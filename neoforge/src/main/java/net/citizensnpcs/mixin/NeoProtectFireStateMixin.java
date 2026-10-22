package net.citizensnpcs.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** NeoForge's null-entity placement argument exposes the old state; the fire logger needs the actual result. */
@Pseudo
@Mixin(targets="dev.northbladetech.neoprotect.NeoProtectService",remap=false)
public abstract class NeoProtectFireStateMixin {
    @WrapOperation(method={"onFireSpread","onFireDestroy"},at=@At(value="INVOKE",target="Lnet/neoforged/neoforge/event/level/BlockEvent$EntityPlaceEvent;getPlacedBlock()Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState citizens$resultingFireState(BlockEvent.EntityPlaceEvent event,Operation<BlockState> original) {
        return event.getEntity()==null?event.getBlockSnapshot().getCurrentState():original.call(event);
    }
}
