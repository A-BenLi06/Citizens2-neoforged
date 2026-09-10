package net.yuuniverse.interactions.mixin;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.yuuniverse.interactions.DialogueMovement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class DialogueMovementMixin {
    @Shadow public ServerPlayer player;
    @Shadow public abstract void teleport(double x, double y, double z, float yaw, float pitch);

    // The second packet getX is after vanilla verifies the controlling passenger and tracked vehicle.
    @Inject(method = "handleMoveVehicle", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ServerboundMoveVehiclePacket;getX()D", ordinal = 1),
            cancellable = true)
    private void interactions$blockVehicleMovement(ServerboundMoveVehiclePacket packet, CallbackInfo callback) {
        if (!DialogueMovement.isBlocked(player.getUUID())) return;
        var vehicle = player.getRootVehicle();
        if (packet.getX() == vehicle.getX() && packet.getZ() == vehicle.getZ()) return;
        vehicle.setYRot(Mth.wrapDegrees(packet.getYRot()));
        vehicle.setXRot(Mth.wrapDegrees(packet.getXRot()));
        player.connection.send(new ClientboundMoveVehiclePacket(vehicle));
        callback.cancel();
    }

    // The first getX validates packet numbers. The second is reached on the server thread, after that validation
    // and the pending-teleport acknowledgement gate. Leave both vanilla checks intact.
    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;getX(D)D", ordinal = 1),
            cancellable = true)
    private void interactions$blockHorizontalMovement(ServerboundMovePlayerPacket packet, CallbackInfo callback) {
        if (!DialogueMovement.isBlocked(player.getUUID()) || !packet.hasPosition() || player.isPassenger()) return;
        if (packet.getX(player.getX()) == player.getX() && packet.getZ(player.getZ()) == player.getZ()) return;
        teleport(player.getX(), player.getY(), player.getZ(), Mth.wrapDegrees(packet.getYRot(player.getYRot())),
                Mth.wrapDegrees(packet.getXRot(player.getXRot())));
        callback.cancel();
    }
}
