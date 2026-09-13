package net.citizensnpcs.commands.history;

import java.util.UUID;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCSelector;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.trait.trait.MobType;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.EntityType;
import net.citizensnpcs.util.Messages;

/** Undoing a removal recreates the NPC from the data it was saved with, keeping its id and UUID. */
public class RemoveNPCHistoryItem implements CommandHistoryItem {
    private final int id;
    private final DataKey key;
    private final EntityType<?> type;
    private final UUID uuid;
    private final NPCRegistry registry;
    private final boolean defaultRegistry;
    private final boolean temporaryRegistry;
    private final boolean namedRegistry;

    public RemoveNPCHistoryItem(NPC from) {
        key = new MemoryDataKey();
        from.saveSnapshot(key);
        type = from.getOrAddTrait(MobType.class).getType();
        uuid = from.getUniqueId();
        id = from.getId();
        registry = from.getOwningRegistry();
        defaultRegistry = registry == CitizensAPI.getNPCRegistry();
        temporaryRegistry = registry == CitizensAPI.getTemporaryNPCRegistry();
        namedRegistry = CitizensAPI.getNamedNPCRegistry(registry.getName()) == registry;
    }

    @Override
    public void undo(CommandSourceStack sender, NPCSelector selector) throws CommandException {
        NPCRegistry destination = defaultRegistry ? CitizensAPI.getNPCRegistry()
                : temporaryRegistry ? CitizensAPI.getTemporaryNPCRegistry()
                : namedRegistry ? CitizensAPI.getNamedNPCRegistry(registry.getName()) : registry;
        if (destination == null) throw new CommandException(Messages.UNKNOWN_NPC_REGISTRY, registry.getName());
        if (destination.getById(id) != null || destination.getByUniqueId(uuid) != null)
            throw new CommandException(Messages.UNDO_ID_CONFLICT, id);
        NPC npc = destination.createNPC(type, uuid, id, key.getString("name"));
        npc.load(key);
        selector.select(sender, npc);
    }
}
