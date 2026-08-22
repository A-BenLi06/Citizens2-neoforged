package net.citizensnpcs.commands.history;

import java.util.UUID;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCSelector;
import net.citizensnpcs.api.trait.trait.MobType;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.EntityType;

/** Undoing a removal recreates the NPC from the data it was saved with, keeping its id and UUID. */
public class RemoveNPCHistoryItem implements CommandHistoryItem {
    private final int id;
    private final DataKey key;
    private final EntityType<?> type;
    private final UUID uuid;

    public RemoveNPCHistoryItem(NPC from) {
        key = new MemoryDataKey();
        from.save(key);
        type = from.getOrAddTrait(MobType.class).getType();
        uuid = from.getUniqueId();
        id = from.getId();
    }

    @Override
    public void undo(CommandSourceStack sender, NPCSelector selector) {
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(type, uuid, id, key.getString("name"));
        npc.load(key);
        selector.select(sender, npc);
    }
}
