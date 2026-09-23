package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.PlayerFilter;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.trait.ClickRedirectTrait;
import net.citizensnpcs.trait.HologramTrait;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import net.citizensnpcs.api.event.NPCSeenByPlayerEvent;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.MirrorTrait;
import net.citizensnpcs.trait.PacketNPC;
import net.citizensnpcs.util.NPCVisibility;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.citizensnpcs.util.ParadigmPlaceholders;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
/** Actual optional Paradigm registration and native message delivery; no substituted provider implementation. */
@EventBusSubscriber(modid = "citizens")
public final class ParadigmPlaceholderRuntimeAudit {
    private static boolean forced, done, failed, provider;
    private static int phase, nextTick, passed, deadline, messageId;
    private static NPCRegistry registry;
    private static NPC alpha, beta;
    private static Actor alice, bob;
    private static final List<Actor> actors = new ArrayList<>();
    private static final List<Expected> messages = new ArrayList<>();
    private static Method register, status, active, close, resolve, send;
    private static Object service, messageService, unrelated, collision;
    private static Class<?> resolverType;
    private static List<?> shutdownHandles = List.of();
    private static final String[] KEYS = {"citizens_selected_npc_name", "citizens_selected_npc_id", "citizens_selected_npc_uuid", "citizens_nearest_npc_id"};
    private record Expected(Actor actor, String marker, String value) { }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        MinecraftServer server = event.getServer(); ServerLevel level = server.overworld();
        if (!forced) { level.setChunkForced(0, 0, true); forced = true; deadline = server.getTickCount() + 1000; }
        try {
            for (Actor actor : actors) actor.pump();
            if (server.getTickCount() > deadline) throw new AssertionError("timed_out_phase_" + phase);
            if (!level.areEntitiesLoaded(0L) || !level.isPositionEntityTicking(new BlockPos(1, -60, 1))) return;
            if (server.getTickCount() < nextTick) return;
            if (phase == 1 && (!alice.watches(level, 0, 0) || !bob.watches(level, 0, 0))) return;
            nextTick = server.getTickCount() + 20;
            for (Expected expected : messages) {
                check(expected.actor.packets.stream().anyMatch(p -> p instanceof ClientboundSystemChatPacket chat
                        && chat.content().getString().equals(expected.marker + expected.value)), "native_message_" + expected.marker + "_" + expected.actor.packets.stream().filter(p -> p instanceof ClientboundSystemChatPacket).map(p -> ((ClientboundSystemChatPacket)p).content().getString()).toList());
            }
            messages.clear();
            switch (phase++) {
                case 0 -> {
                    check(Files.isRegularFile(Path.of("paradigm-placeholder-audit-fixture.txt")) && !CitizensAPI.getNPCRegistry().iterator().hasNext(), "isolated_empty_fixture");
                    provider = ModList.get().isLoaded("paradigm");
                    check(provider == Boolean.parseBoolean(Files.readString(Path.of("expect-provider.txt")).trim()), "expected_provider_mode");
                    alice = actor(server, "ValueAlice", 4); bob = actor(server, "ValueBob", 12);
                    registry = CitizensAPI.createNamedNPCRegistry("placeholder-audit", new MemoryNPCDataStore());
                    alpha = npc(level, "Alpha", 2); beta = npc(level, "Beta", 14);
                    CitizensAPI.getDefaultNPCSelector().select(alice.player.createCommandSourceStack(), alpha);
                    CitizensAPI.getDefaultNPCSelector().select(bob.player.createCommandSourceStack(), beta);
                    CitizensAPI.getDefaultNPCSelector().select(server.createCommandSourceStack(), beta);
                }
                case 1 -> {
                    if (!provider) {
                        check(installed() == null, "absent_provider_installs_nothing");
                        ParadigmPlaceholders.install(server); ParadigmPlaceholders.refresh(); ParadigmPlaceholders.uninstall();
                        check(installed() == null, "absent_provider_lifecycle_is_safe");
                        check(net.citizensnpcs.api.util.Placeholders.replace("<player>:<id>", alice.player.createCommandSourceStack(), alpha).equals("ValueAlice:" + alpha.getId()), "citizens_own_placeholders_work_without_provider");
                        done = true; break;
                    }
                    Class<?> api = Class.forName("eu.avalanche7.paradigm.api.ParadigmAPI");
                    check(Boolean.TRUE.equals(api.getMethod("isAvailable").invoke(null)) && installed() != null, "real_provider_connected_after_startup");
                    service = api.getMethod("placeholders").invoke(null); messageService = api.getMethod("messages").invoke(null);
                    resolverType = Class.forName("eu.avalanche7.paradigm.api.ExternalPlaceholderResolver");
                    register = Class.forName("eu.avalanche7.paradigm.api.PlaceholderService").getMethod("register", String.class, String.class, resolverType);
                    Class<?> registration = Class.forName("eu.avalanche7.paradigm.api.Registration");
                    status = registration.getMethod("status"); active = registration.getMethod("active"); close = registration.getMethod("close");
                    resolve = Class.forName("eu.avalanche7.paradigm.api.internal.ApiProviderRegistry").getMethod("resolveExternalPlaceholders", String.class, UUID.class);
                    send = Class.forName("eu.avalanche7.paradigm.api.MessageService").getMethod("sendPlayerMessage", UUID.class, String.class, Map.class);
                    for (String key : KEYS) {
                        Object duplicate = registration("citizens", key, "wrong");
                        check(status.invoke(duplicate).toString().equals("ALREADY_REGISTERED"), "owned_key_registered_" + key); close.invoke(duplicate);
                    }
                    expect(alice, all(), "Alpha|" + alpha.getId() + "|" + alpha.getUniqueId() + "|" + alpha.getId());
                    expect(bob, all(), "Beta|" + beta.getId() + "|" + beta.getUniqueId() + "|" + beta.getId());
                }
                case 2 -> {
                    alpha.setName("Cost $5");
                    expect(alice, "{citizens_selected_npc_name}", "Cost $5");
                    check(raw("{citizens_selected_npc_id}", alice.player.getUUID()).equals(Integer.toString(alpha.getId())), "main_thread_live_selection");
                    CitizensAPI.getDefaultNPCSelector().select(alice.player.createCommandSourceStack(), beta);
                    check(raw("{citizens_selected_npc_id}", alice.player.getUUID()).equals(Integer.toString(beta.getId())), "selection_change_visible_immediately");
                    check(raw("{not_owned}", alice.player.getUUID()).equals("{not_owned}"), "unrelated_placeholder_untouched");
                    expect(alice, "{citizens_selected_npc_id}", Integer.toString(beta.getId()));
                }
                case 3 -> {
                    check(CompletableFuture.supplyAsync(() -> raw("{citizens_selected_npc_id}", alice.player.getUUID())).get(2, TimeUnit.SECONDS).equals(Integer.toString(beta.getId())), "worker_uses_published_player_selection_without_blocking_server");
                    check(CompletableFuture.supplyAsync(() -> raw("{citizens_selected_npc_id}", null)).get(2, TimeUnit.SECONDS).equals(Integer.toString(beta.getId())), "worker_server_context_uses_console_selection");
                    check(raw("{citizens_selected_npc_id}", UUID.randomUUID()).equals(Integer.toString(beta.getId())), "offline_context_matches_reference_console_fallback");
                    check(raw("{citizens_nearest_npc_id}", null).isEmpty(), "server_context_has_no_nearest_npc");
                    check(raw("{citizens_nearest_npc_id}", alice.player.getUUID()).equals(Integer.toString(alpha.getId())), "nearest_independent_of_selection");
                    alpha.getEntity().setPos(100, -60, 4);
                    beta.getOrAddTrait(PlayerFilter.class).addPlayer(alice.player.getUUID());
                }
                case 4 -> {
                    check(raw("{citizens_nearest_npc_id}", alice.player.getUUID()).equals(Integer.toString(beta.getId())), "nearest_uses_reference_native_query_despite_visibility_filter");
                    beta.despawn();
                    check(raw("{citizens_selected_npc_id}", alice.player.getUUID()).equals(Integer.toString(beta.getId())), "despawn_preserves_selection");
                    check(raw("{citizens_nearest_npc_id}", alice.player.getUUID()).isEmpty(), "despawn_and_range_exclude_nearest_candidates");
                    expect(alice, "{citizens_selected_npc_name}", "Beta");
                }
                case 5 -> {
                    check(beta.spawn(new Location(level, 14, -60, 4)), "selected_npc_respawn");
                    CitizensAPI.getDefaultNPCSelector().select(alice.player.createCommandSourceStack(), alpha); alpha.destroy();
                    expect(alice, "<{citizens_selected_npc_name}>|{citizens_selected_npc_id}|{citizens_selected_npc_uuid}", "<>||");
                    ParadigmPlaceholders.install(server); ParadigmPlaceholders.install(server);
                    unrelated = registration("audit", "audit_keep", "untouched");
                    check(status.invoke(unrelated).toString().equals("REGISTERED"), "independent_owner_registered");
                    shutdownHandles = handles();
                    ParadigmPlaceholders.uninstall();
                    for (Object handle : shutdownHandles) check(!Boolean.TRUE.equals(active.invoke(handle)), "our_handle_closed");
                    check(raw("{audit_keep}", null).equals("untouched"), "uninstall_preserves_other_owner");
                    for (String key : KEYS) {
                        check(raw("{" + key + "}", null).equals("{" + key + "}"), "uninstall_removes_key_" + key);
                        Object probe = registration("citizens", key, "probe");
                        check(status.invoke(probe).toString().equals("REGISTERED"), "repeated_install_did_not_leak_reference_" + key); close.invoke(probe);
                    }
                    collision = registration("citizens", "citizens_selected_npc_id", "existing");
                    ParadigmPlaceholders.install(server);
                    check(raw("{citizens_selected_npc_id}", null).equals("existing") && Boolean.TRUE.equals(active.invoke(collision)), "same_owner_existing_resolver_preserved");
                    check(raw("{citizens_selected_npc_name}", null).equals("{citizens_selected_npc_name}"), "partial_registration_rolled_back");
                    close.invoke(collision); collision = null; ParadigmPlaceholders.uninstall(); ParadigmPlaceholders.install(server);
                    check(raw("{citizens_selected_npc_id}", bob.player.getUUID()).equals(Integer.toString(beta.getId())), "registration_recovers_after_collision_removed");
                }
                case 6 -> {
                    expect(alice, "Selected [{citizens_selected_npc_id}] nearest [{citizens_nearest_npc_id}]", "Selected [] nearest [" + beta.getId() + "]");
                    expect(bob, "{citizens_selected_npc_name}", "Beta");
                    check(raw("{audit_keep}", null).equals("untouched"), "reinstall_keeps_unrelated_registration");
                    bob.player.teleportTo(server.getLevel(Level.NETHER), 4, 64, 4, Set.of(), 0, 0);
                }
                case 7 -> {
                    check(raw("{citizens_nearest_npc_id}", bob.player.getUUID()).isEmpty(), "nearest_uses_current_dimension");
                    check(raw("{citizens_selected_npc_id}", bob.player.getUUID()).equals(Integer.toString(beta.getId())), "dimension_change_keeps_selection");
                    check(CompletableFuture.supplyAsync(() -> raw("{citizens_nearest_npc_id}", bob.player.getUUID())).get(2, TimeUnit.SECONDS).isEmpty(), "worker_snapshot_refreshes_dimension_change");
                    shutdownHandles = handles();
                    // Exercise the real provider's API replacement boundary, not a mock implementation or direct
                    // mutation of Citizens registration state. This is narrower than a full mod reload.
                    Object services = Class.forName("eu.avalanche7.paradigm.Paradigm").getMethod("getServices").invoke(null);
                    Object version = Class.forName("eu.avalanche7.paradigm.api.ParadigmAPI").getMethod("modVersion").invoke(null);
                    Object replacement = Class.forName("eu.avalanche7.paradigm.api.internal.ParadigmApiProvider")
                            .getConstructor(Class.forName("eu.avalanche7.paradigm.core.Services"), String.class)
                            .newInstance(services, version);
                    Class.forName("eu.avalanche7.paradigm.api.internal.ApiProviderRegistry")
                            .getMethod("install", Class.forName("eu.avalanche7.paradigm.api.internal.ApiProvider")).invoke(null, replacement);
                    check(raw("{citizens_selected_npc_id}", bob.player.getUUID()).equals("{citizens_selected_npc_id}"), "fresh_provider_has_no_inherited_registrations");
                }
                case 8 -> {
                    Class<?> api = Class.forName("eu.avalanche7.paradigm.api.ParadigmAPI");
                    Object current = api.getMethod("placeholders").invoke(null);
                    check(current != service, "actual_provider_service_replaced");
                    check(raw("{citizens_selected_npc_id}", bob.player.getUUID()).equals(Integer.toString(beta.getId())), "native_tick_registers_with_replacement_provider");
                    for (Object handle : shutdownHandles) check(!Boolean.TRUE.equals(active.invoke(handle)), "old_provider_owned_handle_released");
                    service = current; messageService = api.getMethod("messages").invoke(null);
                    expect(bob, "{citizens_selected_npc_name}", "Beta");
                }
                case 9 -> {
                    shutdownHandles = handles(); done = true;
                }
            }
        } catch (Throwable failure) { done = true; failed = true; LoggerFactory.getLogger("citizens").error("[PARADIGMPLACEHOLDERAUDIT] FAILED phase " + phase, failure); }
        if (done) {
            try {
                if (alpha != null) alpha.destroy(); if (beta != null) beta.destroy();
                for (Actor actor : actors) { server.getPlayerList().remove(actor.player); actor.channel.finishAndReleaseAll(); }
                if (unrelated != null) close.invoke(unrelated); if (collision != null) close.invoke(collision);
            } catch (Throwable failure) { failed = true; LoggerFactory.getLogger("citizens").error("[PARADIGMPLACEHOLDERAUDIT] FAILED cleanup", failure); }
            server.halt(false);
        }
    }

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        if (!done || failed) return;
        try {
            check(installed() == null, "real_server_shutdown_uninstalls_bridge");
            for (Object handle : shutdownHandles) check(!Boolean.TRUE.equals(active.invoke(handle)), "real_shutdown_closes_owned_handle");
            LoggerFactory.getLogger("citizens").info("[PARADIGMPLACEHOLDERAUDIT] COMPLETE {} checks provider={}", passed, provider);
        } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[PARADIGMPLACEHOLDERAUDIT] FAILED shutdown", failure); }
    }
    private static Object installed() throws ReflectiveOperationException {
        var field = ParadigmPlaceholders.class.getDeclaredField("installed"); field.setAccessible(true); return field.get(null);
    }
    private static List<?> handles() throws ReflectiveOperationException {
        Object bridge = installed(); var field = bridge.getClass().getDeclaredField("handles"); field.setAccessible(true); return List.copyOf((List<?>) field.get(bridge));
    }
    private static NPC npc(ServerLevel level, String name, double x) {
        NPC npc = registry.createNPC(EntityType.COW, name); check(npc.spawn(new Location(level, x, -60, 4)), "spawn_" + name);
        ((net.minecraft.world.entity.Mob) npc.getEntity()).setNoAi(true); npc.getEntity().setNoGravity(true); return npc;
    }
    private static String all() { return "{" + String.join("}|{", KEYS) + "}"; }
    private static String raw(String text, UUID player) {
        try { return (String) resolve.invoke(null, text, player); } catch (ReflectiveOperationException failure) { throw new RuntimeException(failure); }
    }
    private static Object registration(String owner, String key, String value) throws ReflectiveOperationException {
        Object resolver = Proxy.newProxyInstance(resolverType.getClassLoader(), new Class<?>[]{resolverType}, (p, m, a) -> value);
        return register.invoke(service, owner, key, resolver);
    }
    private static void expect(Actor actor, String template, String expected) throws ReflectiveOperationException {
        String marker = "Placeholder audit " + ++messageId + ": ";
        check(send.invoke(messageService, actor.player.getUUID(), marker + template, Map.of()).toString().equals("SENT"), "native_message_accepted_" + messageId);
        messages.add(new Expected(actor, marker, expected));
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); passed++; LoggerFactory.getLogger("citizens").info("[PARADIGMPLACEHOLDERAUDIT] PASS {}", label); }
    private static Actor actor(MinecraftServer server, String name, double x) {
        var defaults = ClientInformation.createDefault();
        var information = new ClientInformation(defaults.language(), 6, defaults.chatVisibility(), defaults.chatColors(),
                defaults.modelCustomisation(), defaults.mainHand(), defaults.textFilteringEnabled(), defaults.allowsListing());
        Actor actor = new Actor(); actor.player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), name), information);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        actor.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                connection.configurePacketHandler(channel.pipeline());
                channel.pipeline().addLast("placeholder-capture", new ChannelOutboundHandlerAdapter() {
                    @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                        if (message instanceof Packet<?> packet) actor.capture(packet);
                        super.write(context, message, promise);
                    }
                });
            }
        });
        NetworkRegistry.configureMockConnection(connection);
        var cookie = new CommonListenerCookie(actor.player.getGameProfile(), 0, information, false, ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
        server.getPlayerList().placeNewPlayer(connection, actor.player, cookie);
        actor.player.setPos(x, -60, 4); actors.add(actor); return actor;
    }

    private static final class Actor {
        ServerPlayer player; EmbeddedChannel channel; int pendingBatches;
        final List<Packet<?>> packets = new ArrayList<>();
        final Set<Long> chunks = new HashSet<>();
        final Set<Integer> known = new HashSet<>();
        void capture(Packet<?> packet) {
            if (packet instanceof ClientboundBundlePacket bundle) { bundle.subPackets().forEach(this::capture); return; }
            packets.add(packet);
            if (packet instanceof ClientboundAddEntityPacket add) known.add(add.getId());
            if (packet instanceof ClientboundRemoveEntitiesPacket remove) for (int id : remove.getEntityIds()) known.remove(id);
            if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) chunks.add(ChunkPos.asLong(chunk.getX(), chunk.getZ()));
            if (packet instanceof ClientboundChunkBatchFinishedPacket) pendingBatches++;
        }
        void pump() {
            channel.runPendingTasks();
            while (pendingBatches > 0) { pendingBatches--; player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(16)); }
            Object output; while ((output = channel.readOutbound()) != null) ReferenceCountUtil.release(output);
        }
        boolean watches(ServerLevel level, int x, int z) throws ReflectiveOperationException {
            var method = net.minecraft.server.level.ChunkMap.class.getDeclaredMethod("isChunkTracked", ServerPlayer.class, int.class, int.class);
            method.setAccessible(true);
            return (boolean) method.invoke(level.getChunkSource().chunkMap, player, x, z);
        }
        long spawns(Entity e) { pump(); return packets.stream().filter(p -> p instanceof ClientboundAddEntityPacket add && add.getId() == e.getId()).count(); }
        long removals(Entity e) { pump(); return packets.stream().filter(p -> p instanceof ClientboundRemoveEntitiesPacket remove && remove.getEntityIds().contains(e.getId())).count(); }
        long metadata(Entity e) { pump(); return packets.stream().filter(p -> p instanceof ClientboundSetEntityDataPacket data && data.id() == e.getId()).count(); }
        long equipment(Entity e) { pump(); return packets.stream().filter(p -> p instanceof ClientboundSetEquipmentPacket equipment && equipment.getEntity() == e.getId()).count(); }
        long profiles(NPC n) { pump(); return packets.stream().filter(p -> p instanceof ClientboundPlayerInfoUpdatePacket info && info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER) && info.entries().stream().anyMatch(e -> e.profileId().equals(n.getEntity().getUUID()))).count(); }
        long profileRemovals(NPC n) { pump(); return packets.stream().filter(p -> p instanceof ClientboundPlayerInfoRemovePacket remove && remove.profileIds().contains(n.getEntity().getUUID())).count(); }
        boolean profileBeforeSpawn(NPC n) {
            pump(); boolean profile = false;
            for (Packet<?> packet : packets) {
                if (packet instanceof ClientboundPlayerInfoUpdatePacket info && info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)
                        && info.entries().stream().anyMatch(e -> e.profileId().equals(n.getEntity().getUUID()))) profile = true;
                if (packet instanceof ClientboundAddEntityPacket add && add.getId() == n.getEntity().getId()) return profile;
            }
            return false;
        }
    }
}
