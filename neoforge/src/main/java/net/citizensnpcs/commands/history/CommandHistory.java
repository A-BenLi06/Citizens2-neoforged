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
    private static final class Entry {
        final CommandHistoryItem item;
        Entry(CommandHistoryItem item) { this.item = item; }
    }

    private final ListMultimap<UUID, Entry> history = ArrayListMultimap.create();
    private final NPCSelector selector;
    private final Set<UUID> undoing = new HashSet<>();

    public CommandHistory(NPCSelector selector) {
        this.selector = selector;
    }

    public void add(CommandSourceStack sender, CommandHistoryItem item) {
        history.put(keyOf(sender), new Entry(item));
    }

    /** Discard only the operation that failed, preserving history added by callbacks. */
    public void discard(CommandSourceStack sender, CommandHistoryItem item) {
        List<Entry> entries = history.get(keyOf(sender));
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).item == item) {
                entries.remove(i);
                return;
            }
        }
    }

    private static UUID keyOf(CommandSourceStack sender) {
        Entity entity = sender == null ? null : sender.getEntity();
        return entity == null ? null : entity.getUUID();
    }

    public boolean undo(CommandSourceStack sender) throws CommandException {
        UUID key = keyOf(sender);
        if (!undoing.add(key)) throw new CommandException(Messages.UNDO_IN_PROGRESS);
        try {
            List<Entry> hist = history.get(key);
            if (hist.isEmpty()) return false;
            Entry entry = hist.get(hist.size() - 1);
            entry.item.undo(sender, selector);
            hist.remove(entry);
            return true;
        } finally { undoing.remove(key); }
    }

    /** Undo the entries present at invocation; callbacks may add history for a later command. */
    public void undoAll(CommandSourceStack sender) throws CommandException {
        UUID key = keyOf(sender);
        if (!undoing.add(key)) throw new CommandException(Messages.UNDO_IN_PROGRESS);
        try {
            List<Entry> hist = history.get(key);
            List<Entry> pending = List.copyOf(hist);
            for (int i = pending.size() - 1; i >= 0; i--) {
                Entry entry = pending.get(i);
                if (!hist.contains(entry)) continue;
                entry.item.undo(sender, selector);
                hist.remove(entry);
            }
        } finally { undoing.remove(key); }
    }
}
