package net.citizensnpcs.mixin;

import java.util.List;
import java.util.function.Function;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.citizensnpcs.util.CreateMachineEvidence;
import net.minecraft.world.item.ItemStack;

/** Exact pinned native lists; read-only logical inventory includes committed pending changes. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.belt.transport.BeltInventory", remap = false)
public abstract class CreateBeltInventoryEvidenceMixin implements net.citizensnpcs.util.CreateTransportInventory {
    @Shadow @Final private List<Object> items;
    @Shadow @Final private List<Object> toInsert;
    @Shadow @Final private List<Object> toRemove;

    @Unique public List<ItemStack> citizens$transportSnapshot() {
        var snapshot = new java.util.ArrayList<ItemStack>();
        for (var item : items) if (!toRemove.contains(item)) snapshot.add(CreateMachineEvidence.transportedStack(item).copy());
        for (var item : toInsert) snapshot.add(CreateMachineEvidence.transportedStack(item).copy());
        return snapshot;
    }

    @WrapMethod(method = "applyToEachWithin")
    private void citizens$storage(float position, float distance, Function<?, ?> callback, Operation<Void> original) {
        CreateMachineEvidence.duringBeltStorage(this, this::citizens$transportSnapshot, () -> original.call(position, distance, callback));
    }

    @WrapOperation(method = "applyToEachWithin", at = @At(value = "INVOKE", target = "Ljava/util/function/Function;apply(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object citizens$matched(Function<Object, Object> callback, Object input, Operation<Object> original) {
        var before = CreateMachineEvidence.transportedStack(input).copy();
        var result = original.call(callback, input); CreateMachineEvidence.transportMatched(input, before, result); return result;
    }
}
