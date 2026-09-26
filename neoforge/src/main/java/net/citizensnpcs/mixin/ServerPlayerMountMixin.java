package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.citizensnpcs.util.NPCMounts;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMountMixin {
    @WrapOperation(method = "stopRiding", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void citizens$keepEffectsOnCanceledDismount(ServerGamePacketListenerImpl connection, Packet<?> packet,
            Operation<Void> original) {
        if (NPCMounts.shouldRemoveVehicleEffects((ServerPlayer) (Object) this)) original.call(connection, packet);
    }
}
