package net.citizensnpcs.api.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.server.level.ServerPlayer;

/**
 * The state carried through a chat conversation: who is answering, and whatever the prompts have stashed along the way.
 * Stands in for Bukkit's {@code ConversationContext}.
 */
public class ChatPromptSession {
    private ChatPrompt current;
    private final Map<String, Object> data = new HashMap<>();
    private boolean ended;
    private final Set<String> escapes = new HashSet<>();
    private Runnable onAbandon;
    private ServerPlayer player;

    ChatPromptSession(ServerPlayer player, ChatPrompt first, Map<String, Object> initial) {
        this.player = player;
        this.current = first;
        if (initial != null) {
            data.putAll(initial);
        }
    }

    /**
     * Words that end the conversation instead of being answered, matched case-insensitively. Bukkit calls these escape
     * sequences; Citizens uses them so that typing {@code exit} or re-running the command leaves the editor.
     */
    public ChatPromptSession withEscapeSequences(String... sequences) {
        for (String sequence : sequences) {
            escapes.add(sequence.toLowerCase(Locale.ROOT));
        }
        return this;
    }

    /** Run when the conversation ends for any reason, including an escape sequence or the player logging out. */
    public ChatPromptSession onAbandon(Runnable callback) {
        this.onAbandon = callback;
        return this;
    }

    boolean isEscape(String input) {
        return escapes.contains(input.trim().toLowerCase(Locale.ROOT));
    }

    void runAbandonCallback() {
        if (onAbandon != null) {
            Runnable callback = onAbandon;
            onAbandon = null;
            callback.run();
        }
    }

    /** Ends the conversation after the current input has been handled. */
    public void end() {
        ended = true;
    }

    public ServerPlayer getPlayer() {
        // Bukkit's Player wrapper follows respawn; the native server replaces the ServerPlayer instance.
        if (player.isRemoved() && player.getServer() != null) {
            ServerPlayer replacement = player.getServer().getPlayerList().getPlayer(player.getUUID());
            if (replacement != null && !replacement.isRemoved()) player = replacement;
        }
        return player;
    }

    public Object getSessionData(String key) {
        return data.get(key);
    }

    public Object getSessionData(String key, Object def) {
        return data.getOrDefault(key, def);
    }

    public void setSessionData(String key, Object value) {
        if (value == null) {
            data.remove(key);
        } else {
            data.put(key, value);
        }
    }

    ChatPrompt getCurrent() {
        return current;
    }

    public boolean isEnded() {
        return ended;
    }

    void setCurrent(ChatPrompt current) {
        this.current = current;
    }
}
