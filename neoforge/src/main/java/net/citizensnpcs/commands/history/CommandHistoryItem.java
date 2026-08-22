package net.citizensnpcs.commands.history;

import net.citizensnpcs.api.npc.NPCSelector;
import net.minecraft.commands.CommandSourceStack;

/** One undoable step, recorded so that {@code /npc undo} can put it back. */
public interface CommandHistoryItem {
    void undo(CommandSourceStack sender, NPCSelector selector);
}
