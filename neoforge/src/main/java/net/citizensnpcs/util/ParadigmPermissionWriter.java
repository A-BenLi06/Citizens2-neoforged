package net.citizensnpcs.util;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.api.util.PermissionUtil.PermissionChange;
import net.citizensnpcs.api.util.PermissionUtil.PermissionWriter;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Writes the global permanent permission rules used by upstream's null-world Vault shop operations. */
public final class ParadigmPermissionWriter implements PermissionWriter {
    private static final Logger LOGGER = LoggerFactory.getLogger(ParadigmPermissionWriter.class);
    private static ParadigmPermissionWriter installed;
    private final Backend backend;
    private boolean active = true;

    ParadigmPermissionWriter(Backend backend) {
        this.backend = backend;
    }

    public static void install() {
        if (installed != null || PermissionUtil.getPermissionWriter() != null || !ModList.get().isLoaded("paradigm")
                || !"paradigm:internal".equals(String.valueOf(PermissionAPI.getActivePermissionHandler()))) return;
        try {
            var writer = new ParadigmPermissionWriter(new ApiBackend());
            if (!writer.isAvailable()) return;
            installed = writer;
            PermissionUtil.setPermissionWriter(writer);
            LOGGER.info("Citizens reversible permission changes connected to Paradigm.");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            LOGGER.error("Could not connect Citizens permission writing to Paradigm", failure);
        }
    }

    public static void uninstall() {
        if (installed == null) return;
        installed.active = false;
        if (PermissionUtil.getPermissionWriter() == installed) PermissionUtil.setPermissionWriter(null);
        installed = null;
    }

    @Override
    public boolean isAvailable() {
        return active && backend.available();
    }

    @Override
    public boolean supportsReversibleChanges() {
        return true;
    }

    @Override
    public PermissionChange prepare(ServerPlayer player, Collection<String> permissions, boolean grant) {
        if (player == null || permissions == null) return null;
        List<String> names = normalize(permissions);
        return new Change(backend, player.getUUID(), names, grant, this::isAvailable,
                () -> names.stream().allMatch(permission -> PermissionUtil.hasPermission(player, permission)));
    }

    @Override
    public boolean add(ServerPlayer player, String permission) {
        return mutate(player, permission, true);
    }

    @Override
    public boolean remove(ServerPlayer player, String permission) {
        return mutate(player, permission, false);
    }

    private boolean mutate(ServerPlayer player, String permission, boolean grant) {
        if (player == null || permission == null || permission.isBlank() || !isAvailable()) return false;
        try {
            // The basic remove API removes a direct grant even if a denial currently masks it. Shop costs additionally
            // require effective access, matching their original playerHas check without spending inherited rights.
            var change = new Change(backend, player.getUUID(), normalize(List.of(permission)), grant,
                    this::isAvailable, () -> true);
            if (!change.isPossible()) return false;
            change.apply();
            return true;
        } catch (RuntimeException failure) {
            LOGGER.error("Citizens permission mutation failed for {}", player.getUUID(), failure);
            return false;
        }
    }

    static List<String> normalize(Collection<String> permissions) {
        Set<String> names = new LinkedHashSet<>();
        for (String permission : permissions) {
            if (permission == null || permission.isBlank()) throw new IllegalArgumentException("Permission must not be blank");
            String name = permission.trim().toLowerCase(Locale.ROOT);
            // Paradigm and GroupManager accept a leading '-' as a deny rule, not as part of the node's name.
            // Preserve that rule identity when the provider reports separate node/denied fields.
            if (name.startsWith("-")) {
                String node = name.substring(1).trim();
                if (node.isEmpty()) throw new IllegalArgumentException("A deny rule needs a permission node");
                name = "-" + node;
            }
            names.add(name);
        }
        return List.copyOf(names);
    }

    /** Signed rule identity plus its provider ID; temporary and contextual records are not eligible for these writes. */
    record Rule(String id, String permission) { }

    interface Backend {
        boolean available();

        Set<Rule> rules(UUID player);

        boolean add(UUID player, String permission);

        boolean remove(UUID player, Rule grant);
    }

    static final class Change implements PermissionChange {
        private final Backend backend;
        private final UUID player;
        private final List<String> permissions;
        private final boolean grant;
        private final BooleanSupplier available, eligible;
        private final List<Undo> undo = new ArrayList<>();
        private boolean attempted;

        Change(Backend backend, UUID player, Collection<String> permissions, boolean grant,
                BooleanSupplier available, BooleanSupplier eligible) {
            this.backend = backend;
            this.player = player;
            this.permissions = normalize(permissions);
            this.grant = grant;
            this.available = available;
            this.eligible = eligible;
        }

        @Override
        public boolean isPossible() {
            if (attempted || !available.getAsBoolean() || !backend.available()) return false;
            if (grant) return true;
            Set<Rule> current = backend.rules(player);
            return eligible.getAsBoolean() && permissions.stream()
                    .allMatch(permission -> current.stream().anyMatch(entry -> entry.permission().equals(permission)));
        }

        @Override
        public void apply() {
            if (!isPossible()) throw new IllegalStateException("Permission change is unavailable");
            attempted = true;
            try {
                for (String permission : permissions) {
                    Set<Rule> current = backend.rules(player);
                    List<Rule> matching = current.stream().filter(entry -> entry.permission().equals(permission)).toList();
                    if (grant) {
                        if (!matching.isEmpty()) continue; // An existing grant is not owned by this trade.
                        if (!backend.add(player, permission)) throw new IllegalStateException("Permission grant was rejected: " + permission);
                        Added added = new Added(permission, current);
                        undo.add(added); // Keep the intent even if reading the resulting provider ID fails.
                        added.capture();
                    } else {
                        if (matching.isEmpty()) throw new IllegalStateException("Permission was consumed before the trade: " + permission);
                        for (Rule entry : matching) {
                            if (!backend.remove(player, entry)) throw new IllegalStateException("Permission removal was rejected: " + permission);
                            undo.add(new Removed(entry));
                            if (backend.rules(player).contains(entry)) throw new IllegalStateException("Provider did not remove permission: " + permission);
                        }
                    }
                }
            } catch (RuntimeException failure) {
                try { rollback(); }
                catch (RuntimeException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }

        @Override
        public void rollback() {
            RuntimeException failure = null;
            for (int i = undo.size() - 1; i >= 0; i--) {
                Undo receipt = undo.get(i);
                if (receipt.done) continue;
                try {
                    receipt.restore();
                    receipt.done = true;
                } catch (RuntimeException ex) {
                    if (failure == null) failure = ex;
                    else failure.addSuppressed(ex);
                }
            }
            if (failure != null) throw failure;
        }

        private abstract class Undo {
            boolean done;
            abstract void restore();
        }

        private final class Added extends Undo {
            final String permission;
            final Set<Rule> before;
            Rule added;

            Added(String permission, Set<Rule> before) {
                this.permission = permission;
                this.before = Set.copyOf(before);
            }

            void capture() {
                List<Rule> candidates = backend.rules(player).stream()
                        .filter(entry -> entry.permission().equals(permission) && !before.contains(entry)).toList();
                if (candidates.size() != 1) throw new IllegalStateException("Could not identify the granted permission: " + permission);
                added = candidates.getFirst();
            }

            @Override void restore() {
                if (added == null) {
                    if (backend.rules(player).stream().noneMatch(entry -> entry.permission().equals(permission))) return;
                    capture();
                }
                if (!backend.rules(player).contains(added)) return;
                if (!backend.remove(player, added) && backend.rules(player).contains(added))
                    throw new IllegalStateException("Could not roll back permission grant: " + permission);
                if (backend.rules(player).contains(added)) throw new IllegalStateException("Permission grant rollback was not applied: " + permission);
            }
        }

        private final class Removed extends Undo {
            final Rule removed;

            Removed(Rule removed) { this.removed = removed; }

            @Override void restore() {
                Set<Rule> current = backend.rules(player);
                if (current.contains(removed)) return;
                if (current.stream().anyMatch(entry -> entry.permission().equals(removed.permission())))
                    throw new IllegalStateException("Permission changed before refund: " + removed.permission());
                boolean added = backend.add(player, removed.permission());
                if (!backend.rules(player).contains(removed))
                    throw new IllegalStateException("Could not restore permission " + removed.permission() + " (accepted=" + added + ")");
            }
        }
    }

    private static final class ApiBackend implements Backend {
        private final Method available, services, enabled, info, assignments, id, value, denied, contexts, expires, empty;
        private final Method add, remove;
        private final Object originalServices, handler;

        ApiBackend() throws ReflectiveOperationException {
            Class<?> api = Class.forName("eu.avalanche7.paradigm.api.ParadigmAPI");
            available = api.getMethod("isAvailable");
            services = Class.forName("eu.avalanche7.paradigm.Paradigm").getMethod("getServices");
            originalServices = services.invoke(null);
            handler = Class.forName("eu.avalanche7.paradigm.core.Services").getMethod("getPermissionsHandler").invoke(originalServices);
            Class<?> type = Class.forName("eu.avalanche7.paradigm.modules.permissions.PermissionsHandler");
            enabled = type.getMethod("isInternalPermissionsEnabled");
            info = type.getMethod("getPlayerPermissionInfo", UUID.class);
            add = type.getMethod("addPermissionToPlayer", UUID.class, String.class, boolean.class);
            remove = type.getMethod("removePermissionFromPlayerById", UUID.class, String.class);
            assignments = Class.forName("eu.avalanche7.paradigm.modules.permissions.PermissionAPI$UserInfo").getMethod("assignments");
            Class<?> assignment = Class.forName("eu.avalanche7.paradigm.modules.permissions.PermissionAssignment");
            id = assignment.getMethod("id"); value = assignment.getMethod("value"); denied = assignment.getMethod("denied");
            contexts = assignment.getMethod("contexts"); expires = assignment.getMethod("expiresAtMs");
            empty = Class.forName("eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet").getMethod("isEmpty");
        }

        @Override public boolean available() {
            try {
                return handler != null && Boolean.TRUE.equals(available.invoke(null)) && services.invoke(null) == originalServices
                        && Boolean.TRUE.equals(enabled.invoke(handler));
            } catch (ReflectiveOperationException | RuntimeException failure) { return false; }
        }

        private void requireAvailable() {
            if (!available()) throw new IllegalStateException("The original Paradigm permission service is unavailable");
        }

        @Override public Set<Rule> rules(UUID player) {
            requireAvailable();
            try {
                Object snapshot = info.invoke(handler, player);
                if (snapshot == null || !(assignments.invoke(snapshot) instanceof Collection<?> entries))
                    throw new IllegalStateException("Paradigm returned invalid permission assignments");
                Set<Rule> result = new LinkedHashSet<>();
                for (Object entry : entries) {
                    if (expires.invoke(entry) != null
                            || !Boolean.TRUE.equals(empty.invoke(contexts.invoke(entry)))) continue;
                    String key = (String) id.invoke(entry), permission = (String) value.invoke(entry);
                    if (key == null || key.isBlank() || permission == null || permission.isBlank())
                        throw new IllegalStateException("Paradigm returned an invalid global permission record");
                    String rule = (Boolean.TRUE.equals(denied.invoke(entry)) ? "-" : "")
                            + permission.trim().toLowerCase(Locale.ROOT);
                    result.add(new Rule(key, rule));
                }
                return Set.copyOf(result);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Could not read Paradigm permission assignments", failure);
            }
        }

        @Override public boolean add(UUID player, String permission) {
            requireAvailable();
            try {
                // False is the default denial flag. The provider also accepts an explicit leading '-' in the rule.
                // Opposite-polarity records are separate and are never implicitly removed.
                return Boolean.TRUE.equals(add.invoke(handler, player, permission, false));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Could not add Paradigm permission", failure);
            }
        }

        @Override public boolean remove(UUID player, Rule grant) {
            requireAvailable();
            if (!rules(player).contains(grant)) return false;
            try {
                return Boolean.TRUE.equals(remove.invoke(handler, player, grant.id()));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Could not remove Paradigm permission", failure);
            }
        }
    }
}
