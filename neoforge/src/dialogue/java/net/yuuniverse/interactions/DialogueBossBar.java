package net.yuuniverse.interactions;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;

/** One private boss bar per session; no global custom-boss-event entry or independent scheduler. */
final class DialogueBossBar {
    private ServerPlayer player;
    private ServerBossEvent bar;
    private boolean waiting, named;
    private double duration;
    private int ticks;
    private BossBarSettings settings;

    DialogueBossBar(ServerPlayer player, BossBarSettings settings) {
        this.player = player;
        this.settings = settings;
    }

    void line(double seconds) {
        duration = seconds;
        ticks = 0;
        waiting = false;
        named = true;
    }

    void options() {
        waiting = true;
        named = true;
    }

    void rebind(ServerPlayer replacement) {
        if (bar != null) bar.removePlayer(player);
        player = replacement;
        // The next refresh relinks only after the session has validated the replacement's world and distance.
    }

    void refresh(BossBarSettings settings, DialogueMessages messages, String name) {
        this.settings = settings;
        ensureBar();
        if (bar == null) return;
        bar.setColor(settings.color());
        bar.setOverlay(settings.overlay());
        if (named) bar.setName(messages.bossBarTitle(name, waiting));
        updateProgress();
    }

    void elapsed(int ticks) {
        this.ticks = ticks;
        updateProgress();
    }

    private void ensureBar() {
        if (!settings.enabled()) { close(); return; }
        if (bar == null) {
            bar = new ServerBossEvent(Component.empty(), settings.color(), settings.overlay());
            updateProgress();
        }
        if (player.connection != null && !bar.getPlayers().contains(player)) bar.addPlayer(player);
    }

    private void updateProgress() {
        if (bar != null) bar.setProgress(progress(settings.changeProgressWithTime(), waiting, duration, ticks));
    }

    static float progress(boolean timed, boolean waiting, double seconds, int ticks) {
        if (!timed || waiting) return 1;
        if (!Double.isFinite(seconds) || seconds <= 0) return 0;
        // The original task increments once per second, filling from zero towards one.
        return (float) Math.clamp((ticks / 20) / seconds, 0, 1);
    }

    void close() {
        ServerBossEvent previous = bar;
        bar = null;
        if (previous != null) previous.removeAllPlayers();
    }
}
