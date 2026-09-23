package net.citizensnpcs.util;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Placeholders;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Publishes Citizens-owned values through Paradigm's optional, public placeholder registration API. */
public final class ParadigmPlaceholders {
    private static final Logger LOGGER = LoggerFactory.getLogger(ParadigmPlaceholders.class);
    private static Bridge installed;

    private ParadigmPlaceholders() { }

    public static void install(MinecraftServer server) {
        if (installed != null || !ModList.get().isLoaded("paradigm")) return;
        try {
            installed = new Bridge(server);
            installed.refresh();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            uninstall();
            LOGGER.warn("Could not register Citizens placeholders with Paradigm", failure);
        }
    }

    public static void refresh() {
        if (installed != null) installed.refresh();
    }

    public static void uninstall() {
        if (installed == null) return;
        installed.running = false;
        installed.snapshot = Snapshot.EMPTY;
        installed.release();
        installed = null;
    }

    private enum Key {
        SELECTED_NAME("selected_npc_name"), SELECTED_ID("selected_npc_id"),
        SELECTED_UUID("selected_npc_uuid"), NEAREST_ID("nearest_npc_id");

        final String external;
        Key(String suffix) { external = "citizens_" + suffix; }
    }

    private record Values(String name, String id, String uuid, String nearest) {
        static final Values EMPTY = new Values(null, null, null, null);
        String get(Key key) {
            return switch (key) {
                case SELECTED_NAME -> name;
                case SELECTED_ID -> id;
                case SELECTED_UUID -> uuid;
                case NEAREST_ID -> nearest;
            };
        }
    }

    private record Snapshot(Values console, Map<UUID, Values> players) {
        static final Snapshot EMPTY = new Snapshot(Values.EMPTY, Map.of());
    }

    private static final class Bridge {
        final MinecraftServer server;
        final Method available, capabilities, placeholders, register, playerUuid, status, active, close;
        final Class<?> resolverType;
        final List<Object> handles = new ArrayList<>();
        volatile boolean running = true;
        volatile Snapshot snapshot = Snapshot.EMPTY;
        Object service;
        boolean warned;

        Bridge(MinecraftServer server) throws ReflectiveOperationException {
            this.server = server;
            Class<?> api = Class.forName("eu.avalanche7.paradigm.api.ParadigmAPI");
            available = api.getMethod("isAvailable");
            capabilities = api.getMethod("capabilities");
            placeholders = api.getMethod("placeholders");
            resolverType = Class.forName("eu.avalanche7.paradigm.api.ExternalPlaceholderResolver");
            register = Class.forName("eu.avalanche7.paradigm.api.PlaceholderService")
                    .getMethod("register", String.class, String.class, resolverType);
            playerUuid = Class.forName("eu.avalanche7.paradigm.api.PlaceholderContext").getMethod("playerUuid");
            Class<?> registration = Class.forName("eu.avalanche7.paradigm.api.Registration");
            status = registration.getMethod("status");
            active = registration.getMethod("active");
            close = registration.getMethod("close");
        }

        void refresh() {
            if (!running) return;
            try {
                if (!Boolean.TRUE.equals(available.invoke(null))) {
                    release(); service = null; snapshot = Snapshot.EMPTY;
                    return;
                }
                Object current = placeholders.invoke(null);
                if (current != service) {
                    release(); service = current;
                    if (!(capabilities.invoke(null) instanceof Collection<?> values)
                            || values.stream().noneMatch(value -> "EXTERNAL_PLACEHOLDERS".equals(String.valueOf(value))))
                        throw new IllegalStateException("Paradigm does not expose external placeholders");
                    registerAll();
                    LOGGER.info("Citizens NPC placeholders connected to Paradigm.");
                }
                if (handles.isEmpty()) return;
                Map<UUID, Values> players = new HashMap<>();
                for (ServerPlayer player : server.getPlayerList().getPlayers())
                    players.put(player.getUUID(), query(player.getUUID()));
                snapshot = new Snapshot(query(null), Map.copyOf(players));
                warned = false;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                snapshot = Snapshot.EMPTY;
                if (!warned) LOGGER.warn("Citizens placeholders are unavailable from Paradigm", failure);
                warned = true;
            }
        }

        private void registerAll() throws ReflectiveOperationException {
            try {
                for (Key key : Key.values()) {
                    Object resolver = Proxy.newProxyInstance(resolverType.getClassLoader(), new Class<?>[] { resolverType },
                            (proxy, method, args) -> {
                                if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                                    case "equals" -> proxy == args[0];
                                    case "hashCode" -> System.identityHashCode(proxy);
                                    case "toString" -> "Citizens placeholder " + key.external;
                                    default -> throw new UnsupportedOperationException(method.toString());
                                };
                                if (!running) return null;
                                UUID player = (UUID) ((Optional<?>) playerUuid.invoke(args[0])).orElse(null);
                                if (server.isSameThread()) return query(player).get(key);
                                // Paradigm message delivery runs on the server thread. Other formatter callers can
                                // safely use the last completed tick without reading live entity/registry state.
                                Snapshot published = snapshot;
                                return (player == null ? published.console()
                                        : published.players().getOrDefault(player, published.console())).get(key);
                            });
                    Object handle = register.invoke(service, "citizens", key.external, resolver);
                    handles.add(handle);
                    if (!Boolean.TRUE.equals(active.invoke(handle)) || !"REGISTERED".equals(String.valueOf(status.invoke(handle))))
                        throw new IllegalStateException("Could not own placeholder " + key.external + ": " + status.invoke(handle));
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                // ALREADY_REGISTERED retains the old resolver and increments its reference count. Release our handle
                // as well as this partial batch; never claim or remove somebody else's existing registration.
                release();
                throw failure;
            }
        }

        private Values query(UUID id) {
            ServerPlayer player = id == null ? null : server.getPlayerList().getPlayer(id);
            NPC selected = CitizensAPI.getDefaultNPCSelector().getSelected(player == null
                    ? server.createCommandSourceStack() : player.createCommandSourceStack());
            return new Values(selected == null ? null : selected.getFullName(),
                    selected == null ? null : Integer.toString(selected.getId()),
                    selected == null ? null : selected.getUniqueId().toString(),
                    player == null ? null : Placeholders.replace("<nearest_npc_id>", player));
        }

        void release() {
            for (Object handle : handles) {
                try { close.invoke(handle); }
                catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    LOGGER.warn("Could not release a Citizens placeholder registration", failure);
                }
            }
            handles.clear();
        }
    }
}
