package net.citizensnpcs.mixin;

import java.util.UUID;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import net.citizensnpcs.util.FtbMonetaryRewards;
import net.citizensnpcs.util.CommerceQuestTasks;
import net.citizensnpcs.util.FtbCommerceRewardGraph;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbquests.quest.TeamData", remap = false)
public abstract class FtbRewardClaimMixin {
    @WrapMethod(method = "areDependenciesComplete(Ldev/ftb/mods/ftbquests/quest/Quest;)Z")
    private boolean citizens$backedDependencies(@Coerce Object quest, Operation<Boolean> original) {
        return FtbCommerceRewardGraph.dependenciesComplete(this, quest, () -> original.call(quest));
    }

    @WrapMethod(method = "isCompleted(Ldev/ftb/mods/ftbquests/quest/QuestObject;)Z")
    private boolean citizens$backedCompletion(@Coerce Object object, Operation<Boolean> original) {
        return FtbCommerceRewardGraph.filter(this, object, false, original.call(object));
    }

    @WrapMethod(method = "isStarted(Ldev/ftb/mods/ftbquests/quest/QuestObject;)Z")
    private boolean citizens$backedStart(@Coerce Object object, Operation<Boolean> original) {
        return FtbCommerceRewardGraph.filter(this, object, true, original.call(object));
    }

    @WrapOperation(method = "claimReward(Lnet/minecraft/server/level/ServerPlayer;Ldev/ftb/mods/ftbquests/quest/reward/Reward;ZJ)V",
            at = @At(value = "INVOKE", target = "Ldev/ftb/mods/ftbquests/quest/TeamData;markRewardAsClaimed(Ljava/util/UUID;Ldev/ftb/mods/ftbquests/quest/reward/Reward;J)Z"))
    private boolean citizens$durableNativeClaim(@Coerce Object team, UUID actorId, @Coerce Object reward, long timestamp,
            Operation<Boolean> original, ServerPlayer player, @Coerce Object enclosingReward, boolean notify, long claimTime) {
        if (!CommerceQuestTasks.validateNativeReward(team, reward, player)) return false;
        return FtbMonetaryRewards.nativeClaim(team, reward, player, () -> original.call(team, actorId, reward, timestamp));
    }
}
