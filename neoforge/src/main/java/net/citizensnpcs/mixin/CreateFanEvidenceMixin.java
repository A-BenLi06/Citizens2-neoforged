package net.citizensnpcs.mixin;

import java.util.function.Function;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.citizensnpcs.util.CreateMachineEvidence;
import net.minecraft.world.entity.item.ItemEntity;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.fan.AirCurrent", remap = false)
public abstract class CreateFanEvidenceMixin {
    @WrapOperation(method = "tickAffectedEntities", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/fan/processing/FanProcessing;applyProcessing(Lnet/minecraft/world/entity/item/ItemEntity;Lcom/simibubi/create/content/kinetics/fan/processing/FanProcessingType;)Z"))
    private boolean citizens$world(ItemEntity input, @Coerce Object type, Operation<Boolean> original) {
        return CreateMachineEvidence.duringFanWorld(this, input, () -> original.call(input, type));
    }
    @WrapOperation(method = "tickAffectedHandlers", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/belt/behaviour/TransportedItemStackHandlerBehaviour;handleProcessingOnAllItems(Ljava/util/function/Function;)V"))
    private void citizens$transport(@Coerce Object handler, Function<Object, Object> callback, Operation<Void> original) {
        CreateMachineEvidence.duringFanStorage(this, handler, callback, wrapped -> original.call(handler, wrapped));
    }
}
