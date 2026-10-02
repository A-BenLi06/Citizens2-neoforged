package net.citizensnpcs.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.citizensnpcs.npc.NPCDeaths;
import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.server.level.ServerLevel;

@Mixin(ArmorStand.class)
public abstract class ArmorStandDeathMixin {
    @WrapMethod(method = "hurt")
    private boolean citizens$ownBreak(DamageSource source, float damage, Operation<Boolean> original) {
        return NPCDeaths.duringInstantDeath((ArmorStand) (Object) this, source, () -> original.call(source, damage));
    }

    @WrapMethod(method = "kill")
    private void citizens$ownKill(Operation<Void> original) {
        NPCDeaths.instantKill((ArmorStand) (Object) this, () -> original.call());
    }

    @Inject(method = { "brokenByPlayer", "brokenByAnything" }, at = @At("HEAD"))
    private void citizens$acceptedBreak(ServerLevel level, DamageSource source, CallbackInfo callback) {
        NPCDeaths.accepted((ArmorStand) (Object) this);
    }

    @WrapOperation(method = { "brokenByPlayer", "brokenByAnything" }, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;popResource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)V"))
    private void citizens$captureItem(Level level, BlockPos pos, ItemStack stack, Operation<Void> original) {
        NPCDeaths.captureBlockDrop((ArmorStand) (Object) this, () -> original.call(level, pos, stack));
    }
}
