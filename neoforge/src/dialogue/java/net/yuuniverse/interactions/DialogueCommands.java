package net.yuuniverse.interactions;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Session ownership for restrictions on commands received from a player's connection. */
public final class DialogueCommands {
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private DialogueCommands() {}

    static void begin(Session session) { SESSIONS.put(session.player().getUUID(), session); }

    static void end(Session session) { SESSIONS.remove(session.player().getUUID(), session); }

    public static boolean isBlocked(UUID player, String command) {
        Session session = SESSIONS.get(player);
        return session != null && !session.isFinished() && !session.permitsCommand(command);
    }
}
