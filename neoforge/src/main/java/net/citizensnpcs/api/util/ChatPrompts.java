package net.citizensnpcs.api.util;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
            session.runAbandonCallback();
        }
    }

    public static void abandonAll() {
        for (ChatPromptSession session : new java.util.ArrayList<>(SESSIONS.values())) {
            session.runAbandonCallback();
        }
        SESSIONS.clear();
    }

    public static ChatPromptSession begin(ServerPlayer player, ChatPrompt first) {
        return begin(player, first, null);
    }

    public static ChatPromptSession begin(ServerPlayer player, ChatPrompt first, Map<String, Object> initial) {
        if (first == null)
            return null;
        ChatPromptSession session = new ChatPromptSession(player, first, initial);
        SESSIONS.put(player.getUUID(), session);
        ask(session, first);
        return session;
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
        if (session.isEscape(input)) {
            abandon(session.getPlayer());
            return;
        }
        ChatPrompt next;
        try {
            next = session.getCurrent().acceptInput(session, input);
        } catch (Throwable ex) {
            Messaging.severe("Error in chat prompt");
            ex.printStackTrace();
            abandon(session.getPlayer());
            return;
        }
        if (next == null || session.isEnded()) {
            abandon(session.getPlayer());
            return;
        }
        session.setCurrent(next);
        ask(session, next);
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
            SESSIONS.remove(event.getEntity().getUUID());
        }
    }

    private static final Map<UUID, ChatPromptSession> SESSIONS = new HashMap<>();
}
