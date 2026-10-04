package net.citizensnpcs.api.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import net.citizensnpcs.api.CitizensAPI;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Runs {@link ChatPrompt} conversations: one active chain per player, fed by whatever they type next.
 * <p>
 * Bukkit has a whole Conversation API for this — abandonment listeners, prefixes, escape sequences, per-conversation
 * echo suppression. None of it exists on NeoForge, and Citizens uses almost none of it, so this is the small part that
 * matters: capture the next chat line from that player, keep it out of public chat, and run the prompt chain on the
 * server thread.
 * <p>
 * Chat arrives off the server thread, which is why the prompt itself is dispatched through the scheduler: a prompt that
 * touches an NPC, a menu or the world must not do it from the netty thread.
 */
public class ChatPrompts {
    private ChatPrompts() {
    }

    /** @return true when this player is part-way through a conversation */
    public static boolean isActive(ServerPlayer player) {
        return SESSIONS.containsKey(player.getUUID());
    }

    /** Ends a conversation without running any more prompts. */
    public static void abandon(ServerPlayer player) {
        ChatPromptSession session = SESSIONS.remove(player.getUUID());
        if (session != null) {
            session.end();
            session.runAbandonCallback();
        }
    }

    /** Ends only this session, preserving any replacement that has already been opened. */
    public static void abandon(ChatPromptSession session) {
        if (session != null && SESSIONS.remove(session.getPlayer().getUUID(), session)) {
            session.end();
            session.runAbandonCallback();
        }
    }

    public static boolean isActive(ChatPromptSession session) {
        return session != null && SESSIONS.get(session.getPlayer().getUUID()) == session;
    }

    public static void abandonAll() {
        Throwable failure = null;
        for (Map.Entry<UUID, ChatPromptSession> entry : new java.util.ArrayList<>(SESSIONS.entrySet())) {
            if (!SESSIONS.remove(entry.getKey(), entry.getValue())) continue;
            try {
                entry.getValue().end();
                entry.getValue().runAbandonCallback();
            } catch (RuntimeException | Error ex) {
                if (failure == null) failure = ex;
                else if (failure != ex) failure.addSuppressed(ex);
            }
        }
        if (failure instanceof RuntimeException ex) throw ex;
        if (failure instanceof Error ex) throw ex;
    }

    public static ChatPromptSession begin(ServerPlayer player, ChatPrompt first) {
        return begin(player, first, null);
    }

    public static ChatPromptSession begin(ServerPlayer player, ChatPrompt first, Map<String, Object> initial) {
        return begin(player, first, initial, null);
    }

    /** Configures ownership/abandonment handlers before publishing the session or rendering its first prompt. */
    public static synchronized ChatPromptSession begin(ServerPlayer player, ChatPrompt first, Map<String, Object> initial,
            Consumer<ChatPromptSession> configure) {
        if (first == null)
            return null;
        if (!acceptingSessions) throw new IllegalStateException("Chat prompts are shut down");
        ChatPromptSession session = new ChatPromptSession(player, first, initial);
        if (configure != null) configure.accept(session);
        if (!acceptingSessions) throw new IllegalStateException("Chat prompts are shut down");
        ChatPromptSession previous = SESSIONS.put(player.getUUID(), session);
        if (previous != null) {
            previous.end();
            previous.runAbandonCallback();
        }
        try {
            refresh(session);
        } catch (RuntimeException | Error failure) {
            abandon(session);
            throw failure;
        }
        return session;
    }

    /** Supplies command-button input through the same prompt chain as ordinary chat. */
    public static boolean acceptInput(ServerPlayer player, String input) {
        ChatPromptSession session = SESSIONS.get(player.getUUID());
        if (session == null) return false;
        if (CitizensAPI.getScheduler().isOnOwnerThread()) handle(session, input);
        else CitizensAPI.getScheduler().runTask(() -> handle(session, input));
        return true;
    }

    /** Redraws the current prompt after a direct editor command. */
    public static void refresh(ChatPromptSession session) {
        if (!isActive(session)) return;
        if (session.isEnded()) { abandon(session); return; }
        ask(session, session.getCurrent());
        if (session.isEnded()) abandon(session);
    }

    /** Register once per server lifetime, reopening prompts after a completed shutdown. */
    public static synchronized void registerListeners() {
        if (shuttingDown || net.citizensnpcs.Citizens.getInstance() != null
                && net.citizensnpcs.Citizens.getInstance().isStopping())
            throw new IllegalStateException("Chat prompts are shutting down");
        if (dispatcher == null) {
            Dispatcher candidate = new Dispatcher();
            NeoForge.EVENT_BUS.register(candidate);
            dispatcher = candidate;
        }
        acceptingSessions = true;
    }

    /** Reject new conversations before invoking callbacks and always release the server's listener. */
    public static synchronized void shutdown() {
        if (shuttingDown) return;
        acceptingSessions = false;
        shuttingDown = true;
        Throwable failure = null;
        try {
            try { abandonAll(); }
            catch (RuntimeException | Error ex) { failure = ex; }
            if (dispatcher != null) {
                try {
                    NeoForge.EVENT_BUS.unregister(dispatcher);
                    dispatcher = null;
                } catch (RuntimeException | Error ex) {
                    if (failure == null) failure = ex;
                    else if (failure != ex) failure.addSuppressed(ex);
                }
            }
        } finally {
            shuttingDown = false;
        }
        if (failure instanceof RuntimeException ex) throw ex;
        if (failure instanceof Error ex) throw ex;
    }

    private static void ask(ChatPromptSession session, ChatPrompt prompt) {
        String text = prompt.getPromptText(session);
        if (text != null && !text.isEmpty()) {
            Messaging.send(session.getPlayer().createCommandSourceStack(), text);
        }
    }

    private static void handle(ChatPromptSession session, String input) {
        if (!isActive(session)) return;
        if (session.isEscape(input)) {
            abandon(session);
            return;
        }
        ChatPrompt next;
        try {
            next = session.getCurrent().acceptInput(session, input);
        } catch (Throwable ex) {
            Messaging.severe("Error in chat prompt");
            ex.printStackTrace();
            abandon(session);
            return;
        }
        if (next == null || session.isEnded()) {
            abandon(session);
            return;
        }
        session.setCurrent(next);
        try { refresh(session); }
        catch (RuntimeException | Error failure) {
            abandon(session);
            Messaging.severe("Error rendering chat prompt", failure.getMessage());
        }
    }

    private static class Dispatcher {
        @SubscribeEvent
        public void onChat(ServerChatEvent event) {
            ChatPromptSession session = SESSIONS.get(event.getPlayer().getUUID());
            if (session == null)
                return;
            // the answer is for us, not for everyone in chat
            event.setCanceled(true);
            String input = event.getRawText();
            CitizensAPI.getScheduler().runTask(() -> {
                // re-read: the conversation may have been abandoned in the meantime
                if (SESSIONS.get(event.getPlayer().getUUID()) == session) {
                    handle(session, input);
                }
            });
        }

        @SubscribeEvent
        public void onQuit(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) abandon(player);
        }
    }

    private static final Map<UUID, ChatPromptSession> SESSIONS = new ConcurrentHashMap<>();
    private static boolean acceptingSessions = true;
    private static boolean shuttingDown;
    private static Dispatcher dispatcher;
}
