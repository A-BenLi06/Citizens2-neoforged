package net.citizensnpcs.mixin;

import java.util.UUID;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.citizensnpcs.util.FtbMonetaryRewards;
import net.citizensnpcs.util.CommerceQuestTasks;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbquests.quest.TeamData", remap = false)
public abstract class FtbRewardClaimMixin {
    @WrapOperation(method = "claimReward(Lnet/minecraft/server/level/ServerPlayer;Ldev/ftb/mods/ftbquests/quest/reward/Reward;ZJ)V",
            at = @At(value = "INVOKE", target = "Ldev/ftb/mods/ftbquests/quest/TeamData;markRewardAsClaimed(Ljava/util/UUID;Ldev/ftb/mods/ftbquests/quest/reward/Reward;J)Z"))
    private boolean citizens$durableNativeClaim(@Coerce Object team, UUID actorId, @Coerce Object reward, long timestamp,
            Operation<Boolean> original, ServerPlayer player, @Coerce Object enclosingReward, boolean notify, long claimTime) {
        if (!CommerceQuestTasks.validateNativeReward(team, reward, player)) return false;
        return FtbMonetaryRewards.nativeClaim(team, reward, player, () -> original.call(team, actorId, reward, timestamp));
    }
}
