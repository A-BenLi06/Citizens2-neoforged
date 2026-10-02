package net.citizensnpcs.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.npc.NPCDeaths;
import net.citizensnpcs.npc.NPCRegistries;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.fluids.FluidType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Gate native flow-vector accumulation without skipping fluid contact/height calculations. */
@Mixin(Entity.class)
public abstract class EntityFluidPushMixin {
    @WrapOperation(method = "updateFluidHeightAndDoFluidPushing()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;isPushedByFluid(Lnet/neoforged/neoforge/fluids/FluidType;)Z"))
    private boolean citizens$fluidPolicy(Entity entity, FluidType type, Operation<Boolean> original) {
        if (!original.call(entity, type)) return false;
        NPC npc = NPCRegistries.lookup(entity);
        if (npc == null || npc.getEntity() != entity) npc = NPCDeaths.visualOwner(entity);
        return npc == null || npc.isPushableByFluids();
    }
}
