package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.citizensnpcs.npc.NPCRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Optional Guard Villagers 2.4 ownership boundary; leaves the shared interaction/spawn events intact. */
@Pseudo
@Mixin(targets="tallestegg.guardvillagers.HandlerEvents",remap=false)
public abstract class GuardVillagersOwnershipMixin {
    @Inject(method="onEntityInteract(Lnet/neoforged/neoforge/event/entity/player/PlayerInteractEvent$EntityInteract;)V",
            at=@At("HEAD"),cancellable=true)
    private static void citizens$keepOwnedVillager(PlayerInteractEvent.EntityInteract event, CallbackInfo ci) {
        if (NPCRegistries.lookup(event.getTarget()) != null) ci.cancel();
    }

    @Inject(method="onLivingSpawned",at=@At("HEAD"),cancellable=true)
    private static void citizens$keepOwnedGoals(EntityJoinLevelEvent event, CallbackInfo ci) {
        var npc = NPCRegistries.lookup(event.getEntity());
        if (npc != null && !npc.useMinecraftAI()) ci.cancel();
    }

    /** Assistance can select existing golems after spawn without consulting their goal selectors. */
    @WrapOperation(method={"onEntityTarget", "onEntityHurt"},
            at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/Mob;setTarget(Lnet/minecraft/world/entity/LivingEntity;)V"))
    private static void citizens$keepOwnedCombat(Mob responder, LivingEntity target, Operation<Void> original) {
        var npc = NPCRegistries.lookup(responder);
        if (npc == null || npc.useMinecraftAI()) original.call(responder, target);
    }
}
