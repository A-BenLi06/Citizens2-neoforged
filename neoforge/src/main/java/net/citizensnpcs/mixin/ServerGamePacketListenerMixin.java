package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.citizensnpcs.trait.PacketNPC;
import net.citizensnpcs.util.NPCVisibility;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;

/** Extend target lookup; vanilla still owns reach, border, item checks and interaction dispatch. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
    @Shadow public ServerPlayer player;

    @WrapOperation(method = "handleInteract", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ServerboundInteractPacket;getTarget(Lnet/minecraft/server/level/ServerLevel;)Lnet/minecraft/world/entity/Entity;"))
    private Entity citizens$resolveInteraction(ServerboundInteractPacket packet, ServerLevel level,
            Operation<Entity> original) {
        Entity target = original.call(packet, level);
        if (target == null)
            return PacketNPC.getInteractionTarget(((ServerboundInteractPacketAccessor) packet).citizens$getEntityId(), player);
        return NPCVisibility.isVisible(target, player) ? target : null;
    }
}
