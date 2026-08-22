package net.citizensnpcs.api.npc;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.citizensnpcs.api.CitizensAPI;
import net.minecraft.commands.CommandSourceStack;

/**
 * The default selector: one selection per sender, keyed by the sender entity, with the console and command blocks sharing
 * a single slot.
 * <p>
 * Selections are held by NPC UUID rather than by reference, so a selection survives the NPC despawning and respawning and
 * never keeps a removed NPC alive.
 */
public class SimpleNPCSelector implements NPCSelector {
    private final Map<UUID, UUID> selected = new ConcurrentHashMap<>();

    @Override
    public void deselect(CommandSourceStack sender) {
        selected.remove(keyOf(sender));
    }

    @Override
    public NPC getSelected(CommandSourceStack sender) {
        if (sender == null)
            return null;
        UUID id = selected.get(keyOf(sender));
        return id == null ? null : CitizensAPI.getNPCRegistry().getByUniqueIdGlobal(id);
    }

    @Override
    public void select(CommandSourceStack sender, NPC npc) {
        if (npc == null) {
            deselect(sender);
            return;
        }
        selected.put(keyOf(sender), npc.getUniqueId());
    }

    /** Anything without an entity - the console, a command block, a function - shares one selection. */
    private static UUID keyOf(CommandSourceStack sender) {
        return sender.getEntity() == null ? NON_ENTITY : sender.getEntity().getUUID();
    }

    private static final UUID NON_ENTITY = new UUID(0, 0);
}
