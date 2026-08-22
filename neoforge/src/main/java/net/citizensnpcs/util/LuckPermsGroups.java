package net.citizensnpcs.util;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.api.util.PermissionUtil.GroupResolver;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

/**
 * Answers group queries through LuckPerms when it is installed.
 * <p>
 * Upstream reads groups through Vault, which has no NeoForge counterpart, so {@link PermissionUtil#inGroup} returns
 * "unknown" until something installs a {@link GroupResolver}. This is that something for the permission mod most servers
 * use.
 * <p>
 * The integration is reflective and entirely optional: Citizens neither compiles nor runs against LuckPerms, and with it
 * absent {@link #install} does nothing, leaving group queries exactly as unresolved as before. A declared dependency
 * would make LuckPerms mandatory for everyone; a compile-time one would pin its version.
 * <p>
 * Only <em>loaded</em> users can be answered for. LuckPerms holds a user while they are online and unloads them
 * afterwards, so an offline player yields null — "cannot say" rather than "no", which is what the SPI asks for and the
 * only safe answer for a guard deciding whether someone is an enemy.
 */
public final class LuckPermsGroups {
    private static final String MOD_ID = "luckperms";
    private static Object api;
    private static Method getUserManager;
    private static Method getUser;
    private static Method getContextManager;
    private static Method getQueryOptions;
    private static Method nonContextual;
    private static Method getInheritedGroups;
    private static Method getGroupName;
    /** So a broken lookup is reported once per player rather than once per tick. */
    private static final Set<UUID> WARNED = ConcurrentHashMap.newKeySet();

    private LuckPermsGroups() {
    }

    /**
     * Installs the resolver if LuckPerms is present and its API can be reached.
     *
     * @return true when group queries will now resolve
     */
    public static boolean install() {
        if (!ModList.get().isLoaded(MOD_ID)) {
            Messaging.log("LuckPerms not installed - NPC group checks (shop requirements, player filters, guard"
                    + " targeting) stay unresolved. Install LuckPerms to switch them on.");
            return false;
        }
        try {
            api = Class.forName("net.luckperms.api.LuckPermsProvider").getMethod("get").invoke(null);
            Class<?> userClass = Class.forName("net.luckperms.api.model.user.User");
            Class<?> groupClass = Class.forName("net.luckperms.api.model.group.Group");
            Class<?> queryOptionsClass = Class.forName("net.luckperms.api.query.QueryOptions");

            getUserManager = api.getClass().getMethod("getUserManager");
            getUserManager.setAccessible(true);
            getUser = Class.forName("net.luckperms.api.model.user.UserManager").getMethod("getUser", UUID.class);
            nonContextual = queryOptionsClass.getMethod("nonContextual");
            getInheritedGroups = userClass.getMethod("getInheritedGroups", queryOptionsClass);
            getGroupName = groupClass.getMethod("getName");
            // Contextual options honour per-world group assignments, which is how these groups were set up under
            // GroupManager. The parameter type is looked up rather than named: ContextManager overloads
            // getQueryOptions for User and for a platform subject, and naming the wrong one cost a run on a live
            // server. Its absence is not fatal - nonContextual() is a perfectly good fallback - so this whole block
            // is allowed to fail without taking the integration down with it.
            try {
                getContextManager = api.getClass().getMethod("getContextManager");
                getContextManager.setAccessible(true);
                for (Method candidate : Class.forName("net.luckperms.api.context.ContextManager").getMethods()) {
                    if (candidate.getName().equals("getQueryOptions") && candidate.getParameterCount() == 1
                            && candidate.getParameterTypes()[0].isAssignableFrom(userClass)) {
                        candidate.setAccessible(true);
                        getQueryOptions = candidate;
                        break;
                    }
                }
            } catch (Throwable ex) {
                Messaging.log("LuckPerms context manager unavailable; group checks will ignore per-world contexts:",
                        ex.toString());
            }
        } catch (Throwable ex) {
            Messaging.severe("LuckPerms is installed but its API could not be reached, so NPC group checks stay"
                    + " unresolved:", ex);
            api = null;
            return false;
        }
        PermissionUtil.setGroupResolver(new Resolver());
        Messaging.log("Group checks are resolving through LuckPerms.");
        return true;
    }

    private static class Resolver implements GroupResolver {
        @Override
        public Boolean inGroup(ServerPlayer player, String group) {
            if (api == null || player == null || group == null || group.isEmpty())
                return null;
            try {
                Object user = getUser.invoke(getUserManager.invoke(api), player.getUUID());
                if (user == null)
                    return null;

                Object options = contextualOptions(user);
                Object groups = getInheritedGroups.invoke(user, options);
                if (!(groups instanceof Collection<?>))
                    return null;

                for (Object each : (Collection<?>) groups) {
                    if (group.equalsIgnoreCase(String.valueOf(getGroupName.invoke(each))))
                        return true;
                }
                return false;
            } catch (Throwable ex) {
                if (WARNED.add(player.getUUID())) {
                    Messaging.severe("LuckPerms group lookup failed for", player.getGameProfile().getName(), ex);
                }
                return null;
            }
        }

        /**
         * @return the player's contextual query options, or the non-contextual ones when this build of LuckPerms does not
         *         expose a usable overload
         */
        private Object contextualOptions(Object user) throws Exception {
            if (getQueryOptions != null && getContextManager != null) {
                Object result = getQueryOptions.invoke(getContextManager.invoke(api), user);
                if (result instanceof Optional<?> optional)
                    return optional.isPresent() ? optional.get() : nonContextual.invoke(null);
                if (result != null)
                    return result;
            }
            return nonContextual.invoke(null);
        }
    }
}
