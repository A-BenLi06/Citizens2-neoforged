package net.citizensnpcs.mixin;

import java.util.List;
import java.util.function.Predicate;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.citizensnpcs.npc.entity.EntityHumanNPC;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;
import net.minecraft.world.entity.player.Player;

/** Mirrors CraftBukkit's sleeping-ignored/fauxSleeping semantics for Citizens player NPCs. */
@Mixin(SleepStatus.class)
public class SleepStatusMixin {
    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isSleeping()Z"))
    private boolean citizens$countIgnoredNPCAsSleeping(ServerPlayer player, Operation<Boolean> original) {
        return original.call(player) || player instanceof EntityHumanNPC;
    }

    @ModifyReturnValue(method = "update", at = @At("RETURN"))
    private boolean citizens$requireActualSleeperForNotification(boolean changed, List<ServerPlayer> players) {
        if (!changed || players.stream().noneMatch(EntityHumanNPC.class::isInstance)) return changed;
        return players.stream().anyMatch(player -> !player.isSpectator() && player.isSleeping());
    }

    @ModifyArg(method = "areEnoughDeepSleeping", at = @At(value = "INVOKE",
            target = "Ljava/util/stream/Stream;filter(Ljava/util/function/Predicate;)Ljava/util/stream/Stream;"), index = 0)
    private Predicate<ServerPlayer> citizens$countIgnoredNPCAsDeepSleeping(Predicate<ServerPlayer> original) {
        return player -> original.test(player) || player instanceof EntityHumanNPC;
    }

    @ModifyReturnValue(method = "areEnoughDeepSleeping", at = @At("RETURN"))
    private boolean citizens$requireActualDeepSleeper(boolean enough, int percentage, List<ServerPlayer> players) {
        if (!enough || players.stream().noneMatch(EntityHumanNPC.class::isInstance)) return enough;
        return players.stream().anyMatch(Player::isSleepingLongEnough);
    }
}
