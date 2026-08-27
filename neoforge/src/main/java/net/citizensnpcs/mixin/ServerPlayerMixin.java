package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.citizensnpcs.npc.entity.EntityHumanNPC;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * Stops a player NPC from synchronising an inventory that nobody is looking at.
 * <p>
 * A player-type NPC is a {@link ServerPlayer} subclass, so it inherits the whole of {@code ServerPlayer.tick()} — and the
 * first thing that does is {@code containerMenu.broadcastChanges()}, which walks all 46 slots of the inventory menu,
 * copies every non-empty stack and diffs it against the copy last sent to the client. For a real player that is how the
 * open screen stays current. An NPC has no screen and no client asking for one: its connection is a stub, so every one of
 * those comparisons is thrown away.
 * <p>
 * Profiling the live server put this at the top of the mod's cost — two of the four samples that landed in Citizens at all
 * were inside {@code Slot.getItem} underneath this call, which is what 200-odd NPCs each diffing 46 slots twenty times a
 * second buys you. Skipping it for NPCs removes the work and the per-slot {@code ItemStack} copies with it.
 * <p>
 * Only the broadcast is skipped, not the menu. The NPC keeps a real {@code InventoryMenu}, so {@link Inventory} traits,
 * {@code /npc inventory} and anything else that reads or writes the slots behave exactly as before; what disappears is
 * only the attempt to push those slots down a connection that goes nowhere.
 * <p>
 * {@code @WrapOperation} rather than {@code @Redirect} so that another mod wrapping the same call still gets to run — a
 * redirect would claim the call site exclusively and crash on the conflict.
 */
@Mixin(ServerPlayer.class)
public class ServerPlayerMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;broadcastChanges()V"))
    private void citizens$skipInventorySyncForNPCs(AbstractContainerMenu menu, Operation<Void> original) {
        if ((Object) this instanceof EntityHumanNPC)
            return;
        original.call(menu);
    }
}
