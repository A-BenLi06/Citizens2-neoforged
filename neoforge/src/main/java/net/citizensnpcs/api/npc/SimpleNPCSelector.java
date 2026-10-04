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
 * Selections retain an NPC UUID and logical registry identity, so ordinary reloads remain usable without selecting
 * another registry's NPC with the same UUID.
 */
public class SimpleNPCSelector implements NPCSelector {
    private final Map<UUID, Selection> selected = new ConcurrentHashMap<>();

    /** Release sender selections and anonymous-registry references when the server stops. */
    public void clear() {
        selected.clear();
    }

    @Override
    public void deselect(CommandSourceStack sender) {
        selected.remove(keyOf(sender));
    }

    @Override
    public NPC getSelected(CommandSourceStack sender) {
        if (sender == null)
            return null;
        Selection choice = selected.get(keyOf(sender));
        if (choice == null) return null;
        NPCRegistry registry = choice.registry();
        if (registry == null) return null;
        NPC npc = registry.getByUniqueId(choice.id);
        return npc != null && npc.getOwningRegistry() == registry && registry.getById(npc.getId()) == npc ? npc : null;
    }

    @Override
    public void select(CommandSourceStack sender, NPC npc) {
        if (npc == null) {
            deselect(sender);
            return;
        }
        NPCRegistry registry = npc.getOwningRegistry();
        if (registry == null || registry.getByUniqueId(npc.getUniqueId()) != npc || registry.getById(npc.getId()) != npc) return;
        selected.put(keyOf(sender), new Selection(npc));
    }

    /** Keep logical registry identity across reload without resolving into an unrelated registry. */
    private static final class Selection {
        final UUID id;
        final String name;
        final RegistryKind kind;
        final NPCRegistry anonymous;
        Selection(NPC npc) {
            id = npc.getUniqueId();
            NPCRegistry registry = npc.getOwningRegistry();
            name = registry.getName();
            kind = registry == CitizensAPI.getNPCRegistry() ? RegistryKind.DEFAULT
                    : registry == CitizensAPI.getTemporaryNPCRegistry() ? RegistryKind.TEMPORARY
                    : CitizensAPI.getNamedNPCRegistry(name) == registry ? RegistryKind.NAMED : RegistryKind.ANONYMOUS;
            anonymous = kind == RegistryKind.ANONYMOUS ? registry : null;
        }
        NPCRegistry registry() {
            if (kind == RegistryKind.DEFAULT) return CitizensAPI.getNPCRegistry();
            if (kind == RegistryKind.TEMPORARY) return CitizensAPI.getTemporaryNPCRegistry();
            if (kind == RegistryKind.NAMED) return CitizensAPI.getNamedNPCRegistry(name);
            for (NPCRegistry registry : CitizensAPI.getNPCRegistries()) if (registry == anonymous) return registry;
            return null;
        }
    }

    private enum RegistryKind { DEFAULT, TEMPORARY, NAMED, ANONYMOUS }

    /** Anything without an entity - the console, a command block, a function - shares one selection. */
    private static UUID keyOf(CommandSourceStack sender) {
        return sender.getEntity() == null ? NON_ENTITY : sender.getEntity().getUUID();
    }

    private static final UUID NON_ENTITY = new UUID(0, 0);
}
