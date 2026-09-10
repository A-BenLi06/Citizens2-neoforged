package net.yuuniverse.interactions.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.yuuniverse.interactions.DialogueCommands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class DialogueCommandMixin {
    @Shadow public ServerPlayer player;

    // Restrict only player input, after vanilla has validated signatures and advanced the signed message chain.
    @Inject(method = "performUnsignedChatCommand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/commands/Commands;performCommand(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)V"),
            cancellable = true)
    private void interactions$unsignedCommand(String command, CallbackInfo callback) {
        interactions$check(command, callback);
    }

    @Inject(method = "performSignedChatCommand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/commands/Commands;performCommand(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)V"),
            cancellable = true)
    private void interactions$signedCommand(ServerboundChatCommandSignedPacket packet, LastSeenMessages seen,
            CallbackInfo callback) {
        interactions$check(packet.command(), callback);
    }

    private void interactions$check(String command, CallbackInfo callback) {
        if (!DialogueCommands.isBlocked(player.getUUID(), command)) return;
        player.sendSystemMessage(Component.translatableWithFallback("interactions.command.blocked",
                "You cannot use that command during a conversation."));
        callback.cancel();
    }
}
