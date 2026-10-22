package net.citizensnpcs.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.citizensnpcs.util.NeoProtectFireObservations;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.LavaFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Observes both lava-ignition writes after NeoForge resolves its native fluid event. */
@Mixin(LavaFluid.class)
public abstract class LavaFluidLoggingMixin {
    @WrapOperation(method="randomTick",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean citizens$ignition(Level level,BlockPos pos,BlockState state,Operation<Boolean> original) {
        var before=state!=null && state.getBlock() instanceof BaseFireBlock
                ?NeoProtectFireObservations.before(level,pos,state):null;
        try {return original.call(level,pos,state);}finally{NeoProtectFireObservations.after(before);}
    }
}
