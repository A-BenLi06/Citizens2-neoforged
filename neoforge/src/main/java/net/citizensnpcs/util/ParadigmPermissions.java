package net.citizensnpcs.util;

import java.lang.reflect.Method;
import java.util.Collection;

import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.api.util.PermissionUtil.PermissionResolver;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Optional tri-state permission queries for the selected Paradigm provider, including runtime-defined names. */
public final class ParadigmPermissions {
    private static final Logger LOGGER = LoggerFactory.getLogger(ParadigmPermissions.class);
    private static PermissionResolver installed;

    private ParadigmPermissions() {
    }

    public static void install() {
        if (installed != null || PermissionUtil.getPermissionResolver() != null
                || !ModList.get().isLoaded("paradigm")
                || !"paradigm:internal".equals(String.valueOf(PermissionAPI.getActivePermissionHandler()))) return;
        try {
            installed = new Resolver();
            LOGGER.info("Citizens dynamic permission checks connected to Paradigm.");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            // The selected provider is present but incompatible: treating this as UNDEFINED would grant OP defaults
            // without knowing whether the provider explicitly denied access. Keep dynamic checks closed instead.
            installed = (player, permission) -> false;
            LOGGER.error("Could not connect Citizens dynamic permission checks to the selected Paradigm provider", failure);
        }
        PermissionUtil.setPermissionResolver(installed);
    }

    public static void uninstall() {
        if (installed != null && PermissionUtil.getPermissionResolver() == installed)
            PermissionUtil.setPermissionResolver(null);
        installed = null;
    }

    private static final class Resolver implements PermissionResolver {
        private final Method available, services, permissionsHandler, platformAdapter, wrapPlayer, query;
        private boolean warned;

        Resolver() throws ReflectiveOperationException {
            Class<?> api = Class.forName("eu.avalanche7.paradigm.api.ParadigmAPI");
            available = api.getMethod("isAvailable");
            if (!Boolean.TRUE.equals(available.invoke(null)))
                throw new IllegalStateException("Paradigm permission API is unavailable after server startup");
            Object capabilities = api.getMethod("capabilities").invoke(null);
            if (!(capabilities instanceof Collection<?> values)
                    || values.stream().noneMatch(value -> "PERMISSION_CHECKS".equals(String.valueOf(value))))
                throw new IllegalStateException("Paradigm does not expose permission checks");

            // The versioned UUID/PermissionContext facade requires callers to supply all contexts, and GLOBAL
            // omits the current server/network. Use the provider's public online query, also used by its NeoForge
            // handler, so world, dimension, server, network and inherited groups come from its own live resolver.
            // No provider internals are mutated or bundled. Missing public methods make installation fail closed.
            services = Class.forName("eu.avalanche7.paradigm.Paradigm").getMethod("getServices");
            Class<?> serviceType = Class.forName("eu.avalanche7.paradigm.core.Services");
            permissionsHandler = serviceType.getMethod("getPermissionsHandler");
            platformAdapter = serviceType.getMethod("getPlatformAdapter");
            wrapPlayer = Class.forName("eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter")
                    .getMethod("wrapPlayer", Object.class);
            query = Class.forName("eu.avalanche7.paradigm.modules.permissions.PermissionsHandler")
                    .getMethod("queryDefinedPermission",
                            Class.forName("eu.avalanche7.paradigm.platform.Interfaces.IPlayer"), String.class);
        }

        @Override
        public Boolean check(ServerPlayer player, String permission) {
            try {
                if (!Boolean.TRUE.equals(available.invoke(null)))
                    throw new IllegalStateException("Paradigm permission API is unavailable");
                Object service = services.invoke(null);
                Object handler = permissionsHandler.invoke(service);
                Object wrapped = wrapPlayer.invoke(platformAdapter.invoke(service), player);
                if (handler == null || wrapped == null)
                    throw new IllegalStateException("Paradigm could not resolve the online player");
                return (Boolean) query.invoke(handler, wrapped, permission);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                if (!warned) {
                    warned = true;
                    LOGGER.error("Citizens permission lookup failed; denying access until the provider recovers", failure);
                }
                return false;
            }
        }
    }
}
