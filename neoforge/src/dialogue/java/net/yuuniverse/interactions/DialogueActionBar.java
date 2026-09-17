package net.yuuniverse.interactions;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerPlayer;

/** Private conversation status, refreshed every second and immediately when the phase/title changes. */
final class DialogueActionBar {
    private ServerPlayer player;
    private Component title;
    private int ticks;

    DialogueActionBar(ServerPlayer player) { this.player = player; }

    void rebind(ServerPlayer replacement) { player = replacement; ticks = 20; }

    void tick(boolean enabled, DialogueMessages messages, String name, boolean waiting) {
        if (!enabled) { close(); return; }
        Component next = messages.actionBarTitle(name, waiting);
        if (!next.equals(title) || ++ticks >= 20) {
            send(next);
            title = next;
            ticks = 0;
        }
    }

    void close() {
        if (title != null) send(Component.empty());
        title = null;
        ticks = 0;
    }

    private void send(Component message) {
        if (player.connection != null) player.connection.send(new ClientboundSetActionBarTextPacket(message));
    }
}
