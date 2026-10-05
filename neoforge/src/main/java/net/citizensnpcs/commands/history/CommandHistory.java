package net.citizensnpcs.commands.history;

import java.util.List;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

import net.citizensnpcs.api.npc.NPCSelector;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.util.Messages;
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
    private final Set<UUID> undoing = new HashSet<>();

    public CommandHistory(NPCSelector selector) {
        this.selector = selector;
    }

    public void add(CommandSourceStack sender, CommandHistoryItem item) {
        history.put(keyOf(sender), item);
    }

    /** Discard only the operation that failed, preserving history added by callbacks. */
    public void discard(CommandSourceStack sender, CommandHistoryItem item) {
        history.remove(keyOf(sender), item);
    }

    private static UUID keyOf(CommandSourceStack sender) {
        Entity entity = sender == null ? null : sender.getEntity();
        return entity == null ? null : entity.getUUID();
    }

    public boolean undo(CommandSourceStack sender) throws CommandException {
        UUID key = keyOf(sender);
        if (!undoing.add(key)) throw new CommandException(Messages.UNDO_IN_PROGRESS);
        try {
            List<CommandHistoryItem> hist = history.get(key);
            if (hist.isEmpty()) return false;
            CommandHistoryItem item = hist.get(hist.size() - 1);
            item.undo(sender, selector);
            hist.remove(item);
            return true;
        } finally { undoing.remove(key); }
    }
}
