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
        for (ChatPromptSession session : new java.util.ArrayList<>(SESSIONS.values())) {
            abandon(session);
        }
    }

    public static ChatPromptSession begin(ServerPlayer player, ChatPrompt first) {
        return begin(player, first, null);
    }

    public static ChatPromptSession begin(ServerPlayer player, ChatPrompt first, Map<String, Object> initial) {
        return begin(player, first, initial, null);
    }

    /** Configures ownership/abandonment handlers before publishing the session or rendering its first prompt. */
    public static ChatPromptSession begin(ServerPlayer player, ChatPrompt first, Map<String, Object> initial,
            Consumer<ChatPromptSession> configure) {
        if (first == null)
            return null;
        ChatPromptSession session = new ChatPromptSession(player, first, initial);
        if (configure != null) configure.accept(session);
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

    /** Registered once at startup; there is nothing per-conversation to register or leak. */
    public static void registerListeners() {
        NeoForge.EVENT_BUS.register(new Dispatcher());
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
}
