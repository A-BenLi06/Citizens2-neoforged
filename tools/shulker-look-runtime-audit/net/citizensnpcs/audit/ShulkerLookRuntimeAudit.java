package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.DisguiseTrait;
import net.citizensnpcs.trait.versioned.ShulkerTrait;
import net.citizensnpcs.trait.PacketNPC;
import net.citizensnpcs.util.NPCVisibility;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.neoforge.event.VanillaGameEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class ShulkerLookRuntimeAudit {
    private static boolean done;
    private static int passed;
    private static NPC npc;
    private static LookClose look;
    private static Shulker shulker;
    private static int phase, deadline, events;
    private static Runnable callback;
    private static ServerPlayer viewer;
    private static final List<ServerPlayer> players = new ArrayList<>();
    private static final List<EmbeddedChannel> channels = new ArrayList<>();
    private static final Map<ServerPlayer, List<Packet<?>>> packets = new IdentityHashMap<>();
    private static final Map<ServerPlayer, Integer> pendingBatches = new IdentityHashMap<>();

    @SubscribeEvent public static void gameEvent(VanillaGameEvent event) {
        if (event.getCause() != shulker || !(event.getVanillaEvent().equals(GameEvent.CONTAINER_OPEN)
                || event.getVanillaEvent().equals(GameEvent.CONTAINER_CLOSE))) return;
        events++;
        Runnable action = callback;
        callback = null;
        if (action != null) action.run();
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done || event.getServer().getTickCount() < 10) return;
        var server = event.getServer();
        try {
            pump();
            if (phase == 1) {
                if (server.getTickCount() < deadline) return;
                check(shulker.tickCount == 0, "virtual_entity_remains_unticked");
                check(peek() == 96, "virtual_traits_keep_distance_response");
                check(wirePeek(viewer) == 96, "virtual_native_metadata_updates");
                var late = admit(server, "ShulkerLate", 2.5, 0.5);
                npc.getTrait(PacketNPC.class).run(); pump();
                check(wirePeek(late) == 96, "virtual_late_pairing_has_current_peek");
                clearPackets(); look.setEnabled(false);
                npc.getTrait(PacketNPC.class).run(); pump();
                check(peek() == 30 && wirePeek(viewer) == 30 && wirePeek(late) == 30,
                        "virtual_release_metadata_reaches_all_viewers");
                late.setPos(50, -60, 50);
                npc.destroy(); create(server, false); configure(0); near();
                clearPackets(); look.setEnabled(true); look.run();
                phase = 2; deadline = server.getTickCount() + 240;
                return;
            }
            if (phase == 2) {
                if (server.getTickCount() > deadline) throw new AssertionError("World pairing timed out");
                NPCVisibility.refresh(shulker);
                if (!NPCVisibility.isTracked(shulker, viewer)) return;
                check(wirePeek(viewer) == 96, "world_native_pairing_has_peek");
                check(shulker.tickCount > 0 && shulker.getClientPeekAmount(1) > 0,
                        "world_native_tick_advances_animation");
                clearPackets(); look.setEnabled(false);
                phase = 3; deadline = server.getTickCount() + 20;
                return;
            }
            if (phase == 3) {
                if (server.getTickCount() < deadline) return;
                check(wirePeek(viewer) == 0, "world_native_metadata_restores_closed_baseline");
                check(peek() == 0 && shulker.getClientPeekAmount(1) == 0, "world_native_animation_closes");
                done = true;
                LoggerFactory.getLogger("citizens").info("[SHULKERLOOKAUDIT] COMPLETE {} checks", passed);
                return;
            }
            check(Files.isRegularFile(Path.of("shulker-look-audit-fixture.txt")), "isolated_fixture");
            viewer = admit(server, "ShulkerViewer", 0.5, 1.5);
            create(server, false);
            viewer.setPos(0.5, -60, 6.5); look.setRange(20); look.setEnabled(true); look.run();
            check(peek() == 0 && shulker.getAttributeValue(Attributes.ARMOR) == 20,
                    "first_closed_response_initializes_native_covered_armor");
            look.setEnabled(false); near();
            shulker.setSilent(false); shulker.setRawPeekAmount(0);
            clearPackets(); events = 0;
            look.setEnabled(true); look.run();
            check(peek() == 96, "nearby_target_opens_shulker_by_distance");
            check(events == 1 && shulker.getAttributeValue(Attributes.ARMOR) == 0, "native_open_event_and_armor");
            check(!shulker.isSilent() && sounds() == 0, "quiet_open_preserves_silent_state");
            for (int i = 0; i < 30; i++) look.run();
            check(events == 1 && sounds() == 0, "unchanged_distance_does_not_repeat_native_side_effects");
            viewer.setPos(0.5, -60, 1.9); look.run();
            check(peek() == 96 && events == 1, "distance_formula_floors_squared_distance");
            viewer.setPos(0.5, -60, 2.5); look.run(); check(peek() == 84, "two_block_distance_opens_84");
            viewer.setPos(0.5, -60, 0.5); look.run(); check(peek() == 100, "zero_distance_opens_fully");
            look.setRange(Double.MAX_VALUE);
            viewer.setPos(0.5, -60, 5.5); look.run();
            check(peek() == 0 && shulker.getAttributeValue(Attributes.ARMOR) == 20, "five_block_distance_closes_with_native_armor");
            viewer.setPos(0.5, -60, 6.5); look.run(); check(peek() == 0, "long_distance_clamps_before_byte_write");
            viewer.setPos(0.5, -60, 1.0e100); look.run(); check(peek() == 0, "huge_finite_distance_does_not_overflow");
            look.setEnabled(false); near(); look.setRange(20);
            lifecycle(server);
            configured();
            callbacks(server);
            nativeIsolation(server);
            disguiseBoundary(server);
            npc.destroy(); create(server, true); configure(30); near();
            npc.getTrait(PacketNPC.class).run(); clearPackets(); look.setEnabled(true); look.run();
            phase = 1; deadline = server.getTickCount() + 8;
        } catch (Throwable failure) {
            done = true;
            LoggerFactory.getLogger("citizens").error("[SHULKERLOOKAUDIT] FAILED", failure);
        } finally {
            if (done) try {
                callback = null;
                if (npc != null) npc.destroy();
                for (var player : players) server.getPlayerList().remove(player);
                for (var channel : channels) channel.finishAndReleaseAll();
            } finally { server.halt(false); }
        }
    }

    private static void create(MinecraftServer server, boolean virtual) {
        npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.SHULKER, "ShulkerLook");
        look = npc.getOrAddTrait(LookClose.class); look.setEnabled(false); look.setRandomLook(false); look.setRealisticLooking(false);
        if (virtual) npc.getOrAddTrait(PacketNPC.class);
        check(npc.spawn(new Location(server.overworld(), 0.5, -60, 0.5)), virtual ? "virtual_spawned" : "world_spawned");
        shulker = (Shulker) npc.getEntity();
        shulker.setSilent(false);
    }

    private static void near() { viewer.setPos(shulker.getX(), shulker.getY(), shulker.getZ() + 1); }
    private static int peek() { return peek(shulker); }
    private static int peek(Shulker entity) {
        CompoundTag tag = new CompoundTag(); entity.addAdditionalSaveData(tag); return tag.getByte("Peek");
    }
    private static void start() { near(); look.setEnabled(true); look.run(); }

    private static void lifecycle(MinecraftServer server) {
        shulker.setRawPeekAmount(23); start(); look.setEnabled(false);
        check(peek() == 23, "disable_restores_unconfigured_baseline");
        start(); viewer.setPos(30, -60, 30); look.run();
        check(look.getTarget() == null && peek() == 23, "target_loss_restores_baseline");
        start(); look.setPerPlayer(true);
        check(peek() == 23, "private_mode_releases_physical_peek");
        look.run(); check(peek() == 23, "private_mode_does_not_control_global_peek");
        look.setPerPlayer(false); start();
        shulker.setRawPeekAmount(62); look.setEnabled(false);
        check(peek() == 62, "later_native_request_survives_release");
        start(); shulker.setRawPeekAmount(96); look.setEnabled(false);
        check(peek() == 96, "later_same_value_native_request_survives_release");
        shulker.setRawPeekAmount(23); start(); shulker.setRawPeekAmount(62); look.run(); look.setEnabled(false);
        check(peek() == 62, "continued_looking_captures_newer_native_baseline");
        start(); CompoundTag tag = new CompoundTag(); shulker.addAdditionalSaveData(tag); tag.putByte("Peek", (byte) 58);
        shulker.readAdditionalSaveData(tag); look.setEnabled(false);
        check(peek() == 58, "direct_native_metadata_change_survives_release");
        start(); npc.removeTrait(LookClose.class);
        check(peek() == 58, "trait_removal_restores_baseline");
        look = npc.getOrAddTrait(LookClose.class); look.setRandomLook(false); start();
        Shulker old = shulker;
        npc.despawn(); check(peek(old) == 58, "despawn_releases_old_entity");
        check(npc.spawn(new Location(server.overworld(), 0.5, -60, 0.5)), "respawn_succeeds");
        shulker = (Shulker) npc.getEntity(); shulker.setRawPeekAmount(17); start(); look.setEnabled(false);
        check(peek() == 17 && peek(old) == 58, "respawn_owns_only_new_entity_baseline");
    }

    private static ShulkerTrait configure(int amount) {
        var trait = npc.getOrAddTrait(ShulkerTrait.class); trait.setPeek(amount); trait.run(); return trait;
    }

    private static void configured() {
        var trait = configure(20); start(); trait.run();
        check(peek() == 96 && trait.getPeek() == 20, "configured_trait_does_not_fight_lookclose");
        trait.setPeek(40); trait.run();
        check(peek() == 96 && trait.getPeek() == 40, "explicit_configuration_updates_temporary_baseline");
        look.setEnabled(false); trait.run(); check(peek() == 40, "release_restores_latest_configuration");
        start(); shulker.setRawPeekAmount(63); trait.setPeek(42); look.setEnabled(false);
        check(peek() == 42, "newer_configuration_supersedes_earlier_native_request");
        start(); trait.setPeek(55); look.setEnabled(false);
        check(peek() == 55, "configuration_restores_before_another_trait_tick");
        var key = new MemoryDataKey(); PersistenceLoader.save(trait, key);
        check(key.getInt("peek") == 55, "temporary_response_does_not_leak_into_saved_configuration");
        try { trait.setPeek(101); throw new AssertionError("invalid peek accepted"); }
        catch (IllegalArgumentException expected) { check(trait.getPeek() == 55, "invalid_api_peek_is_atomic"); }
        trait.setPeek(0); trait.run(); viewer.setPos(0.5, -60, 6.5); look.setRange(20); look.setEnabled(true); look.run();
        check(peek() == 0, "already_closed_response");
        trait.setPeek(44); look.setEnabled(false);
        check(peek() == 44, "configuration_restores_when_response_never_changed_native_amount");
        near(); start(); trait.setPeek(44); trait.run(); look.setEnabled(false);
        check(peek() == 44, "repeated_configured_value_is_retained");
        // First LookClose tick may precede the first ShulkerTrait tick after respawn.
        npc.despawn(); npc.spawn(new Location(viewer.serverLevel(), 0.5, -60, 0.5)); shulker = (Shulker) npc.getEntity();
        start(); trait.run(); check(peek() == 96, "lookclose_first_trait_order");
        look.setEnabled(false); check(peek() == 44, "lookclose_first_order_restores_configured_baseline");
    }

    private static void callbacks(MinecraftServer server) {
        configure(0); shulker.setSilent(false); callback = () -> look.setEnabled(false); start();
        check(!look.isEnabled() && peek() == 0, "disable_in_native_open_event_retires_pending_write");
        check(shulker.getAttributeValue(Attributes.ARMOR) == 20, "callback_cleanup_restores_covered_armor");
        callback = () -> { check(!shulker.isSilent(), "event_observes_original_silent_flag"); shulker.setSilent(true); };
        start(); check(peek() == 96 && shulker.isSilent(), "callback_silent_change_survives_quiet_request");
        look.setEnabled(false); shulker.setSilent(false);
        callback = () -> shulker.setRawPeekAmount(37); clearPackets(); start();
        check(peek() == 37 && sounds() == 1, "nested_native_request_supersedes_quiet_write_and_keeps_sound");
        look.setEnabled(false); check(peek() == 37, "nested_native_request_survives_cleanup");
        configure(0); callback = () -> { npc.getTrait(ShulkerTrait.class).setPeek(41); look.setEnabled(false); };
        start(); check(peek() == 41, "callback_configuration_used_for_pending_release");
        configure(0); callback = () -> look.run(); int before = events; start();
        check(peek() == 96 && events == before + 1, "reentrant_run_does_not_repeat_pending_peek");
        look.setEnabled(false); configure(0);
        Shulker old = shulker; callback = () -> npc.despawn(); start();
        check(!npc.isSpawned() && peek(old) == 0, "despawn_in_native_event_retires_pending_write");
        check(npc.spawn(new Location(server.overworld(), 0.5, -60, 0.5)), "callback_respawn_succeeds");
        shulker = (Shulker) npc.getEntity(); look.setEnabled(false);
    }

    private static void nativeIsolation(MinecraftServer server) {
        var ordinary = EntityType.SHULKER.create(server.overworld()); ordinary.setPos(0.5, -60, 0.5);
        clearPackets(); ordinary.setRawPeekAmount(24);
        check(peek(ordinary) == 24 && sounds() == 1, "ordinary_native_request_keeps_sound");
        ordinary.setRawPeekAmount(0);
        check(peek(ordinary) == 0 && ordinary.getAttributeValue(Attributes.ARMOR) == 20 && sounds() == 2,
                "ordinary_native_close_keeps_sound_and_armor");
        configure(32); shulker.setSilent(true); start(); look.setEnabled(false);
        check(shulker.isSilent() && peek() == 32, "preexisting_silent_state_survives_full_lifecycle");
    }

    private static void disguiseBoundary(MinecraftServer server) {
        NPC other = CitizensAPI.getNPCRegistry().createNPC(EntityType.COW, "CosmeticShulker");
        try {
            var otherLook = other.getOrAddTrait(LookClose.class); otherLook.setEnabled(false); otherLook.setRandomLook(false);
            other.getOrAddTrait(DisguiseTrait.class).disguiseAsType(EntityType.SHULKER);
            other.spawn(new Location(server.overworld(), 0.5, -60, 0.5));
            var configured = other.getOrAddTrait(ShulkerTrait.class); configured.setPeek(29); configured.run();
            near(); otherLook.setEnabled(true); otherLook.run();
            check(other.getEntity().getType() == EntityType.COW && otherLook.getTarget() == viewer
                    && peek((Shulker) other.getCosmeticEntity()) == 29, "cosmetic_shulker_does_not_gain_physical_response");
        } finally { other.destroy(); }
    }

    private static long sounds() {
        pump();
        return packets.get(viewer).stream().filter(packet -> packet instanceof ClientboundSoundPacket sound
                && (sound.getSound().value() == SoundEvents.SHULKER_OPEN || sound.getSound().value() == SoundEvents.SHULKER_CLOSE)).count();
    }

    private static int wirePeek(ServerPlayer player) {
        var replica = EntityType.SHULKER.create(player.serverLevel()); boolean found = false;
        for (var packet : packets.get(player)) {
            if (packet instanceof ClientboundSetEntityDataPacket data && data.id() == shulker.getId()) {
                replica.getEntityData().assignValues(data.packedItems()); found = true;
            }
        }
        return found ? peek(replica) : -1;
    }

    private static void capture(ServerPlayer player, Packet<?> packet) {
        if (packet instanceof ClientboundBundlePacket bundle) { bundle.subPackets().forEach(part -> capture(player, part)); return; }
        if (packet instanceof ClientboundChunkBatchFinishedPacket) pendingBatches.merge(player, 1, Integer::sum);
        if (packet instanceof ClientboundSoundPacket) { packets.get(player).add(packet); return; }
        if (!(packet instanceof ClientboundSetEntityDataPacket metadata)) return;
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
        try {
            ClientboundSetEntityDataPacket.STREAM_CODEC.encode(buffer, metadata);
            packets.get(player).add(ClientboundSetEntityDataPacket.STREAM_CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
    private static void pump() {
        for (var channel : channels) channel.runPendingTasks();
        for (var player : players) {
            int count = pendingBatches.getOrDefault(player, 0); pendingBatches.put(player, 0);
            while (count-- > 0) player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(16));
        }
        for (var channel : channels) { Object value; while ((value = channel.readOutbound()) != null) ReferenceCountUtil.release(value); }
    }
    private static void clearPackets() { pump(); packets.values().forEach(List::clear); }

    private static ServerPlayer admit(MinecraftServer server, String name, double x, double z) {
        var player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault());
        packets.put(player, new ArrayList<>());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                connection.configurePacketHandler(channel.pipeline());
                channel.pipeline().addLast("shulker-look-capture", new ChannelOutboundHandlerAdapter() {
                    @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) throws Exception {
                        if (message instanceof Packet<?> packet) capture(player, packet);
                        super.write(ctx, message, promise);
                    }
                });
            }
        });
        NetworkRegistry.configureMockConnection(connection);
        var cookie = new CommonListenerCookie(player.getGameProfile(), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setPos(x, -60, z);
        channels.add(channel); players.add(player);
        return player;
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++; LoggerFactory.getLogger("citizens").info("[SHULKERLOOKAUDIT] PASS {}", label);
    }
}
