package net.citizensnpcs.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.citizensnpcs.util.NeoProtectFireObservations;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Captures actual native fire writes, including burns that have no EntityPlaceEvent. */
@Mixin(FireBlock.class)
public abstract class FireBlockLoggingMixin {
    @WrapOperation(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean citizens$spread(ServerLevel level,BlockPos pos,BlockState state,int flags,Operation<Boolean> original) {
        var before=NeoProtectFireObservations.before(level,pos,state);
        try {return original.call(level,pos,state,flags);}finally{NeoProtectFireObservations.after(before);}
    }
    @WrapOperation(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerLevel;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean citizens$extinguish(ServerLevel level,BlockPos pos,boolean moving,Operation<Boolean> original) {
        var before=NeoProtectFireObservations.before(level,pos,Blocks.AIR.defaultBlockState());
        try {return original.call(level,pos,moving);}finally{NeoProtectFireObservations.after(before);}
    }
    @WrapOperation(method="checkBurnOut",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean citizens$replaceBurned(Level level,BlockPos pos,BlockState state,int flags,Operation<Boolean> original) {
        var before=NeoProtectFireObservations.before(level,pos,state);
        try {return original.call(level,pos,state,flags);}finally{NeoProtectFireObservations.after(before);}
    }
    @WrapOperation(method="checkBurnOut",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean citizens$consumeBurned(Level level,BlockPos pos,boolean moving,Operation<Boolean> original) {
        var before=NeoProtectFireObservations.before(level,pos,Blocks.AIR.defaultBlockState());
        try {return original.call(level,pos,moving);}finally{NeoProtectFireObservations.after(before);}
    }
}
