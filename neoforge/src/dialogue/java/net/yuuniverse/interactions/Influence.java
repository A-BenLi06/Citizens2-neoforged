package net.yuuniverse.interactions;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

/** Native influence API. Keys are exact conversation filenames without the .yml extension. */
public final class Influence {
    public enum Operation {
        SET, ADD, REMOVE;

        public int apply(int current, int amount) {
            if (this != SET && amount <= 0)
                throw new IllegalArgumentException("Influence add/remove amounts must be positive");
            return switch (this) {
                case SET -> amount;
                case ADD -> Math.addExact(current, amount);
                case REMOVE -> Math.subtractExact(current, amount);
            };
        }
    }

    private final ProgressStore progress;
    private final ConversationLibrary conversations;

    public Influence(ProgressStore progress, ConversationLibrary conversations) {
        this.progress = java.util.Objects.requireNonNull(progress);
        this.conversations = java.util.Objects.requireNonNull(conversations);
    }

    public int get(ServerPlayer player, String conversation) {
        return progress.getInfluence(player.getUUID(), conversation);
    }

    public int set(ServerPlayer player, String conversation, int amount) {
        return change(player, conversation, Operation.SET, amount);
    }

    public int add(ServerPlayer player, String conversation, int amount) {
        return change(player, conversation, Operation.ADD, amount);
    }

    public int remove(ServerPlayer player, String conversation, int amount) {
        return change(player, conversation, Operation.REMOVE, amount);
    }

    public int change(ServerPlayer player, String conversation, Operation operation, int amount) {
        requireConversation(conversation);
        return progress.changeInfluence(player.getUUID(), player.getGameProfile().getName(), conversation,
                current -> operation.apply(current, amount));
    }

    private void requireConversation(String conversation) {
        if (conversation == null || conversations.byId(conversation) == null)
            throw new IllegalArgumentException("Unknown influence conversation: " + conversation);
        ProgressStore.requireInfluenceKey(conversation);
    }

    ProgressStore progress() { return progress; }

    Plan plan(UUID player) { return new Plan(player); }

    /** Models native influence changes during preflight without modifying player data. */
    final class Plan {
        private final UUID player;
        private final Map<String, Integer> values = new HashMap<>();

        private Plan(UUID player) { this.player = player; }

        int get(String conversation) {
            return values.computeIfAbsent(conversation, key -> progress.getInfluence(player, key));
        }

        void change(String conversation, Operation operation, int amount) {
            requireConversation(conversation);
            values.put(conversation, operation.apply(get(conversation), amount));
        }
    }
}
