package net.citizensnpcs.commands.history;

import java.util.List;
import java.util.UUID;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

import net.citizensnpcs.api.npc.NPCSelector;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.Entity;

/**
 * Per-sender undo history for the commands that destroy or create NPCs.
 * <p>
 * Keyed by the sender's UUID, with the console sharing the null key — so one admin's undo cannot take back another's.
 */
public class CommandHistory {
    private final ListMultimap<UUID, CommandHistoryItem> history = ArrayListMultimap.create();
    private final NPCSelector selector;

    public CommandHistory(NPCSelector selector) {
        this.selector = selector;
    }

    public void add(CommandSourceStack sender, CommandHistoryItem item) {
        history.put(keyOf(sender), item);
    }

    private static UUID keyOf(CommandSourceStack sender) {
        Entity entity = sender == null ? null : sender.getEntity();
        return entity == null ? null : entity.getUUID();
    }

    public boolean undo(CommandSourceStack sender) {
        List<CommandHistoryItem> hist = history.get(keyOf(sender));
        if (hist.isEmpty())
            return false;
        hist.remove(hist.size() - 1).undo(sender, selector);
        return true;
    }
}
