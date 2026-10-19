package net.citizensnpcs.mixin;

import java.util.List;
import org.spongepowered.asm.mixin.*;
import net.citizensnpcs.util.CreateBasinStorage;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.processing.basin.BasinBlockEntity", remap = false)
public abstract class CreateBasinStorageMixin implements CreateBasinStorage {
    @Shadow protected List<ItemStack> spoutputBuffer;
    @Shadow protected List<FluidStack> spoutputFluidBuffer;
    @Unique public Snapshot citizens$basinSnapshot() {
        var basin = (BlockEntity) (Object) this; var level = basin.getLevel();
        var items = level.getCapability(Capabilities.ItemHandler.BLOCK, basin.getBlockPos(), null);
        var fluids = level.getCapability(Capabilities.FluidHandler.BLOCK, basin.getBlockPos(), null);
        if (items == null || fluids == null) throw new IllegalStateException("Native basin capabilities unavailable");
        var itemStacks = new java.util.ArrayList<ItemStack>();
        for (int slot = 0; slot < items.getSlots(); slot++) itemStacks.add(items.getStackInSlot(slot).copy());
        itemStacks.addAll(spoutputBuffer);
        var fluidStacks = new java.util.ArrayList<FluidStack>();
        for (int tank = 0; tank < fluids.getTanks(); tank++) fluidStacks.add(fluids.getFluidInTank(tank).copy());
        fluidStacks.addAll(spoutputFluidBuffer);
        return new Snapshot(itemStacks, fluidStacks);
    }
}
