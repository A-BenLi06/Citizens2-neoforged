package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.authlib.GameProfile;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.event.NPCLookCloseChangeTargetEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.RotationTrait;
import net.citizensnpcs.trait.PacketNPC;
import net.citizensnpcs.util.NPCVisibility;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class LookCloseRuntimeAudit {
    private static boolean done;
    private static int phase, deadline;
    private static int passed;
    private static NPC npc;
    private static LookClose look;
    private static ServerPlayer visible, hidden;
    private static Consumer<NPCLookCloseChangeTargetEvent> handler;
    private static final List<ServerPlayer> players = new ArrayList<>();
    private static final List<EmbeddedChannel> channels = new ArrayList<>();
    private static final Map<ServerPlayer, List<WireRotation>> packets = new IdentityHashMap<>();
    private static final Map<ServerPlayer, Integer> pendingBatches = new IdentityHashMap<>();
    private record WireRotation(int id, boolean head, byte yaw, byte pitch) { }

    @SubscribeEvent public static void targetChanged(NPCLookCloseChangeTargetEvent event) {
        if (event.getNPC() == npc && handler != null) handler.accept(event);
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done || event.getServer().getTickCount() < 10) return;
        var server = event.getServer();
        try {
            pump();
            if (phase != 0) {
                if (server.getTickCount() > deadline) throw new AssertionError("Native world tracking timed out");
                if (phase == 1) {
                    NPCVisibility.refresh(npc.getEntity());
                    if (!NPCVisibility.isTracked(npc.getEntity(), visible)) return;
                    check(true, "ordinary_world_viewer_is_natively_paired");
                    var living = (LivingEntity) npc.getEntity();
                    living.setYRot(45); living.setYBodyRot(-30); living.setYHeadRot(90); living.setXRot(-22.5F);
                    look.run(); var rotation = npc.getOrAddTrait(RotationTrait.class);
                    var owned = rotation.getPacketSession(visible);
                    check(owned != null, "ordinary_world_private_session_exists");
                    clearPackets(); look.setEnabled(false); pump();
                    check(!owned.isActive() && packets.get(visible).contains(new WireRotation(living.getId(), false, (byte)32, (byte)-16))
                            && packets.get(visible).contains(new WireRotation(living.getId(), true, (byte)64, (byte)0)),
                            "ordinary_world_disable_restores_native_rotation_packets");
                    phase = 2; deadline = server.getTickCount() + 8;
                    return;
                }
                if (server.getTickCount() < deadline) return;
                check(npc.getOrAddTrait(RotationTrait.class).getPacketSession(visible) == null && target() == null,
                        "disabled_private_session_stays_released_across_real_ticks");
                LoggerFactory.getLogger("citizens").info("[LOOKCLOSEAUDIT] COMPLETE {} checks", passed);
                done = true;
                return;
            }
            check(Files.isRegularFile(Path.of("lookclose-audit-fixture.txt")), "isolated_fixture");
            visible = admit(server, "LookVisible", 0, 4);
            hidden = admit(server, "LookHidden", 2, 0);
            defaults(server);
            npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.COW, "LookAudit");
            look = npc.getOrAddTrait(LookClose.class);
            look.setEnabled(false);
            check(npc.spawn(new Location(server.overworld(), 0, -60, 0)), "native_npc_spawned");
            look.setRange(16);
            look.setRealisticLooking(false);
            hidden.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 1200));
            look.setEnabled(true); look.run();
            check(target() == visible, "nearest_invisible_player_is_not_selected");
            hidden.removeAllEffects(); hidden.setInvisible(false); hidden.setGameMode(GameType.SPECTATOR);
            look.run(); check(target() == visible, "spectator_is_not_selected");
            hidden.setGameMode(GameType.SURVIVAL);
            look.setEntityFilter(entity -> entity == visible);
            look.setEnabled(false); look.setEnabled(true); look.run();
            check(target() == visible, "initial_selection_obeys_entity_filter");
            look.setEntityFilter(entity -> false); look.run();
            check(target() == null, "filter_change_drops_existing_target");
            look.setEntityFilter(null); hidden.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 1200));
            look.setPerPlayer(true); look.run();
            var rotation = npc.getOrAddTrait(RotationTrait.class);
            check(rotation.getPacketSession(visible) != null, "visible_player_has_private_session");
            check(rotation.getPacketSession(hidden) == null, "invisible_player_has_no_private_session");
            look.setEnabled(false); look.run();
            check(rotation.getPacketSession(visible) == null, "disable_releases_private_session");
            look.setEnabled(true); look.run();
            var owned = rotation.getPacketSession(visible);
            var replacement = rotation.createPacketSession(rotation.getGlobalParameters().clone().uuidFilter(visible.getUUID()).persist(true));
            look.setEnabled(false);
            check(!owned.isActive() && rotation.getPacketSession(visible) == replacement && replacement.isActive(),
                    "cleanup_preserves_replacement_owner_session");
            replacement.end();
            look.setEnabled(true); look.run();
            owned = rotation.getPacketSession(visible);
            npc.removeTrait(LookClose.class);
            check(!owned.isActive() && rotation.getPacketSession(visible) == null, "trait_removal_releases_own_session");
            selection(server);
            navigation(server);
            callbacks(server);
            packetCleanup(server);
            npc.destroy();
            npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.COW, "LookWorldAudit");
            fresh(); look.setPerPlayer(true);
            check(npc.spawn(new Location(server.overworld(), 0, -60, 0)), "ordinary_world_probe_spawned");
            phase = 1; deadline = server.getTickCount() + 240;
        } catch (Throwable failure) {
            done = true;
            LoggerFactory.getLogger("citizens").error("[LOOKCLOSEAUDIT] FAILED", failure);
        } finally {
            if (done) {
            handler = null;
            try {
                if (npc != null) npc.destroy();
                for (var player : players) server.getPlayerList().remove(player);
                for (var channel : channels) channel.finishAndReleaseAll();
            } finally { server.halt(false); }
            }
        }
    }

    private static Player target() throws Exception {
        var field = LookClose.class.getDeclaredField("lookingAt"); field.setAccessible(true);
        return (Player) field.get(look);
    }

    private static void fresh() {
        handler = null;
        look = new LookClose(); look.setEnabled(false); npc.addTrait(look);
        look.setRange(16); look.setRealisticLooking(false); look.setDisableWhileNavigating(false); look.setEnabled(true);
    }

    private static void defaults(MinecraftServer server) throws Exception {
        boolean enabled = Setting.DEFAULT_LOOK_CLOSE.asBoolean(), random = Setting.DEFAULT_RANDOM_LOOK_CLOSE.asBoolean();
        boolean navigating = Setting.DISABLE_LOOKCLOSE_WHILE_NAVIGATING.asBoolean();
        NPC created = null;
        try {
            Setting.DEFAULT_LOOK_CLOSE.set(false); Setting.DEFAULT_RANDOM_LOOK_CLOSE.set(true);
            Setting.DISABLE_LOOKCLOSE_WHILE_NAVIGATING.set(true);
            var defaults = new LookClose();
            check(!defaults.isEnabled() && defaults.isRandomLook() && defaults.disableWhileNavigating(), "configured_trait_defaults_are_consumed");
            Setting.DEFAULT_RANDOM_LOOK_CLOSE.set(false);
            created = CitizensAPI.getNPCRegistry().createNPC(EntityType.COW, "LookCommandDefault");
            check(!created.hasTrait(LookClose.class), "disabled_default_does_not_attach_lookclose");
            var source = server.createCommandSourceStack();
            CitizensAPI.getDefaultNPCSelector().select(source, created);
            check(server.getCommands().getDispatcher().execute("npc lookclose", source) == 1
                    && created.getTrait(LookClose.class).isEnabled(), "first_lookclose_command_enables_new_trait");
            check(server.getCommands().getDispatcher().execute("npc lookclose", source) == 1
                    && !created.getTrait(LookClose.class).isEnabled(), "second_lookclose_command_disables_trait");
            Setting.DEFAULT_LOOK_CLOSE.set(true); Setting.DISABLE_LOOKCLOSE_WHILE_NAVIGATING.set(false);
            defaults = new LookClose();
            check(defaults.isEnabled() && !defaults.disableWhileNavigating(), "updated_defaults_apply_to_new_traits");
        } finally {
            if (created != null) created.destroy();
            Setting.DEFAULT_LOOK_CLOSE.set(enabled); Setting.DEFAULT_RANDOM_LOOK_CLOSE.set(random);
            Setting.DISABLE_LOOKCLOSE_WHILE_NAVIGATING.set(navigating);
        }
    }

    private static void resetPlayers() {
        for (ServerPlayer player : players) { player.removeAllEffects(); player.setInvisible(false); player.setGameMode(GameType.SURVIVAL); }
        visible.setPos(0, -60, 4); hidden.setPos(2, -60, 0);
    }

    private static void selection(MinecraftServer server) throws Exception {
        resetPlayers(); fresh();
        hidden.setInvisible(true); visible.setPos(0, -60, 40);
        look.run(); check(target() == null, "native_invisibility_flag_and_range_are_respected");
        hidden.setInvisible(false); look.setEntityFilter(entity -> entity == hidden);
        var level = server.overworld();
        for (int y = -60; y <= -57; y++) for (int z = -1; z <= 1; z++) level.setBlockAndUpdate(new BlockPos(1, y, z), Blocks.STONE.defaultBlockState());
        try {
            check(!((LivingEntity) npc.getEntity()).hasLineOfSight(hidden), "native_line_of_sight_fixture_is_occluded");
            look.setRealisticLooking(true); look.run(); check(target() == null, "realistic_mode_rejects_occluded_player");
            look.setRealisticLooking(false); look.run(); check(target() == hidden, "nonrealistic_mode_can_select_occluded_player");
        } finally {
            for (int y = -60; y <= -57; y++) for (int z = -1; z <= 1; z++) level.setBlockAndUpdate(new BlockPos(1, y, z), Blocks.AIR.defaultBlockState());
        }
        fresh(); resetPlayers();
        look.setEntityFilter(entity -> { entity.setInvisible(true); return true; });
        look.run(); check(target() == null, "filter_side_effects_are_revalidated_before_selection");
        resetPlayers(); fresh(); look.setEntityFilter(entity -> entity == visible);
        look.run(); var rotation = npc.getOrAddTrait(RotationTrait.class);
        check(rotation.getPhysicalSession().isActive(), "selected_target_starts_physical_rotation");
        look.setEnabled(false); check(!rotation.getPhysicalSession().isActive(), "disable_cancels_own_physical_rotation");
        rotation.getGlobalParameters().persist(true); look.setEnabled(true); look.run(); look.setEnabled(false);
        check(!rotation.getPhysicalSession().isActive(), "owned_physical_cancel_respects_persistent_parameter_without_changing_it");
        rotation.getGlobalParameters().persist(false);
        look.setEnabled(true); look.run(); rotation.getPhysicalSession().rotateToHave(77, 12);
        look.setEnabled(false);
        check(rotation.getPhysicalSession().isActive() && rotation.getPhysicalSession().getTargetYaw() == 77,
                "disable_preserves_later_physical_rotation_owner");
        for (double invalid : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            try { look.setRange(invalid); throw new AssertionError("Accepted invalid range"); }
            catch (IllegalArgumentException expected) { check(look.getRange() == 16, "invalid_range_is_atomic"); }
        }
    }

    private static void navigation(MinecraftServer server) throws Exception {
        resetPlayers(); fresh(); look.setEntityFilter(entity -> entity == visible);
        var nav = npc.getNavigator(); var destination = new Location(server.overworld(), 10, -60, 0);
        nav.setPaused(false); nav.setTarget(destination); look.run();
        check(nav.isPaused(), "physical_looking_pauses_current_navigation");
        look.setEnabled(false); check(!nav.isPaused(), "disabling_look_releases_own_navigation_pause");
        look.setEnabled(true); look.run(); nav.setPaused(true); look.setEnabled(false);
        check(nav.isPaused(), "later_same_value_pause_request_is_preserved");
        nav.setPaused(false); nav.setTarget(destination); nav.setPaused(true); look.setEnabled(true); look.run(); look.setEnabled(false);
        check(nav.isPaused(), "preexisting_external_pause_is_preserved");
        nav.setPaused(false); look.setEnabled(true); look.run(); nav.setTarget(new Location(server.overworld(), 8, -60, 0)); look.setEnabled(false);
        check(!nav.isPaused(), "route_replacement_does_not_inherit_retired_look_pause");
        look.setEnabled(true); look.setDisableWhileNavigating(true); look.run();
        check(target() == null && !nav.isPaused(), "disable_while_navigating_releases_target_and_pause");
        look.setDisableWhileNavigating(false); look.setPerPlayer(true); look.run();
        check(!nav.isPaused(), "private_looking_does_not_pause_navigation");
        nav.cancelNavigation(); nav.setPaused(false);
    }

    private static void callbacks(MinecraftServer server) throws Exception {
        resetPlayers(); fresh();
        handler = event -> event.setNewTarget(visible);
        look.run(); check(target() == visible, "event_can_redirect_to_another_eligible_player");
        fresh(); handler = event -> event.setNewTarget(null);
        look.run(); check(target() == null, "null_event_redirect_suppresses_selection");
        fresh(); hidden.setInvisible(true); handler = event -> event.setNewTarget(hidden);
        look.run(); check(target() == visible, "invalid_event_redirect_retains_valid_proposal");
        fresh(); int[] calls = {0}; handler = event -> { calls[0]++; look.run(); look.findNewTarget(); };
        look.run(); check(target() == visible && calls[0] == 1, "recursive_selection_does_not_reenter_dispatch");
        fresh(); handler = event -> look.setEnabled(false);
        look.run(); check(target() == null && !look.isEnabled(), "callback_disable_cannot_be_overwritten");
        fresh(); handler = event -> look.setPerPlayer(true);
        look.run(); check(target() == null && look.isPerPlayer(), "callback_mode_change_cannot_restore_physical_target");
        fresh(); var old = look;
        handler = event -> { handler = null; fresh(); };
        old.run(); check(look != old && target() == null, "callback_trait_replacement_retires_old_selection");
        fresh(); var originalEntity = npc.getEntity();
        handler = event -> { handler = null; npc.despawn(); npc.spawn(new Location(server.overworld(), 0, -60, 0)); };
        look.run(); check(npc.getEntity() != originalEntity && target() == null, "callback_respawn_cannot_commit_old_selection");
        handler = null; look.run(); check(target() == visible, "new_entity_can_acquire_target_after_callback_respawn");
        var disconnected = hidden;
        server.getPlayerList().remove(disconnected); players.remove(disconnected);
        fresh(); handler = event -> event.setNewTarget(disconnected); look.run();
        check(target() == visible, "disconnected_player_cannot_be_reintroduced_by_event");
        handler = null;
    }

    private static void packetCleanup(MinecraftServer server) throws Exception {
        npc.destroy();
        npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.COW, "LookPacketAudit");
        var virtual = npc.getOrAddTrait(PacketNPC.class);
        fresh(); look.setPerPlayer(true);
        check(npc.spawn(new Location(server.overworld(), 0, -60, 0)), "virtual_npc_spawned");
        virtual.run(); pump(); check(NPCVisibility.isTracked(npc.getEntity(), visible), "reset_viewer_is_natively_paired");
        var living = (LivingEntity) npc.getEntity();
        living.setYRot(45); living.setYBodyRot(-30); living.setYHeadRot(90); living.setXRot(-22.5F);
        look.run(); var rotation = npc.getOrAddTrait(RotationTrait.class); var owned = rotation.getPacketSession(visible);
        check(owned != null, "paired_private_session_exists");
        clearPackets(); visible.setInvisible(true); look.run(); pump();
        check(!owned.isActive(), "becoming_invisible_releases_private_session");
        check(packets.get(visible).contains(new WireRotation(living.getId(), false, (byte)32, (byte)-16))
                && packets.get(visible).contains(new WireRotation(living.getId(), true, (byte)64, (byte)0)),
                "native_packet_reset_restores_exact_body_head_and_pitch_even_when_viewer_invisible");
        visible.setInvisible(false); look.run(); owned = rotation.getPacketSession(visible);
        look.setHeadOnly(true); check(!owned.isActive(), "live_head_policy_releases_old_private_parameters");
        look.run(); owned = rotation.getPacketSession(visible); look.setLinkedBody(true);
        check(!owned.isActive(), "live_body_policy_releases_old_private_parameters");
        look.run(); owned = rotation.getPacketSession(visible); look.setPerPlayer(false);
        check(!owned.isActive() && rotation.getPacketSession(visible) == null, "leaving_private_mode_releases_session");
        look.setPerPlayer(true); look.run(); owned = rotation.getPacketSession(visible);
        var external = rotation.createPacketSession(rotation.getGlobalParameters().clone().uuidFilter(visible.getUUID()).persist(true));
        clearPackets(); look.setEnabled(false); pump();
        check(external.isActive() && rotation.getPacketSession(visible) == external && packets.get(visible).isEmpty(),
                "releasing_old_owner_sends_no_reset_over_replacement_session");
        external.end(); look.setEnabled(true); look.run(); owned = rotation.getPacketSession(visible);
        npc.despawn(); check(!owned.isActive(), "despawn_ends_private_session");
        check(npc.spawn(new Location(server.overworld(), 0, -60, 0)), "virtual_npc_respawned");
        virtual.run(); look.run(); check(rotation.getPacketSession(visible) != null && rotation.getPacketSession(visible) != owned,
                "respawn_uses_fresh_private_session");
        owned = rotation.getPacketSession(visible); npc.removeTrait(LookClose.class);
        check(!owned.isActive(), "virtual_trait_removal_releases_private_session");
    }

    private static void capture(ServerPlayer player, Packet<?> packet) {
        if (packet instanceof ClientboundBundlePacket bundle) { bundle.subPackets().forEach(part -> capture(player, part)); return; }
        if (packet instanceof ClientboundChunkBatchFinishedPacket) pendingBatches.merge(player, 1, Integer::sum);
        if (!(packet instanceof ClientboundMoveEntityPacket.Rot) && !(packet instanceof ClientboundRotateHeadPacket)) return;
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            if (packet instanceof ClientboundMoveEntityPacket.Rot rotation) {
                ClientboundMoveEntityPacket.Rot.STREAM_CODEC.encode(buffer, rotation);
                packets.get(player).add(new WireRotation(buffer.readVarInt(), false, buffer.readByte(), buffer.readByte()));
            } else if (packet instanceof ClientboundRotateHeadPacket head) {
                ClientboundRotateHeadPacket.STREAM_CODEC.encode(buffer, head);
                packets.get(player).add(new WireRotation(buffer.readVarInt(), true, buffer.readByte(), (byte)0));
            }
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
                channel.pipeline().addLast("look-capture", new ChannelOutboundHandlerAdapter() {
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
        passed++; LoggerFactory.getLogger("citizens").info("[LOOKCLOSEAUDIT] PASS {}", label);
    }
}
