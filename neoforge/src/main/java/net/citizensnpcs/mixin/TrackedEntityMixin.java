package net.citizensnpcs.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.citizensnpcs.util.NPCVisibility;
import net.citizensnpcs.util.HologramMetadata;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Keep vanilla's tracking range/chunk rules and pairing bookkeeping authoritative. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin implements NPCVisibility.TrackedEntity {
    @Shadow @org.spongepowered.asm.mixin.Final private Entity entity;
    @Shadow public abstract void updatePlayers(List<ServerPlayer> players);

    @WrapOperation(method = "broadcast", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerPlayerConnection;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void citizens$personalizeHologramUpdate(ServerPlayerConnection connection, Packet<?> packet,
            Operation<Void> original) {
        original.call(connection, HologramMetadata.rewrite(entity, connection.getPlayer(), packet));
    }

    @Override public void citizens$updateViewers(List<ServerPlayer> players) {
        updatePlayers(players);
    }

    @WrapOperation(method = "updatePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;broadcastToPlayer(Lnet/minecraft/server/level/ServerPlayer;)Z"))
    private boolean citizens$visibility(Entity entity, ServerPlayer viewer, Operation<Boolean> original) {
        return original.call(entity, viewer) && NPCVisibility.isVisible(entity, viewer);
    }
}
