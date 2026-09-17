package net.yuuniverse.interactions;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerPlayer;

/** One private ActionBar channel: timed actions temporarily take precedence over conversation status. */
final class ActionBarDisplays {
    private final Map<UUID, Display> displays = new HashMap<>();

    void status(Object owner, ServerPlayer player, Component message) {
        Display display = display(player);
        display.statusOwner = owner;
        display.status = message;
        if (display.action == null) send(player, message);
    }

    void closeStatus(Object owner, ServerPlayer player) {
        Display display = displays.get(player.getUUID());
        if (display == null || display.statusOwner != owner) return;
        display.statusOwner = null;
        display.status = null;
        if (display.action == null) {
            send(player, Component.empty());
            displays.remove(player.getUUID());
        }
    }

    void action(ServerPlayer player, Component message, int duration, long tick) {
        Display display = display(player);
        display.action = duration < 0 ? null : message;
        display.expires = tick + (long) duration + 1;
        // The original API schedules refreshes at duration - 40, duration - 80, ... > 0.
        display.refresh = duration > 40 ? tick + (duration - 1L) % 40 + 1 : Long.MAX_VALUE;
        send(player, message);
        if (display.action == null && display.statusOwner == null) displays.remove(player.getUUID());
    }

    void tick(long tick) {
        var iterator = displays.values().iterator();
        while (iterator.hasNext()) {
            Display display = iterator.next();
            ServerPlayer player = display.player.resolve();
            if (player == null) { iterator.remove(); continue; }
            boolean rebound = player != display.viewer;
            display.viewer = player;
            if (display.action == null) continue;
            if (tick >= display.expires) {
                display.action = null;
                send(player, display.status == null ? Component.empty() : display.status);
                if (display.statusOwner == null) iterator.remove();
            } else if (rebound || tick >= display.refresh) {
                send(player, display.action);
                if (tick >= display.refresh) display.refresh += 40;
                if (display.refresh >= display.expires - 1) display.refresh = Long.MAX_VALUE;
            }
        }
    }

    void cancel(ServerPlayer player) {
        Display display = displays.get(player.getUUID());
        if (display != null && display.player.matches(player)) {
            displays.remove(player.getUUID());
            send(player, Component.empty());
        }
    }

    void clear() {
        for (Display display : displays.values()) {
            ServerPlayer player = display.player.resolve();
            if (player != null) send(player, Component.empty());
        }
        displays.clear();
    }

    private Display display(ServerPlayer player) {
        Display existing = displays.get(player.getUUID());
        if (existing != null && existing.player.matches(player)) return existing;
        Display created = new Display(new ActionPlayer(player));
        displays.put(player.getUUID(), created);
        return created;
    }

    private static void send(ServerPlayer player, Component message) {
        if (player.connection != null) player.connection.send(new ClientboundSetActionBarTextPacket(message));
    }

    private static final class Display {
        final ActionPlayer player;
        ServerPlayer viewer;
        Object statusOwner;
        Component status, action;
        long expires, refresh;
        Display(ActionPlayer player) { this.player = player; viewer = player.original(); }
    }
}
