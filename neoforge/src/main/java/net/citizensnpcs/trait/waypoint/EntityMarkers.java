package net.citizensnpcs.trait.waypoint;

import java.util.HashMap;
import java.util.Map;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.SpawnReason;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.util.Location;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Visible markers for the waypoints being edited: one throwaway NPC per waypoint, from the temporary registry so nothing
 * is written to disk.
 * <p>
 * Upstream picks its marker entity through a version-fallback list ({@code SHULKER_BULLET}, else {@code LEASH_KNOT}, else
 * {@code LEASH_HITCH}). A shulker bullet exists in 1.21.1 and is the intended choice, so it is named directly.
 *
 * @param <T>
 *            whatever the caller wants to key its markers by
 */
public class EntityMarkers<T> {
    private final Map<T, Entity> markers = new HashMap<>();
    private final NPCRegistry registry = CitizensAPI.getTemporaryNPCRegistry();
    private final EntityType<?> type;

    public EntityMarkers() {
        this(EntityType.SHULKER_BULLET);
    }

    public EntityMarkers(EntityType<?> type) {
        this.type = type;
    }

    public Entity createMarker(T marker, Location at) {
        Entity entity = spawnMarker(at);
        if (entity == null)
            return null;
        markers.put(marker, entity);
        return entity;
    }

    public void destroyMarkers() {
        for (Entity entity : markers.values()) {
            destroy(entity);
        }
        markers.clear();
    }

    public void removeMarker(T marker) {
        destroy(markers.remove(marker));
    }

    /** Spawns a marker without keeping it, for a one-off highlight. */
    public Entity spawnMarker(Location at) {
        NPC npc = registry.createNPC(type, "");
        npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, false);
        npc.spawn(at.clone().add(0.5, 0, 0.5), SpawnReason.CREATE);
        return npc.getEntity();
    }

    private void destroy(Entity entity) {
        if (entity == null)
            return;
        NPC npc = CitizensAPI.getNPCRegistry().getNPC(entity);
        if (npc == null) {
            npc = registry.getNPC(entity);
        }
        if (npc != null) {
            npc.destroy();
        } else {
            entity.discard();
        }
    }
}
