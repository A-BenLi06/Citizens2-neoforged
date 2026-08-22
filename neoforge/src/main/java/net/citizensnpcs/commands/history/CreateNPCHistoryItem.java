package net.citizensnpcs.commands.history;

import java.util.UUID;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCSelector;
import net.minecraft.commands.CommandSourceStack;

/** Undoing a creation destroys the NPC again. */
public class CreateNPCHistoryItem implements CommandHistoryItem {
    private final UUID uuid;

    public CreateNPCHistoryItem(NPC npc) {
        uuid = npc.getUniqueId();
    }

    @Override
    public void undo(CommandSourceStack sender, NPCSelector selector) {
        NPC npc = CitizensAPI.getNPCRegistry().getByUniqueIdGlobal(uuid);
        if (npc != null) {
            npc.destroy();
        }
    }
}
