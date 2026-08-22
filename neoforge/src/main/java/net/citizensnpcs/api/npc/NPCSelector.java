package net.citizensnpcs.api.npc;

import net.minecraft.commands.CommandSourceStack;

/**
 * Tracks which NPC each command sender has selected. Commands that act on "the selected NPC" go through this.
 */
public interface NPCSelector {
    void deselect(CommandSourceStack sender);

    /** @return the NPC this sender has selected, or null */
    NPC getSelected(CommandSourceStack sender);

    void select(CommandSourceStack sender, NPC npc);
}
