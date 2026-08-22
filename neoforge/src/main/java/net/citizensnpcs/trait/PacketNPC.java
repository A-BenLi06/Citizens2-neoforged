package net.citizensnpcs.trait;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.event.SpawnReason;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.RemoveReason;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.EntityUtil;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.npc.EntityController;
import net.citizensnpcs.util.EntityPacketTracker;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Makes an NPC exist only as packets: its entity is never added to the world, so vanilla never ticks it, never saves it
 * and never runs collision or AI for it.
 * <p>
 * This is a performance option rather than a behaviour change — a stationary decorative NPC costs a spawn bundle per
 * viewer and nothing else. What a viewer sees is produced by {@link EntityPacketTracker}.
 * <p>
 * Two differences from upstream, both because this port's structure already covers what upstream needed extra machinery
 * for:
 * <ul>
 * <li>Upstream registers each viewer in a global {@code LocationLookup.PerPlayerMetadata} map keyed by NPC uuid, purely
 * to answer "is this player already linked?". The tracker knows its own linked set, so a field here answers it directly
 * and no {@code LocationLookup} is needed.</li>
 * <li>Upstream drives the entity's per-tick update from a {@code PlayerUpdateTask}, because a Bukkit player NPC that is
 * not in the world is never ticked. This port already sweeps every NPC once a tick in {@code Citizens.onServerTick},
 * which is where {@link #run()} is called from, so the task has no purpose here.</li>
 * </ul>
 */
@TraitName("packet")
public class PacketNPC extends Trait {
    private final Set<UUID> linkedPlayers = new HashSet<>();
    private EntityPacketTracker packetTracker;
    private boolean spawned;

    public PacketNPC() {
        super("packet");
    }

    public EntityPacketTracker getPacketTracker() {
        return packetTracker;
    }

    @Override
    public void onDespawn() {
        unlinkAll();
    }

    /**
     * @param reason
     *            {@link RemoveReason#REMOVAL} when the trait is being taken off the NPC, in which case the NPC needs a
     *            real world entity putting back; {@link RemoveReason#DESTROYED} when the whole NPC is going away, in which
     *            case respawning it would resurrect something the caller just deleted
     */
    @Override
    public void onRemove(RemoveReason reason) {
        unlinkAll();
        if (reason != RemoveReason.REMOVAL || npc.getStoredLocation() == null)
            return;

        // the entity never entered the world, so dropping the trait has to put a real one back
        Location at = npc.getStoredLocation();
        npc.despawn(DespawnReason.PENDING_RESPAWN);
        CitizensAPI.getScheduler().runTask(() -> {
            if (!npc.isSpawned()) {
                npc.spawn(at, SpawnReason.RESPAWN);
            }
        });
    }

    @Override
    public void onSpawn() {
        Entity entity = npc.getEntity();
        if (entity == null)
            return;
        packetTracker = new EntityPacketTracker(entity);
        linkedPlayers.clear();
        spawned = true;
    }

    @Override
    public void run() {
        if (!spawned || packetTracker == null || npc.getEntity() == null)
            return;
        int range = npc.data().get(NPC.Metadata.TRACKING_RANGE, 64);
        for (ServerPlayer nearby : EntityUtil.getNearbyVisiblePlayers(npc.getEntity(), range)) {
            if (linkedPlayers.add(nearby.getUUID())) {
                packetTracker.link(nearby);
            }
        }
        packetTracker.run();
    }

    /**
     * Wraps the NPC's entity controller so that spawning positions the entity and starts the packet tracker instead of
     * adding it to the level.
     */
    public EntityController wrap(EntityController controller) {
        return controller instanceof PacketController ? controller : new PacketController(controller);
    }

    private void unlinkAll() {
        if (packetTracker != null) {
            packetTracker.unlinkAll(null);
        }
        linkedPlayers.clear();
        spawned = false;
    }

    private class PacketController implements EntityController {
        private final EntityController base;

        PacketController(EntityController controller) {
            base = controller;
        }

        @Override
        public void create(Location at, NPC npc) {
            base.create(at, npc);
        }

        @Override
        public void die() {
            unlinkAll();
            base.die();
        }

        @Override
        public Entity getEntity() {
            return base.getEntity();
        }

        @Override
        public void remove() {
            unlinkAll();
            base.remove();
        }

        @Override
        public void spawn(Location at, Consumer<Boolean> callback) {
            Entity entity = base.getEntity();
            if (entity == null) {
                callback.accept(false);
                return;
            }
            // the whole point: position it, but never hand it to the level
            entity.moveTo(at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());
            callback.accept(true);
        }
    }
}
