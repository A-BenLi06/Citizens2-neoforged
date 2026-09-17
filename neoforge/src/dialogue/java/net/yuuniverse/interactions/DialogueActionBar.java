package net.yuuniverse.interactions;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Private conversation status, refreshed every second and immediately when the phase/title changes. */
final class DialogueActionBar {
    private ServerPlayer player;
    private final ActionBarDisplays displays;
    private Component title;
    private int ticks;

    DialogueActionBar(ServerPlayer player, ActionBarDisplays displays) { this.player = player; this.displays = displays; }

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
        if (title != null) displays.closeStatus(this, player);
        title = null;
        ticks = 0;
    }

    private void send(Component message) {
        displays.status(this, player, message);
    }
}
