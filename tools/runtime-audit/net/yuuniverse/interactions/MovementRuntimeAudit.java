package net.yuuniverse.interactions;

import java.io.File;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "interactions")
public final class MovementRuntimeAudit {
    private static boolean ran;
    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (ran || net.citizensnpcs.audit.FixtureRuntimeAudit.elapsedTicks(event.getServer()) < 50) return;
        ran = true;
        Session session = null;
        Session replacement = null;
        ServerPlayer player = null;
        net.minecraft.world.entity.Entity vehicle = null;
        try {
            player = new ServerPlayer(event.getServer(), event.getServer().overworld(),
                    new GameProfile(UUID.randomUUID(), "MoveAudit"), ClientInformation.createDefault());
            player.setPos(0, -55, 0);
            var listener = new ServerGamePacketListenerImpl(event.getServer(), new Connection(PacketFlow.SERVERBOUND),
                    player, CommonListenerCookie.createInitial(player.getGameProfile(), false));
            player.connection = listener;
            player.serverLevel().addNewPlayer(player);
            var story = new Conversation();
            story.blockMovement = true;
            var node = new Conversation.Node("conversation1");
            var progress = new ProgressStore(new File("config/movement-audit-progress"));
            Session.Engine engine = new Session.Engine() {
                public Actions actions() { return new Actions(new ItemLibrary(), new Economy()); }
                public ProgressStore progress() { return progress; }
            };
            session = new Session(engine, story, node, player, null);
            check(DialogueMovement.isBlocked(player.getUUID()), "session_acquires_lock");
            listener.handleMovePlayer(new ServerboundMovePlayerPacket.PosRot(1, -55, 0, 45, 10, false));
            check(player.getX() == 0 && player.getZ() == 0, "horizontal_packet_rejected");
            check(player.getYRot() == 45 && player.getXRot() == 10, "rejected_movement_preserves_rotation");
            var teleportId = ServerGamePacketListenerImpl.class.getDeclaredField("awaitingTeleport");
            teleportId.setAccessible(true);
            listener.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(teleportId.getInt(listener)));
            listener.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(0, -54.5, 0, false));
            check(player.getY() == -54.5, "vertical_movement_allowed");
            listener.handleMovePlayer(new ServerboundMovePlayerPacket.Rot(90, 20, false));
            check(player.getYRot() == 90 && player.getXRot() == 20, "rotation_only_allowed");
            replacement = new Session(engine, story, node, player, null);
            session.end(false);
            check(DialogueMovement.isBlocked(player.getUUID()), "old_session_does_not_unlock_replacement");
            replacement.end(false);
            check(!DialogueMovement.isBlocked(player.getUUID()), "session_end_releases_lock");
            listener.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(0.25, -54.5, 0, false));
            check(player.getX() == 0.25, "normal_movement_restored");
            vehicle = net.minecraft.world.entity.EntityType.BOAT.create(player.serverLevel());
            vehicle.setPos(0, -54.5, 0);
            player.serverLevel().addFreshEntity(vehicle);
            check(player.startRiding(vehicle, true) && vehicle.getControllingPassenger() == player, "vehicle_control_established");
            listener.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(teleportId.getInt(listener)));
            // Match the connection's tick-start vehicle snapshot without advancing unrelated player physics.
            setField(listener, "lastVehicle", vehicle);
            for (String prefix : new String[] { "vehicleFirstGood", "vehicleLastGood" }) {
                setField(listener, prefix + "X", vehicle.getX());
                setField(listener, prefix + "Y", vehicle.getY());
                setField(listener, prefix + "Z", vehicle.getZ());
            }
            replacement = new Session(engine, story, node, player, null);
            var requested = net.minecraft.world.entity.EntityType.BOAT.create(player.serverLevel());
            requested.setPos(1, -54.5, 0);
            requested.setYRot(60);
            listener.handleMoveVehicle(new net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket(requested));
            check(vehicle.getX() == 0 && vehicle.getZ() == 0, "vehicle_horizontal_packet_rejected");
            check(vehicle.getYRot() == 60, "vehicle_rotation_preserved");
            requested.setPos(0, -54.25, 0);
            listener.handleMoveVehicle(new net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket(requested));
            check(vehicle.getY() == -54.25, "vehicle_vertical_movement_allowed");
            replacement.end(false);
            requested.setPos(0.25, -54.25, 0);
            listener.handleMoveVehicle(new net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket(requested));
            check(vehicle.getX() == 0.25, "vehicle_movement_restored");
            LoggerFactory.getLogger("interactions").info("[MOVEMENTAUDIT] COMPLETE 13/13");
        } catch (Throwable failure) {
            LoggerFactory.getLogger("interactions").error("[MOVEMENTAUDIT] FAILED", failure);
        } finally {
            if (session != null) session.end(false);
            if (replacement != null) replacement.end(false);
            if (player != null) player.stopRiding();
            if (vehicle != null) vehicle.discard();
            if (player != null) player.serverLevel().removePlayerImmediately(player,
                    net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        }
    }
    private static void setField(Object listener, String name, Object value) throws ReflectiveOperationException {
        var field = ServerGamePacketListenerImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(listener, value);
    }
    private static void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        LoggerFactory.getLogger("interactions").info("[MOVEMENTAUDIT] PASS {}", name);
    }
}
