package net.citizensnpcs.npc;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.npc.ai.NPCHolder;
import net.minecraft.world.entity.Entity;

/**
 * Maps a live entity back to the NPC that owns it.
 * <p>
 * Upstream can make every entity class {@code implement NPCHolder}, because it creates a subclass per mob type anyway.
 * The port reuses vanilla entity classes untouched (see {@link MobEntityController}), so the association is held here
 * instead. The map is weak-keyed: an entity that is garbage collected drops out on its own, and a removed NPC is
 * unlinked explicitly.
 * <p>
 * The bespoke entity classes that <i>do</i> subclass — the fake player — implement {@link NPCHolder} directly, and
 * {@link #lookup} checks that first, so both routes work.
 */
public class NPCRegistries {
    private NPCRegistries() {
    }

    public static void link(Entity entity, NPC npc) {
        ENTITY_TO_NPC.put(entity, npc);
    }

    /**
     * @return the NPC backing this entity, or null if it is not one
     */
    public static NPC lookup(Entity entity) {
        if (entity == null)
            return null;
        if (entity instanceof NPCHolder)
            return ((NPCHolder) entity).getNPC();
        return ENTITY_TO_NPC.get(entity);
    }

    public static void unlink(Entity entity) {
        if (entity != null) {
            ENTITY_TO_NPC.remove(entity);
        }
    }

    private static final Map<Entity, NPC> ENTITY_TO_NPC = Collections.synchronizedMap(new WeakHashMap<>());
}
