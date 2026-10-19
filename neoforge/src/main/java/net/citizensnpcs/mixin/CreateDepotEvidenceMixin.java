package net.citizensnpcs.mixin;

import java.util.function.Function;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.citizensnpcs.util.CreateMachineEvidence;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.logistics.depot.DepotBehaviour", remap = false)
public abstract class CreateDepotEvidenceMixin {
    @WrapMethod(method = "applyToAllItems")
    private void citizens$storage(float distance, Function<?, ?> callback, Operation<Void> original) {
        CreateMachineEvidence.duringTransportStorage(CreateMachineEvidence.transportBlockEntity(this),
                () -> CreateMachineEvidence.depotInventory(this), () -> original.call(distance, callback));
    }

    @WrapOperation(method = "applyToAllItems", at = @At(value = "INVOKE", target = "Ljava/util/function/Function;apply(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object citizens$matched(Function<Object, Object> callback, Object input, Operation<Object> original) {
        var before = CreateMachineEvidence.transportedStack(input).copy();
        var result = original.call(callback, input); CreateMachineEvidence.transportMatched(input, before, result); return result;
    }
}
