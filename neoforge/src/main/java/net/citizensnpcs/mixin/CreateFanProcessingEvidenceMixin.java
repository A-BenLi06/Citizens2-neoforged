package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.citizensnpcs.util.CreateMachineEvidence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.fan.processing.FanProcessing", remap = false)
public abstract class CreateFanProcessingEvidenceMixin {
    @WrapOperation(method = "applyProcessing(Lnet/minecraft/world/entity/item/ItemEntity;Lcom/simibubi/create/content/kinetics/fan/processing/FanProcessingType;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private static boolean citizens$entity(Level level, Entity entity, Operation<Boolean> original) {
        boolean accepted = original.call(level, entity); CreateMachineEvidence.worldEntityAdded(entity, accepted); return accepted;
    }
}
