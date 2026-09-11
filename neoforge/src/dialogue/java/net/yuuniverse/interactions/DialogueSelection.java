package net.yuuniverse.interactions;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

public final class DialogueSelection {
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private DialogueSelection() {}
    static void begin(Session session) { SESSIONS.put(session.player().getUUID(), session); }
    static void end(Session session) { SESSIONS.remove(session.player().getUUID(), session); }

    public static void move(ServerPlayer player, double dx, double dy, double dz) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.usesSelection(SelectionSettings.Mode.MOVE)) return;
        Vec3 backwards = new Vec3(-dx, -dy, -dz);
        if (backwards.lengthSqr() == 0) return;
        double distance = backwards.normalize().subtract(player.getLookAngle()).length();
        int direction = distance >= 1.9 ? -1 : distance <= 0.3 ? 1 : 0;
        session.cycleSelection(direction, System.currentTimeMillis());
    }

    public static boolean scroll(ServerPlayer player, int slot) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.usesSelection(SelectionSettings.Mode.SCROLL) || slot < 0 || slot > 8) return false;
        int previous = player.getInventory().selected;
        if (slot != previous) {
            boolean next = (slot == 0 && previous == 8) || (slot > previous && !(previous == 0 && slot == 8));
            session.cycleSelection(next ? 1 : -1, System.currentTimeMillis());
        }
        return true;
    }

    public static void confirm(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session != null) session.confirmSelection();
    }
}
