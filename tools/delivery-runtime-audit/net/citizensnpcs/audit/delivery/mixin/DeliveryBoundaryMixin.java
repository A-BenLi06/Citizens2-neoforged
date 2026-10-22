package net.citizensnpcs.audit.delivery.mixin;

import net.citizensnpcs.audit.delivery.DeliveryRuntimeAudit;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test-only pauses around the actual installed provider methods. */
@Mixin(value = ItemTask.class, remap = false)
public abstract class DeliveryBoundaryMixin {
    @Inject(method = "submitTask(Ldev/ftb/mods/ftbquests/quest/TeamData;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"))
    private void before(TeamData team, ServerPlayer player, ItemStack stack, CallbackInfo ci) {
        DeliveryRuntimeAudit.boundary("before", player);
    }
    @Inject(method = "insert(Ldev/ftb/mods/ftbquests/quest/TeamData;Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;", at = @At("RETURN"))
    private void progress(TeamData team, ItemStack stack, boolean simulate, CallbackInfoReturnable<ItemStack> ci) {
        if (!simulate && ci.getReturnValue() != stack) DeliveryRuntimeAudit.boundary("progress", null);
    }
    @Inject(method = "submitTask(Ldev/ftb/mods/ftbquests/quest/TeamData;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;)V", at = @At("RETURN"))
    private void inventory(TeamData team, ServerPlayer player, ItemStack stack, CallbackInfo ci) {
        DeliveryRuntimeAudit.boundary("inventory", player);
    }
}
