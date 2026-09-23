package net.citizensnpcs.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.citizensnpcs.util.NPCVisibility;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Keep vanilla's tracking range/chunk rules and pairing bookkeeping authoritative. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin implements NPCVisibility.TrackedEntity {
    @Shadow public abstract void updatePlayers(List<ServerPlayer> players);

    @Override public void citizens$updateViewers(List<ServerPlayer> players) {
        updatePlayers(players);
    }

    @WrapOperation(method = "updatePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;broadcastToPlayer(Lnet/minecraft/server/level/ServerPlayer;)Z"))
    private boolean citizens$visibility(Entity entity, ServerPlayer viewer, Operation<Boolean> original) {
        return original.call(entity, viewer) && NPCVisibility.isVisible(entity, viewer);
    }
}
