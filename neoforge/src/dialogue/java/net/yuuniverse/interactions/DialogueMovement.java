package net.yuuniverse.interactions;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Session-owned movement restrictions, queried only after vanilla has validated a movement packet. */
public final class DialogueMovement {
    private static final Map<UUID, Session> LOCKS = new ConcurrentHashMap<>();

    private DialogueMovement() {}

    static void begin(Session session) {
        if (session.conversation().blockMovement) LOCKS.put(session.player().getUUID(), session);
    }

    static void end(Session session) {
        LOCKS.remove(session.player().getUUID(), session);
    }

    public static boolean isBlocked(UUID player) {
        Session session = LOCKS.get(player);
        return session != null && !session.isFinished();
    }
}
