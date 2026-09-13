package net.citizensnpcs.util;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.api.util.PermissionUtil.GroupResolver;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

/**
 * Answers group queries through Paradigm Essentials when it is installed.
 * <p>
 * Citizens needs a source of group membership for {@code byGroup} guard rules, shop requirements and player filters.
 * Upstream gets it from Vault; on NeoForge 1.21.1 the obvious candidate, LuckPerms, is not usable — the only two builds
 * declaring 1.21.1 support date from August 2024, and the newer of them kicks every player at login because a mod
 * querying NeoForge's {@code PermissionAPI} during {@code sendCommands} reaches LuckPerms' user capability before it is
 * initialised (LuckPerms issues #4106 and #4235). Paradigm is maintained against 1.21.1 and publishes a versioned API
 * with exactly the query needed.
 * <p>
 * Like {@link LuckPermsGroups} this is entirely reflective and optional: Citizens neither compiles nor runs against
 * Paradigm, and with it absent {@link #install} does nothing. Paradigm is separately licensed (CC-BY-NC-ND-4.0); calling
 * its published API at runtime neither bundles nor modifies it.
 * <p>
 * Membership starts with {@code PermissionService.metadata(uuid)}. Paradigm 2.4.2b's {@code resolvedGroups()}
 * contains assignments, not the complete ancestor graph, so parent groups are read from its public group-info API.
 * Its UUID metadata uses server/network contexts. World/dimension assignments are read from its public user-info
 * API and matched by the provider's own context resolver, so moving between dimensions immediately changes membership.
 */
public final class ParadigmGroups {
    private static final String MOD_ID = "paradigm";
    /** The capability that gates {@code metadata()}; without it there is no membership to read. */
    private static final String METADATA_CAPABILITY = "PERMISSION_METADATA";

    private static Method permissions;
    private static Method metadata;
    private static Method resolvedGroups;
    private static Method primaryGroup;
    private static Method services, handler, groupInfo, inherits;
    private static GroupResolver installed;
    private static ContextualMemberships contextualMemberships;
    /** So a broken lookup is reported once per player rather than once per tick. */
    private static final Set<UUID> WARNED = ConcurrentHashMap.newKeySet();

    private ParadigmGroups() {
    }

    /**
     * Installs the resolver if Paradigm is present and exposes permission metadata.
     *
     * @return true when group queries will now resolve
     */
    public static boolean install() {
        if (!ModList.get().isLoaded(MOD_ID))
            return false;
        try {
            Class<?> api = Class.forName("eu.avalanche7.paradigm.api.ParadigmAPI");
            if (!Boolean.TRUE.equals(api.getMethod("isAvailable").invoke(null))) {
                Messaging.log("Paradigm is installed but its API is not available yet; NPC group checks stay"
                        + " unresolved.");
                return false;
            }
            // metadata() is capability-gated, so a build without it must not be treated as a working resolver
            Object capabilities = api.getMethod("capabilities").invoke(null);
            if (!(capabilities instanceof Collection<?>) || !containsNamed((Collection<?>) capabilities,
                    METADATA_CAPABILITY)) {
                Messaging.log("Paradigm does not expose", METADATA_CAPABILITY,
                        "- NPC group checks stay unresolved. Its permission checks are unaffected.");
                return false;
            }
            permissions = api.getMethod("permissions");
            metadata = Class.forName("eu.avalanche7.paradigm.api.PermissionService").getMethod("metadata", UUID.class);
            Class<?> meta = Class.forName("eu.avalanche7.paradigm.api.PlayerPermissionMeta");
            resolvedGroups = meta.getMethod("resolvedGroups");
            primaryGroup = meta.getMethod("primaryGroup");
            services = Class.forName("eu.avalanche7.paradigm.Paradigm").getMethod("getServices");
            handler = Class.forName("eu.avalanche7.paradigm.core.Services").getMethod("getPermissionsHandler");
            groupInfo = Class.forName("eu.avalanche7.paradigm.modules.permissions.PermissionsHandler")
                    .getMethod("getPermissionGroupInfo", String.class);
            inherits = Class.forName("eu.avalanche7.paradigm.modules.permissions.PermissionAPI$GroupInfo")
                    .getMethod("inherits");
            contextualMemberships = new ContextualMemberships();
        } catch (Throwable ex) {
            Messaging.severe("Paradigm is installed but its API could not be reached, so NPC group checks stay"
                    + " unresolved:", ex);
            permissions = null;
            return false;
        }
        WARNED.clear();
        installed = new Resolver();
        PermissionUtil.setGroupResolver(installed);
        Messaging.log("Group checks are resolving through Paradigm.");
        return true;
    }

    public static void uninstall() {
        if (installed != null && PermissionUtil.getGroupResolver() == installed) PermissionUtil.setGroupResolver(null);
        installed = null;
        permissions = null;
        contextualMemberships = null;
        WARNED.clear();
    }

    /** @return whether any enum constant in the collection has this name */
    private static boolean containsNamed(Collection<?> values, String name) {
        for (Object value : values) {
            if (value != null && name.equals(value.toString()))
                return true;
        }
        return false;
    }

    /**
     * The matching rule, separated from the reflection so it can be tested directly.
     *
     * @return true if the group is the player's primary group or among their resolved groups
     */
    static boolean matches(Collection<?> resolved, Object primary, String group) {
        String wanted = group.trim().toLowerCase(Locale.ROOT);
        if (primary != null && wanted.equals(String.valueOf(primary).trim().toLowerCase(Locale.ROOT)))
            return true;
        if (resolved == null)
            return false;
        for (Object each : resolved) {
            if (each != null && wanted.equals(String.valueOf(each).trim().toLowerCase(Locale.ROOT)))
                return true;
        }
        return false;
    }

    static boolean matchesIncludingParents(Collection<?> resolved, Object primary, String group, ParentLookup parents)
            throws ReflectiveOperationException {
        if (matches(resolved, primary, group)) return true;
        ArrayDeque<String> pending = new ArrayDeque<>();
        if (primary != null) pending.add(primary.toString());
        if (resolved != null) for (Object value : resolved) if (value != null) pending.add(value.toString());
        Set<String> seen = new HashSet<>();
        String wanted = group.trim().toLowerCase(Locale.ROOT);
        while (!pending.isEmpty()) {
            String name = pending.removeFirst().trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty() || !seen.add(name)) continue;
            if (wanted.equals(name)) return true;
            Collection<?> ancestors = parents.get(name);
            if (ancestors != null) for (Object ancestor : ancestors) if (ancestor != null) pending.add(ancestor.toString());
        }
        return false;
    }

    @FunctionalInterface
    interface ParentLookup {
        Collection<?> get(String group) throws ReflectiveOperationException;
    }

    private static class Resolver implements GroupResolver {
        @Override
        public Boolean inGroup(ServerPlayer player, String group) {
            if (permissions == null || player == null || group == null || group.isEmpty())
                return null;
            try {
                Object service = permissions.invoke(null);
                if (service == null)
                    return null;
                Object meta = metadata.invoke(service, player.getUUID());
                if (meta == null)
                    return null;
                Object resolved = resolvedGroups.invoke(meta);
                Object provider = handler.invoke(services.invoke(null));
                List<Object> groups = new ArrayList<>();
                if (resolved instanceof Collection<?> collection) groups.addAll(collection);
                groups.addAll(contextualMemberships.groups(provider, player));
                return matchesIncludingParents(groups,
                        primaryGroup.invoke(meta), group, name -> {
                            Object info = groupInfo.invoke(provider, name);
                            if (info == null) return List.of();
                            Object parents = inherits.invoke(info);
                            return parents instanceof Collection<?> collection ? collection : List.of();
                        });
            } catch (Throwable ex) {
                // "cannot say" rather than "no" - the only safe answer for a guard deciding whether someone is an enemy
                if (WARNED.add(player.getUUID())) {
                    Messaging.severe("Paradigm group lookup failed for", player.getGameProfile().getName(), ex);
                }
                return null;
            }
        }
    }

    /** Public provider APIs retain the context/expiry information that the small UUID metadata facade omits. */
    private static final class ContextualMemberships {
        private final Method userInfo, groupAssignments, value, expired, denied, contexts, match, matches;
        private final Method platform, wrapPlayer, resolveContext;
        private final Object contextResolver;

        ContextualMemberships() throws ReflectiveOperationException {
            Class<?> serviceType = Class.forName("eu.avalanche7.paradigm.core.Services");
            Class<?> handlerType = Class.forName("eu.avalanche7.paradigm.modules.permissions.PermissionsHandler");
            Class<?> assignment = Class.forName("eu.avalanche7.paradigm.modules.permissions.PermissionAssignment");
            Class<?> contextSet = Class.forName("eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet");
            userInfo = handlerType.getMethod("getPlayerPermissionInfo", UUID.class);
            groupAssignments = Class.forName("eu.avalanche7.paradigm.modules.permissions.PermissionAPI$UserInfo")
                    .getMethod("groupAssignments");
            value = assignment.getMethod("value");
            expired = assignment.getMethod("expired");
            denied = assignment.getMethod("denied");
            contexts = assignment.getMethod("contexts");
            match = contextSet.getMethod("match", contextSet);
            matches = Class.forName("eu.avalanche7.paradigm.modules.permissions.context.PermissionContextMatchResult")
                    .getMethod("matches");
            platform = serviceType.getMethod("getPlatformAdapter");
            wrapPlayer = Class.forName("eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter")
                    .getMethod("wrapPlayer", Object.class);
            Method storage = serviceType.getMethod("getStorageService");
            Method storageContext = Class.forName("eu.avalanche7.paradigm.storage.StorageService").getMethod("context");
            Method identity = Class.forName("eu.avalanche7.paradigm.storage.identity.StorageContext")
                    .getMethod("serverIdentity");
            Class<?> resolver = Class.forName("eu.avalanche7.paradigm.modules.permissions.context.PermissionContextResolver");
            contextResolver = resolver.getConstructor(Supplier.class).newInstance((Supplier<Object>) () -> {
                try {
                    return identity.invoke(storageContext.invoke(storage.invoke(services.invoke(null))));
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Could not resolve Paradigm server identity", failure);
                }
            });
            resolveContext = resolver.getMethod("resolve",
                    Class.forName("eu.avalanche7.paradigm.platform.Interfaces.IPlayer"));
        }

        Collection<String> groups(Object provider, ServerPlayer player) throws ReflectiveOperationException {
            Object wrapped = wrapPlayer.invoke(platform.invoke(services.invoke(null)), player);
            if (wrapped == null) throw new IllegalStateException("Paradigm could not resolve the online player");
            Object activeContext = resolveContext.invoke(contextResolver, wrapped);
            Object info = userInfo.invoke(provider, player.getUUID());
            if (info == null) throw new IllegalStateException("Paradigm returned no user permission information");
            Object assignments = groupAssignments.invoke(info);
            if (!(assignments instanceof Collection<?> values))
                throw new IllegalStateException("Paradigm returned invalid group assignments");
            List<String> active = new ArrayList<>();
            for (Object assignment : values) {
                if (Boolean.TRUE.equals(expired.invoke(assignment)) || Boolean.TRUE.equals(denied.invoke(assignment))) continue;
                Object required = contexts.invoke(assignment);
                if (!Boolean.TRUE.equals(matches.invoke(match.invoke(required, activeContext)))) continue;
                String name = (String) value.invoke(assignment);
                if (name != null && !name.isBlank() && groupInfo.invoke(provider, name) != null) active.add(name);
            }
            return active;
        }
    }
}
