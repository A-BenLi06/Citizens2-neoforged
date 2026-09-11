package net.yuuniverse.interactions.mixin;

import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.yuuniverse.interactions.DialogueSelection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class DialogueSelectionMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleSetCarriedItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ServerboundSetCarriedItemPacket;getSlot()I", ordinal = 0),
            cancellable = true)
    private void interactions$scroll(ServerboundSetCarriedItemPacket packet, CallbackInfo callback) {
        if (!DialogueSelection.scroll(player, packet.getSlot())) return;
        player.connection.send(new ClientboundSetCarriedItemPacket(player.getInventory().selected));
        callback.cancel();
    }

    @Inject(method = "handlePlayerCommand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ServerboundPlayerCommandPacket;getAction()Lnet/minecraft/network/protocol/game/ServerboundPlayerCommandPacket$Action;", ordinal = 0))
    private void interactions$confirm(ServerboundPlayerCommandPacket packet, CallbackInfo callback) {
        if (packet.getAction() == ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY) DialogueSelection.confirm(player);
    }
}
