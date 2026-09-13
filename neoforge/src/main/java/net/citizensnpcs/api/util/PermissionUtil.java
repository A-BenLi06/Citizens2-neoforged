package net.citizensnpcs.api.util;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.nodes.PermissionDynamicContext;
import net.neoforged.neoforge.server.permission.nodes.PermissionDynamicContextKey;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

/**
 * Permission checks.
 * <p>
 * Upstream delegates to Bukkit's {@code hasPermission} and, for group queries, to Vault. NeoForge has neither: the
 * platform ships {@link PermissionAPI}, which resolves nodes that must be registered up-front during
 * {@code PermissionGatherEvent.Nodes}, and falls back to operator levels when no permission mod is installed.
 * <p>
 * Registered nodes resolve through the selected NeoForge handler. A {@link PermissionResolver} can also answer
 * arbitrary permission strings from saved shops, command traits and conversations. Explicit decisions precede
 * the upstream defaults; source elevation is supplied to the default resolver, never OR-ed over a backend denial.
 * Group queries have no platform equivalent, so they go through a pluggable {@link GroupResolver} — see
 * {@link #setGroupResolver}. With none installed {@link #inGroup} returns null, the same "unknown" result upstream
 * produces when Vault is absent, which callers already handle.
 */
public class PermissionUtil {
    private PermissionUtil() {
    }

    public static boolean hasPermission(CommandSourceStack source, String permission) {
        if (source == null || permission == null || permission.isBlank())
            return false;
        ServerPlayer player = source.getEntity() instanceof ServerPlayer ? (ServerPlayer) source.getEntity() : null;
        if (player == null)
            // console, command blocks and rcon are unrestricted, matching Bukkit's ConsoleCommandSender
            return true;
        return hasPermission(player, permission,
                player.hasPermissions(FALLBACK_OP_LEVEL) || source.hasPermission(FALLBACK_OP_LEVEL));
    }

    public static boolean hasPermission(ServerPlayer player, String permission) {
        return hasPermission(player, permission, player != null && player.hasPermissions(FALLBACK_OP_LEVEL));
    }

    private static boolean hasPermission(ServerPlayer player, String permission, boolean operator) {
        if (player == null || permission == null || permission.isBlank())
            return false;
        permission = PermissionDefaults.normalize(permission);
        Boolean temporary = temporaryPermission(player, permission);
        if (temporary != null) return temporary;
        PermissionNode<Boolean> node = NODES.get(permission);
        // Nodes declared after gathering cannot be handed to PermissionAPI until the next server lifecycle.
        if (node != null && PermissionAPI.getRegisteredNodes().contains(node))
            return Boolean.TRUE.equals(PermissionAPI.getPermission(player, node, OPERATOR.createContext(operator)));
        Boolean explicit = queryExplicit(player, permission);
        return explicit != null ? explicit : resolveDefault(player, permission, operator);
    }

    private static Boolean queryExplicit(ServerPlayer player, String permission) {
        PermissionResolver resolver = permissionResolver;
        return resolver == null || player == null ? null : resolver.check(player, permission);
    }

    private static boolean resolveDefault(ServerPlayer player, String permission, boolean operator) {
        for (PermissionDefaults.Parent parent : DEFAULTS.parents(permission)) {
            Boolean explicit = queryExplicit(player, parent.permission());
            if (explicit != null) return parent.inherit(explicit);
        }
        return DEFAULTS.fallback(permission, operator);
    }

    /** Optional tri-state bridge for names that are not known when NeoForge gathers nodes. */
    @FunctionalInterface
    public interface PermissionResolver {
        /** @return an authoritative grant/denial, or null when no rule is defined */
        Boolean check(ServerPlayer player, String permission);
    }

    public static void setPermissionResolver(PermissionResolver resolver) {
        permissionResolver = resolver;
    }

    public static PermissionResolver getPermissionResolver() {
        return permissionResolver;
    }

    /** Upstream signature: true if the entity holds any one of {@code permissions}. */
    public static boolean hasPermission(Set<String> permissions, Entity entity) {
        if (!(entity instanceof ServerPlayer))
            return false;
        ServerPlayer player = (ServerPlayer) entity;
        return permissions.stream().anyMatch(permission -> hasPermission(player, permission));
    }

    /**
     * @return true if the player is in any of {@code groups}; false if in none; null if group membership cannot be
     *         determined because no {@link GroupResolver} is installed
     */
    public static Boolean inGroup(Collection<String> groups, ServerPlayer player) {
        GroupResolver resolver = groupResolver;
        if (resolver == null || player == null || groups == null || groups.isEmpty())
            return null;
        boolean anyKnown = false;
        for (String group : groups) {
            Boolean in = resolver.inGroup(player, group);
            if (Boolean.TRUE.equals(in))
                return true;
            if (in != null) {
                anyKnown = true;
            }
        }
        return anyKnown ? false : null;
    }

    /**
     * Installs the bridge to whatever permission mod is present.
     * <p>
     * Upstream reads groups through Vault, which has no NeoForge counterpart, so group-based features (shop
     * requirements, {@code PlayerFilter}, command trait gating) would otherwise be permanently unavailable rather than
     * merely unconfigured. Registering a resolver — from a LuckPerms compatibility layer, an addon, or a server pack's
     * own code — switches them back on without any change here.
     *
     * @param resolver
     *            the resolver, or null to go back to "unknown"
     */
    public static void setGroupResolver(GroupResolver resolver) {
        groupResolver = resolver;
    }

    public static GroupResolver getGroupResolver() {
        return groupResolver;
    }

    /** Bridge to a permission mod's group model. */
    @FunctionalInterface
    public interface GroupResolver {
        /**
         * @return whether the player belongs to {@code group}, or null if this resolver cannot say
         */
        Boolean inGroup(ServerPlayer player, String group);
    }

    /**
     * Grants permissions to a player for as long as the returned handle is held — what the command trait's temporary
     * permissions are for, so that a command an NPC dispatches on the player's behalf can pass a permission check the
     * player would normally fail.
     * <p>
     * Upstream uses Bukkit's {@code PermissionAttachment}, which has no NeoForge counterpart: nothing on the platform can
     * add a permission to a player at runtime. Rather than drop the feature, grants are tracked here and consulted by
     * {@link #hasPermission}, so every check that goes through Citizens honours them. A permission mod can make the grant
     * visible to <em>its own</em> checks as well by installing a {@link TemporaryPermissionGranter}, which is called in
     * addition to the internal tracking.
     *
     * @return a handle whose {@link Attachment#remove()} revokes the grant. Idempotent, so an early removal followed by
     *         the scheduled one is harmless.
     */
    public static Attachment grantTemporary(ServerPlayer player, Collection<String> permissions) {
        if (player == null || permissions == null || permissions.isEmpty())
            return () -> {
            };
        List<String> names = permissions.stream().filter(p -> p != null && !p.isBlank())
                .map(PermissionDefaults::normalize).toList();
        if (names.isEmpty()) return () -> {};
        UUID uuid = player.getUUID();
        Map<String, Integer> granted = TEMPORARY.computeIfAbsent(uuid, id -> new ConcurrentHashMap<>());
        for (String permission : names) {
            granted.merge(permission, 1, Integer::sum);
        }
        TemporaryPermissionGranter granter = temporaryPermissionGranter;
        Attachment delegate;
        try {
            delegate = granter == null ? null : granter.grant(player, names);
        } catch (RuntimeException | Error failure) {
            revokeTemporary(uuid, granted, names);
            throw failure;
        }
        return new Attachment() {
            private boolean removed;

            @Override
            public void remove() {
                if (removed)
                    return;
                removed = true;
                revokeTemporary(uuid, granted, names);
                if (delegate != null) {
                    delegate.remove();
                }
            }
        };
    }

    private static Boolean temporaryPermission(ServerPlayer player, String permission) {
        Map<String, Integer> granted = TEMPORARY.get(player.getUUID());
        if (granted == null) return null;
        if (granted.containsKey(permission)) return true;
        for (PermissionDefaults.Parent parent : DEFAULTS.parents(permission)) {
            if (granted.containsKey(parent.permission())) return parent.value();
        }
        return null;
    }

    private static void revokeTemporary(UUID player, Map<String, Integer> held, Collection<String> permissions) {
        if (TEMPORARY.get(player) != held) return;
        for (String permission : permissions) {
            held.compute(permission, (p, count) -> count == null || count <= 1 ? null : count - 1);
        }
        if (held.isEmpty()) TEMPORARY.remove(player, held);
    }

    /** Drops any grants held by a player, called when they log out so nothing is left behind. */
    public static void clearTemporary(UUID player) {
        TEMPORARY.remove(player);
    }

    /** Releases local attachments between dedicated or integrated server lifecycles. */
    public static void clearTemporary() {
        TEMPORARY.clear();
    }

    public static void setTemporaryPermissionGranter(TemporaryPermissionGranter granter) {
        temporaryPermissionGranter = granter;
    }

    /**
     * Grants a permission for good — what a shop selling a rank or an unlock needs. Returns false when nothing can write
     * permissions, which callers must treat as a refusal rather than a success: a shop that cannot deliver what it sells
     * must not take payment for it.
     * <p>
     * Upstream calls Vault's {@code playerAdd}. NeoForge has no permission-writing API at all — {@link PermissionAPI} only
     * reads — so this goes through a {@link PermissionWriter} that a permission mod (or a server pack's own glue code)
     * installs.
     */
    public static boolean addPermission(ServerPlayer player, String permission) {
        PermissionWriter writer = permissionWriter;
        return writer != null && writer.isAvailable() && writer.add(player, permission);
    }

    public static boolean removePermission(ServerPlayer player, String permission) {
        PermissionWriter writer = permissionWriter;
        return writer != null && writer.isAvailable() && writer.remove(player, permission);
    }

    /** Whether permissions can be granted at all, i.e. whether a {@link PermissionWriter} is installed. */
    public static boolean canWritePermissions() {
        PermissionWriter writer = permissionWriter;
        return writer != null && writer.isAvailable();
    }

    /** Whether the provider supports the reversible changes required by a shop trade. */
    public static boolean canWriteReversiblePermissions() {
        PermissionWriter writer = permissionWriter;
        return writer != null && writer.isAvailable() && writer.supportsReversibleChanges();
    }

    public static void setPermissionWriter(PermissionWriter writer) {
        permissionWriter = writer;
    }

    public static PermissionWriter getPermissionWriter() {
        return permissionWriter;
    }

    /** Prepares a reversible global permission change without applying it; null means unsupported/unavailable. */
    public static PermissionChange preparePermissionChange(ServerPlayer player, Collection<String> permissions,
            boolean grant) {
        PermissionWriter writer = permissionWriter;
        return writer == null || !writer.isAvailable() || !writer.supportsReversibleChanges()
                ? null : writer.prepare(player, permissions, grant);
    }

    /** A single-use change. A failed apply must compensate its own partial writes before propagating the failure. */
    public interface PermissionChange {
        boolean isPossible();

        void apply();

        /** Idempotent; a failed rollback may be retried without repeating its successful parts. */
        void rollback();
    }

    /** Bridge to a permission mod that can persist permission changes. */
    public interface PermissionWriter {
        boolean add(ServerPlayer player, String permission);

        boolean remove(ServerPlayer player, String permission);

        default boolean isAvailable() {
            return true;
        }

        default boolean supportsReversibleChanges() {
            return false;
        }

        /**
         * Shop trades need provider-owned receipts, not an unconditional inverse add/remove. Basic writers remain
         * usable through add/remove; they must implement this capability before being used in reversible trades.
         */
        default PermissionChange prepare(ServerPlayer player, Collection<String> permissions, boolean grant) {
            return null;
        }

        /**
         * @return whether the player holds the permission according to the mod, which may differ from
         *         {@link PermissionUtil#hasPermission} when the permission was never registered as a node
         */
        default boolean has(ServerPlayer player, String permission) {
            return hasPermission(player, permission);
        }
    }

    /** A held permission grant. */
    @FunctionalInterface
    public interface Attachment {
        void remove();
    }

    /** Bridge letting a permission mod apply {@link #grantTemporary} grants inside its own permission model. */
    @FunctionalInterface
    public interface TemporaryPermissionGranter {
        Attachment grant(ServerPlayer player, Collection<String> permissions);
    }

    /**
     * Declares a permission so that it can be resolved through {@link PermissionAPI} rather than falling back to the
     * operator level. Must be called before {@code PermissionGatherEvent.Nodes} fires.
     *
     * @param permission
     *            a dotted permission string, e.g. {@code citizens.npc.create}
     */
    public static PermissionNode<Boolean> register(String permission) {
        String name = PermissionDefaults.normalize(permission);
        int dot = name.indexOf('.');
        if (dot <= 0 || dot == name.length() - 1)
            throw new IllegalArgumentException("A NeoForge permission node needs a dotted name: " + permission);
        return NODES.computeIfAbsent(name, p -> new PermissionNode<>(p.substring(0, dot), p.substring(dot + 1),
                PermissionTypes.BOOLEAN, (player, playerUUID, context) -> {
                    boolean operator = player != null && player.hasPermissions(FALLBACK_OP_LEVEL);
                    for (PermissionDynamicContext<?> value : context) {
                        if (OPERATOR.equals(value.getDynamic())) operator = Boolean.TRUE.equals(value.getValue());
                    }
                    return resolveDefault(player, p, operator);
                }, OPERATOR));
    }

    /** Called before gathering, in addition to the command/flag declarations discovered by CommandManager. */
    public static void registerDefaults() {
        DEFAULTS.permissions().forEach(PermissionUtil::register);
    }

    /** All nodes declared so far, for handing to {@code PermissionGatherEvent.Nodes#addNodes}. */
    public static Collection<PermissionNode<Boolean>> getRegisteredNodes() {
        return List.copyOf(NODES.values());
    }

    private static final int FALLBACK_OP_LEVEL = 2;
    private static final PermissionDefaults DEFAULTS = PermissionDefaults.bundled();
    private static final PermissionDynamicContextKey<Boolean> OPERATOR = new PermissionDynamicContextKey<>(
            Boolean.class, "citizens_operator", Object::toString);
    private static final Map<UUID, Map<String, Integer>> TEMPORARY = new ConcurrentHashMap<>();
    private static volatile TemporaryPermissionGranter temporaryPermissionGranter;
    private static volatile PermissionWriter permissionWriter;
    private static volatile GroupResolver groupResolver;
    private static volatile PermissionResolver permissionResolver;
    private static final Map<String, PermissionNode<Boolean>> NODES = new ConcurrentHashMap<>();
}
