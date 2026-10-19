package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.citizensnpcs.util.CreateMachineEvidence;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.deployer.BeltDeployerCallbacks", remap = false)
public abstract class CreateDeployerEvidenceMixin {
    @WrapMethod(method = "whenItemHeld")
    @Coerce private static Object citizens$process(@Coerce Object input, @Coerce Object handler, @Coerce Object machine, Operation<Object> original) {
        return CreateMachineEvidence.duringTransportDeployer((BlockEntity) machine, input, handler, () -> original.call(input, handler, machine));
    }
    @WrapOperation(method = "activate", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;getCraftingRemainingItem()Lnet/minecraft/world/item/ItemStack;"))
    private static ItemStack citizens$remainder(ItemStack held, Operation<ItemStack> original) {
        var remainder = original.call(held); CreateMachineEvidence.toolRemainder(held, remainder); return remainder;
    }
    @WrapOperation(method = "activate", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;hurtAndBreak(ILnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;)V"))
    private static void citizens$damage(ItemStack held, int amount, LivingEntity player, EquipmentSlot slot, Operation<Void> original) {
        CreateMachineEvidence.duringToolDamage(held, () -> original.call(held, amount, player, slot));
    }
    @WrapOperation(method = "activate", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;drop(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/entity/item/ItemEntity;"))
    private static ItemEntity citizens$drop(Player player, ItemStack remainder, boolean random, Operation<ItemEntity> original) {
        var attempted = remainder.copy(); var result = original.call(player, remainder, random); CreateMachineEvidence.toolDropped(attempted, result); return result;
    }
}
