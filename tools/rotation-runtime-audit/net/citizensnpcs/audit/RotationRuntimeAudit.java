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
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.RotationTrait;
import net.citizensnpcs.trait.PacketNPC;
import net.citizensnpcs.util.NPCVisibility;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class RotationRuntimeAudit {
    private static boolean done;
    private static int phase, deadline, passed;
    private static NPC npc;
    private static RotationTrait rotation;
    private static ServerPlayer alice, bob;
    private static final List<ServerPlayer> players = new ArrayList<>();
    private static final List<EmbeddedChannel> channels = new ArrayList<>();
    private static final Map<ServerPlayer, List<WireRotation>> packets = new IdentityHashMap<>();
    private static final Map<ServerPlayer, List<Packet<?>>> raw = new IdentityHashMap<>();
    private static final Map<ServerPlayer, Integer> pendingBatches = new IdentityHashMap<>();
    private record WireRotation(int id, boolean head, byte yaw, byte pitch) { }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done || event.getServer().getTickCount() < 10) return;
        var server = event.getServer();
        try {
            pump();
            if (phase != 0) {
                if (server.getTickCount() > deadline) throw new AssertionError("World pairing timed out");
                NPCVisibility.refresh(npc.getEntity());
                if (!NPCVisibility.isTracked(npc.getEntity(), alice) || !NPCVisibility.isTracked(npc.getEntity(), bob)) return;
                if (phase == 1) {
                    check(true, "world_viewers_natively_paired");
                    expect(alice, 64, -16, "world_initial_pairing_uses_private_angles");
                    transport(false);
                    NPCVisibility.refreshPairing(npc.getEntity()); pump();
                    check(raw.get(alice).stream().anyMatch(packet -> packet instanceof ClientboundAddEntityPacket spawn
                            && spawn.getId() == npc.getEntity().getId() && spawn.getYRot() == 90),
                            "world_repairing_preserves_private_angles");
                    clearPackets(); phase = 2; deadline = server.getTickCount() + 16;
                    return;
                }
                npc.getEntity().setYRot(server.getTickCount() % 2 == 0 ? -45 : 45);
                npc.getEntity().setYHeadRot(-135); npc.getEntity().setXRot(45);
                if (server.getTickCount() < deadline - 5) return;
                check(packets.get(alice).stream().anyMatch(packet -> packet.id() == npc.getEntity().getId())
                        && packets.get(alice).stream().filter(packet -> packet.id() == npc.getEntity().getId())
                        .allMatch(packet -> packet.yaw() == 64 && (packet.head() || packet.pitch() == -16)),
                        "world_real_ticks_never_leak_physical_angles");
                done = true;
                LoggerFactory.getLogger("citizens").info("[ROTATIONAUDIT] COMPLETE {} checks", passed);
                return;
            }
            check(Files.isRegularFile(Path.of("rotation-audit-fixture.txt")), "isolated_fixture");
            alice = admit(server, "RotationAlice", 0, 4);
            bob = admit(server, "RotationBob", 2, 0);
            npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.COW, "RotationAudit");
            npc.getOrAddTrait(LookClose.class).setEnabled(false);
            var virtual = npc.getOrAddTrait(PacketNPC.class);
            rotation = npc.getOrAddTrait(RotationTrait.class);
            check(npc.spawn(new Location(server.overworld(), 0, -60, 0)), "virtual_npc_spawned");
            virtual.run(); pump();
            check(NPCVisibility.isTracked(npc.getEntity(), alice), "viewer_is_paired");
            var entity = (LivingEntity) npc.getEntity();
            entity.setYRot(90); entity.setYBodyRot(90); entity.setYHeadRot(90); entity.setXRot(45);
            session(0, 0, alice);
            clearPackets(); rotation.run(); pump();
            expect(alice, 0, 0, "initial_zero_angles_are_sent");
            ownership();
            callbackAndHeadPolicy();
            transport(true);
            lifecycle(server);
            playerCommand(server);
            npc.destroy();
            npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.COW, "RotationWorld");
            npc.getOrAddTrait(LookClose.class).setEnabled(false);
            rotation = npc.getOrAddTrait(RotationTrait.class);
            session(90, -22.5F, alice);
            clearPackets();
            check(npc.spawn(new Location(server.overworld(), 0, -60, 0)), "world_npc_spawned");
            rotation.run(); phase = 1; deadline = server.getTickCount() + 240;
        } catch (Throwable failure) {
            done = true;
            LoggerFactory.getLogger("citizens").error("[ROTATIONAUDIT] FAILED", failure);
        } finally {
            if (done) {
                try {
                    if (npc != null) npc.destroy();
                    for (var player : players) server.getPlayerList().remove(player);
                    for (var channel : channels) channel.finishAndReleaseAll();
                } finally { server.halt(false); }
            }
        }
    }

    private static RotationTrait.PacketRotationSession session(float yaw, float pitch, ServerPlayer... viewers) {
        var params = rotation.getGlobalParameters().clone().persist(true).immediate(true).linkedBody(true)
                .uuidFilter(java.util.Arrays.stream(viewers).map(ServerPlayer::getUUID).toList());
        var result = rotation.createPacketSession(params);
        result.getSession().rotateToHave(yaw, pitch);
        return result;
    }

    private static void ownership() {
        clearPackets(); rotation.run(); pump();
        check(packets.get(alice).isEmpty(), "stable_quantised_angles_do_not_resend");
        rotation.clearPacketSessions();
        var first = rotation.createPacketSession(rotation.getGlobalParameters().clone().filter(viewer -> true)
                .persist(true).immediate(true).linkedBody(true));
        first.getSession().rotateToHave(-90, 22.5F);
        var second = rotation.createPacketSession(rotation.getGlobalParameters().clone().filter(viewer -> true)
                .persist(true).immediate(true).linkedBody(true));
        second.getSession().rotateToHave(45, -45);
        clearPackets(); rotation.run(); pump();
        expect(alice, -64, 16, "first_matching_general_session_wins");
        check(packets.get(alice).size() == 2, "overlapping_sessions_send_one_angle_pair");
        var shared = session(90, -22.5F, alice, bob);
        clearPackets(); rotation.run(); pump();
        expect(alice, 64, -16, "uuid_session_precedes_general_session");
        var replacement = session(135, 45, alice);
        check(shared.isActive() && rotation.getPacketSession(bob) == shared, "uuid_replacement_preserves_other_owner_keys");
        clearPackets(); rotation.run(); pump();
        expect(alice, 96, 32, "replacement_delivers_to_its_viewer");
        clearPackets(); rotation.resetPlayerToPhysicalSession(alice.getUUID()); pump();
        expect(alice, -64, 16, "uuid_reset_restores_running_general_fallback");
        check(!replacement.isActive() && shared.isActive(), "reset_retires_only_unreferenced_session");
        clearPackets(); first.end(); pump();
        expect(alice, 32, -32, "ending_general_owner_restores_running_next_session");
        check(rotation.getPacketSession(bob) == shared, "ending_general_owner_does_not_reset_uuid_owner");
        clearPackets(); rotation.run(); pump();
        check(packets.get(alice).isEmpty(), "ended_session_does_not_execute_again");
        alice.setInvisible(true); alice.setGameMode(GameType.SPECTATOR);
        second.getSession().rotateToHave(180, 0); clearPackets(); rotation.run(); pump();
        expect(alice, -128, 0, "invisible_spectator_viewer_still_receives_private_angles");
        alice.setInvisible(false); alice.setGameMode(GameType.SURVIVAL);
        var entity = (LivingEntity) npc.getEntity();
        entity.setYRot(45); entity.setYBodyRot(-90); entity.setYHeadRot(90); entity.setXRot(-22.5F);
        clearPackets(); rotation.clearPacketSessions(); pump();
        check(packets.get(alice).contains(new WireRotation(entity.getId(), false, (byte)32, (byte)-16))
                && packets.get(alice).contains(new WireRotation(entity.getId(), true, (byte)64, (byte)0)),
                "clear_restores_entity_yaw_instead_of_body_animation_yaw");
        check(!first.isActive() && !second.isActive() && !shared.isActive(), "clear_ends_all_owners");
        var finite = rotation.createPacketSession(rotation.getGlobalParameters().clone().uuidFilter(alice.getUUID())
                .immediate(true).linkedBody(true).persist(false));
        finite.getSession().rotateToHave(-90, 0); clearPackets(); rotation.run(); pump();
        expect(alice, -64, 0, "finite_session_delivers_final_frame");
        clearPackets(); rotation.run(); pump();
        check(!finite.isActive() && packets.get(alice).contains(new WireRotation(entity.getId(), false, (byte)32, (byte)-16)),
                "finite_session_restores_physical_on_following_tick");
        boolean[] accept = {true};
        var changing = rotation.createPacketSession(rotation.getGlobalParameters().clone().filter(viewer -> accept[0])
                .persist(true).immediate(true));
        changing.getSession().rotateToHave(90, 0); rotation.run();
        accept[0] = false; clearPackets(); rotation.run(); pump();
        check(packets.get(alice).contains(new WireRotation(entity.getId(), false, (byte)32, (byte)-16)),
                "filter_rejection_restores_previous_viewer");
        rotation.clearPacketSessions();
        var stable = session(90, -22.5F, alice, bob); rotation.run();
        var tracker = npc.getTrait(PacketNPC.class).getPacketTracker();
        tracker.unlink(bob); rotation.run(); clearPackets(); tracker.link(bob); pump();
        check(raw.get(bob).stream().anyMatch(packet -> packet instanceof ClientboundAddEntityPacket spawn
                        && spawn.getId() == entity.getId() && spawn.getYRot() == 90 && spawn.getXRot() == -22.5F
                        && spawn.getYHeadRot() == 90), "stable_session_late_pairing_has_all_private_angles");
        clearPackets(); rotation.run(); pump();
        check(packets.get(bob).isEmpty(), "pairing_counts_as_delivery_without_redundant_tick_packets");
        npc.getOrAddTrait(net.citizensnpcs.api.trait.trait.PlayerFilter.class).addPlayer(alice.getUUID()); tracker.unlink(alice); clearPackets(); stable.getSession().rotateToHave(135, 0); rotation.run(); pump();
        check(packets.get(alice).isEmpty(), "unpaired_hidden_viewer_receives_no_supplemental_packets");
        npc.getTrait(net.citizensnpcs.api.trait.trait.PlayerFilter.class).clear(); tracker.link(alice); pump();
        expect(alice, 96, 0, "readmitted_viewer_receives_current_session_angles");
        rotation.clearPacketSessions(); session(90, -22.5F, alice); rotation.run();
    }

    private static void transport(boolean virtual) throws Exception {
        var entity = npc.getEntity();
        var other = EntityType.COW.create(entity.level());
        var move = new ClientboundMoveEntityPacket.PosRot(entity.getId(), (short)-1234, (short)7, (short)30000,
                (byte)-9, (byte)17, true);
        var teleport = new ClientboundTeleportEntityPacket(entity);
        var unrelated = new ClientboundMoveEntityPacket.Rot(other.getId(), (byte)-5, (byte)13, true);
        var position = new ClientboundMoveEntityPacket.Pos(entity.getId(), (short)1, (short)-2, (short)3, false);
        var spawn = new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(), 1.125, -60.75, 9.5,
                22.5F, -45, entity.getType(), 71, new net.minecraft.world.phys.Vec3(-0.163125, 0.715875, 3.9), 135);
        List<Packet<? super ClientGamePacketListener>> samples = List.of(move, teleport, unrelated, position, spawn,
                new ClientboundMoveEntityPacket.Rot(entity.getId(), (byte)12, (byte)-25, false),
                new ClientboundRotateHeadPacket(entity, (byte)37));
        byte[] spawnBefore = spawnBytes(spawn), teleportBefore = teleportBytes(teleport);
        clearPackets();
        send(new ClientboundBundlePacket(samples), virtual); pump();
        var projected = raw.get(alice).stream().filter(ClientboundMoveEntityPacket.PosRot.class::isInstance)
                .map(ClientboundMoveEntityPacket.PosRot.class::cast).findFirst().orElseThrow();
        var decodedMove = decodeMove(projected);
        check(decodedMove.getXa() == -1234 && decodedMove.getYa() == 7 && decodedMove.getZa() == 30000
                && decodedMove.isOnGround() && decodedMove.getyRot() == 64 && decodedMove.getxRot() == -16,
                "native_" + virtual + "_posrot_preserves_deltas_and_ground");
        var projectedTeleport = raw.get(alice).stream().filter(ClientboundTeleportEntityPacket.class::isInstance)
                .map(ClientboundTeleportEntityPacket.class::cast).findFirst().orElseThrow();
        check(projectedTeleport.getId() == entity.getId() && projectedTeleport.getX() == teleport.getX()
                && projectedTeleport.getY() == teleport.getY() && projectedTeleport.getZ() == teleport.getZ()
                && projectedTeleport.isOnGround() == teleport.isOnGround() && projectedTeleport.getyRot() == 64
                && projectedTeleport.getxRot() == -16, "native_" + virtual + "_teleport_preserves_position_and_ground");
        var projectedSpawn = raw.get(alice).stream().filter(ClientboundAddEntityPacket.class::isInstance)
                .map(ClientboundAddEntityPacket.class::cast).findFirst().orElseThrow();
        var wireSpawn = spawnBytes(projectedSpawn);
        var expectedSpawn = spawnBytes(new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(), 1.125, -60.75, 9.5,
                -22.5F, 90, entity.getType(), 71, new net.minecraft.world.phys.Vec3(-0.163125, 0.715875, 3.9), 90));
        check(java.util.Arrays.equals(wireSpawn, expectedSpawn), "native_" + virtual + "_spawn_preserves_every_nonangle_wire_field");
        expect(alice, 64, -16, "native_" + virtual + "_broadcast_uses_private_body_pitch_head");
        check(raw.get(alice).contains(unrelated) && raw.get(alice).contains(position),
                "native_" + virtual + "_unrelated_entity_and_position_packets_unchanged");
        check(java.util.Arrays.equals(spawnBefore, spawnBytes(spawn)) && java.util.Arrays.equals(teleportBefore, teleportBytes(teleport))
                && move.getyRot() == -9 && move.getxRot() == 17, "native_" + virtual + "_shared_original_packets_unchanged");
        check(raw.get(bob).contains(move) && raw.get(bob).contains(spawn) && raw.get(bob).contains(teleport),
                "native_" + virtual + "_unselected_viewer_receives_original_packets");
        float yaw = entity.getYRot(), head = entity.getYHeadRot(), pitch = entity.getXRot();
        rotation.run();
        check(entity.getYRot() == yaw && entity.getXRot() == pitch && entity.getYHeadRot() == head,
                "native_" + virtual + "_private_session_does_not_rotate_physical_entity");
        if (virtual) {
            entity.setYRot(-45); entity.setXRot(45); entity.setYHeadRot(-90);
            entity.setPos(entity.getX() + 0.25, entity.getY(), entity.getZ());
            clearPackets();
            for (int tick = 0; tick < 6; tick++) npc.getTrait(PacketNPC.class).getPacketTracker().run();
            pump();
            check(!packets.get(alice).isEmpty() && packets.get(alice).stream().filter(packet -> packet.id() == entity.getId())
                    .allMatch(packet -> packet.yaw() == 64 && (packet.head() || packet.pitch() == -16)),
                    "virtual_native_sendChanges_never_leaks_physical_angles");
        }
    }

    private static void send(Packet<?> packet, boolean virtual) throws Exception {
        if (virtual) {
            var tracker = npc.getTrait(PacketNPC.class).getPacketTracker();
            var method = tracker.getClass().getDeclaredMethod("broadcast", Packet.class); method.setAccessible(true);
            method.invoke(tracker, packet);
        } else npc.getEntity().level().getServer().overworld().getChunkSource().broadcastAndSend(npc.getEntity(), packet);
    }

    private static byte[] spawnBytes(ClientboundAddEntityPacket packet) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), alice.registryAccess());
        try { ClientboundAddEntityPacket.STREAM_CODEC.encode(buffer, packet); byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes); return bytes; }
        finally { buffer.release(); }
    }
    private static byte[] teleportBytes(ClientboundTeleportEntityPacket packet) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try { ClientboundTeleportEntityPacket.STREAM_CODEC.encode(buffer, packet); byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes); return bytes; }
        finally { buffer.release(); }
    }
    private static ClientboundMoveEntityPacket.PosRot decodeMove(ClientboundMoveEntityPacket.PosRot packet) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try { ClientboundMoveEntityPacket.PosRot.STREAM_CODEC.encode(buffer, packet); return ClientboundMoveEntityPacket.PosRot.STREAM_CODEC.decode(buffer); }
        finally { buffer.release(); }
    }

    private static void callbackAndHeadPolicy() {
        rotation.clearPacketSessions();
        var entity = (LivingEntity) npc.getEntity();
        entity.setYRot(45); entity.setYBodyRot(-90); entity.setYHeadRot(90); entity.setXRot(-22.5F);
        var headOnly = rotation.createPacketSession(rotation.getGlobalParameters().clone().uuidFilter(alice.getUUID())
                .persist(true).immediate(true).headOnly(true).linkedBody(false));
        headOnly.getSession().rotateToHave(135, 22.5F); clearPackets(); rotation.run(); pump();
        check(packets.get(alice).contains(new WireRotation(entity.getId(), false, (byte)32, (byte)16))
                && packets.get(alice).contains(new WireRotation(entity.getId(), true, (byte)96, (byte)0)),
                "head_only_starts_from_native_entity_yaw");
        rotation.clearPacketSessions();
        var multi = session(90, 0, alice, bob); rotation.run();
        rotation.resetPlayerToPhysicalSession(bob.getUUID());
        check(multi.isActive() && rotation.getPacketSession(alice) == multi && rotation.getPacketSession(bob) == null,
                "reset_one_uuid_preserves_other_shared_viewer");
        rotation.clearPacketSessions();
        RotationTrait.PacketRotationSession[] original = {null}, replacement = {null};
        original[0] = rotation.createPacketSession(rotation.getGlobalParameters().clone().filter(viewer -> {
            if (replacement[0] == null) replacement[0] = session(135, 0, alice);
            return true;
        }).persist(true).immediate(true));
        original[0].getSession().rotateToHave(-90, 0);
        check(rotation.getPacketSession(alice) == null, "predicate_ownership_change_cannot_return_stale_session");
        check(rotation.getPacketSession(alice) == replacement[0], "predicate_replacement_owns_next_selection");
        rotation.clearPacketSessions();
        boolean[] recursed = {false};
        var recursive = rotation.createPacketSession(rotation.getGlobalParameters().clone().filter(viewer -> {
            if (!recursed[0]) {
                recursed[0] = true;
                check(rotation.getPacketSession(viewer) == null, "recursive_selection_is_bounded");
            }
            return true;
        }).persist(true));
        check(rotation.getPacketSession(alice) == recursive, "outer_selection_survives_bounded_query");
        rotation.clearPacketSessions();
        var changing = rotation.createPacketSession(rotation.getGlobalParameters().clone().filter(viewer -> {
            if (viewer == alice) npc.getOrAddTrait(net.citizensnpcs.api.trait.trait.PlayerFilter.class).addPlayer(alice.getUUID());
            return true;
        }).persist(true).immediate(true));
        changing.getSession().rotateToHave(90, 0); clearPackets(); rotation.run(); pump();
        check(packets.get(alice).isEmpty(), "filter_hiding_viewer_cannot_deliver_stale_angles");
        rotation.clearPacketSessions(); npc.getTrait(net.citizensnpcs.api.trait.trait.PlayerFilter.class).clear();
        session(90, -22.5F, alice); rotation.run();
    }

    private static void playerCommand(MinecraftServer server) throws Exception {
        npc.destroy();
        npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, "RotateCommand");
        npc.getOrAddTrait(LookClose.class).setEnabled(false); npc.getOrAddTrait(PacketNPC.class);
        rotation = npc.getOrAddTrait(RotationTrait.class);
        check(npc.spawn(new Location(server.overworld(), 0, -60, 0)), "player_command_npc_spawned");
        npc.getTrait(PacketNPC.class).run(); pump();
        session(90, -22.5F, alice); rotation.run();
        var source = server.createCommandSourceStack(); CitizensAPI.getDefaultNPCSelector().select(source, npc);
        clearPackets();
        check(server.getCommands().getDispatcher().execute("npc rotate --body -45 --head -90 --pitch 45", source) == 1,
                "native_rotate_command_executes");
        pump(); expect(alice, 64, -16, "direct_player_rotate_command_preserves_private_angles");
        check(npc.getEntity().getYRot() == -45 && npc.getEntity().getYHeadRot() == -90 && npc.getEntity().getXRot() == 45,
                "rotate_command_updates_physical_entity");
        check(packets.get(bob).contains(new WireRotation(npc.getEntity().getId(), false, (byte)-32, (byte)32))
                && packets.get(bob).contains(new WireRotation(npc.getEntity().getId(), true, (byte)-64, (byte)0)),
                "rotate_command_unselected_viewer_sees_physical_angles");
    }

    private static void lifecycle(MinecraftServer server) {
        var owned = rotation.getPacketSession(alice);
        npc.despawn(); check(!owned.isActive(), "despawn_ends_external_private_sessions");
        check(npc.spawn(new Location(server.overworld(), 0, -60, 0)), "virtual_respawn_succeeds");
        npc.getTrait(PacketNPC.class).run(); pump();
        check(rotation.getPacketSession(alice) == null, "respawn_does_not_reuse_old_entity_session");
        owned = session(90, 0, alice); rotation.run();
        clearPackets(); npc.removeTrait(RotationTrait.class); pump();
        check(!owned.isActive() && !packets.get(alice).isEmpty(), "trait_removal_ends_sessions_and_restores_paired_viewer");
        rotation = npc.getOrAddTrait(RotationTrait.class); owned = session(90, 0, alice);
        npc.addTrait(new RotationTrait());
        check(!owned.isActive(), "trait_replacement_retires_prior_owner");
        rotation = npc.getTrait(RotationTrait.class);
    }

    private static void expect(ServerPlayer viewer, int yaw, int pitch, String label) {
        int id = npc.getEntity().getId();
        check(packets.get(viewer).contains(new WireRotation(id, false, (byte)yaw, (byte)pitch))
                && packets.get(viewer).contains(new WireRotation(id, true, (byte)yaw, (byte)0)), label);
    }
    private static void capture(ServerPlayer player, Packet<?> packet) {
        if (packet instanceof ClientboundBundlePacket bundle) { bundle.subPackets().forEach(part -> capture(player, part)); return; }
        if (packet instanceof ClientboundChunkBatchFinishedPacket) pendingBatches.merge(player, 1, Integer::sum);
        raw.get(player).add(packet);
        if (packet instanceof ClientboundAddEntityPacket spawn) {
            packets.get(player).add(new WireRotation(spawn.getId(), false, wireAngle(spawn.getYRot()), wireAngle(spawn.getXRot())));
            packets.get(player).add(new WireRotation(spawn.getId(), true, wireAngle(spawn.getYHeadRot()), (byte)0));
        }
        if (packet instanceof ClientboundTeleportEntityPacket teleport) packets.get(player).add(new WireRotation(teleport.getId(), false, teleport.getyRot(), teleport.getxRot()));
        if (!(packet instanceof ClientboundMoveEntityPacket.PosRot) && !(packet instanceof ClientboundMoveEntityPacket.Rot) && !(packet instanceof ClientboundRotateHeadPacket)) return;
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            if (packet instanceof ClientboundMoveEntityPacket.PosRot move) {
                ClientboundMoveEntityPacket.PosRot.STREAM_CODEC.encode(buffer, move);
                int id = buffer.readVarInt(); buffer.readShort(); buffer.readShort(); buffer.readShort();
                packets.get(player).add(new WireRotation(id, false, buffer.readByte(), buffer.readByte()));
            } else if (packet instanceof ClientboundMoveEntityPacket.Rot rotation) {
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
    private static void clearPackets() { pump(); packets.values().forEach(List::clear); raw.values().forEach(List::clear); }

    private static ServerPlayer admit(MinecraftServer server, String name, double x, double z) {
        var player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault());
        packets.put(player, new ArrayList<>()); raw.put(player, new ArrayList<>());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                connection.configurePacketHandler(channel.pipeline());
                channel.pipeline().addLast("rotation-capture", new ChannelOutboundHandlerAdapter() {
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
    private static byte wireAngle(float degrees) { return (byte) Math.floor(degrees * 256.0F / 360.0F); }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++; LoggerFactory.getLogger("citizens").info("[ROTATIONAUDIT] PASS {}", label);
    }
}
