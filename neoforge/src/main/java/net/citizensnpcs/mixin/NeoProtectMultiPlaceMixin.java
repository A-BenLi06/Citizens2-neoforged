package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.level.BlockEvent;

/** NeoProtect 1.0.4's multi-place listener owns all snapshots, including the base placement. */
@Pseudo
@Mixin(targets = "dev.northbladetech.neoprotect.NeoProtectService", remap = false)
public abstract class NeoProtectMultiPlaceMixin {
    @WrapOperation(method = "onBlockPlace(Lnet/neoforged/neoforge/event/level/BlockEvent$EntityPlaceEvent;)V",
            at = @At(value = "INVOKE", target = "Ldev/northbladetech/neoprotect/NeoProtectService;enqueueBlockChange("
                    + "Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;"
                    + "Ldev/northbladetech/neoprotect/model/ActionType;Ldev/northbladetech/neoprotect/model/BlockSnapshot;"
                    + "Ldev/northbladetech/neoprotect/model/BlockSnapshot;Lnet/minecraft/world/item/ItemStack;Ljava/lang/String;)V"))
    private void citizens$multiPlaceOwner(@Coerce Object service, ServerPlayer player, ServerLevel level,
            BlockPos pos, @Coerce Object action, @Coerce Object before, @Coerce Object after, ItemStack item,
            String detail, Operation<Void> original, BlockEvent.EntityPlaceEvent event) {
        // Skip only the duplicate log write; keep the shared event and the provider's fire attribution.
        if (!(event instanceof BlockEvent.EntityMultiPlaceEvent))
            original.call(service, player, level, pos, action, before, after, item, detail);
    }
}
