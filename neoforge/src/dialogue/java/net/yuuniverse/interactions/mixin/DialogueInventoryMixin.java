package net.yuuniverse.interactions.mixin;

import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.yuuniverse.interactions.DialogueInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class DialogueInventoryMixin {
    @Shadow public ServerPlayer player;

    // Menu, player and slot checks have run; remote updates have not yet been suppressed.
    @Inject(method = "handleContainerClick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;suppressRemoteUpdates()V"), cancellable = true)
    private void interactions$containerClick(ServerboundContainerClickPacket packet, CallbackInfo callback) {
        if (!DialogueInventory.isBlocked(player.getUUID())) return;
        player.containerMenu.sendAllDataToRemote();
        callback.cancel();
    }

    // Both single-item and whole-stack drops reach this invocation before inventory removal.
    @Inject(method = "handlePlayerAction", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;drop(Z)Z"), cancellable = true)
    private void interactions$drop(ServerboundPlayerActionPacket packet, CallbackInfo callback) {
        if (!DialogueInventory.isBlocked(player.getUUID())) return;
        player.inventoryMenu.sendAllDataToRemote();
        callback.cancel();
    }

    // This path is separate from normal container clicks and only runs after the creative-mode check.
    @Inject(method = "handleSetCreativeModeSlot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ServerboundSetCreativeModeSlotPacket;itemStack()Lnet/minecraft/world/item/ItemStack;"),
            cancellable = true)
    private void interactions$creativeSlot(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo callback) {
        if (!DialogueInventory.isBlocked(player.getUUID())) return;
        player.inventoryMenu.sendAllDataToRemote();
        callback.cancel();
    }
}
