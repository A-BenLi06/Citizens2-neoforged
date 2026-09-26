package net.citizensnpcs.trait;

import java.util.List;
import java.util.function.Consumer;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.event.SpawnReason;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.RemoveReason;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.npc.EntityController;
import net.citizensnpcs.util.EntityPacketTracker;
import net.citizensnpcs.util.NPCVisibility;
import net.citizensnpcs.util.PacketMounts;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * Makes an NPC exist only as packets: its entity is never added to the world, so vanilla never ticks it, never saves it
 * and never runs collision or AI for it.
 * <p>
 * This is useful for decorative NPCs. What a viewer sees is produced by {@link EntityPacketTracker}; Citizens still
 * updates its traits and reconciles viewers, while the entity does not receive vanilla world ticks.
 * <p>
 * Two differences from upstream, both because this port's structure already covers what upstream needed extra machinery
 * for:
 * <ul>
 * <li>The tracker owns viewer membership. Every update reconciles the live player objects, range, dimension and NPC
 * visibility, so a respawn/relogin cannot leave a stale player object linked under the same UUID.</li>
 * <li>Upstream drives the entity's per-tick update from a {@code PlayerUpdateTask}, because a Bukkit player NPC that is
 * not in the world is never ticked. This port already sweeps every NPC once a tick in {@code Citizens.onServerTick},
 * which is where {@link #run()} is called from, so the task has no purpose here.</li>
 * </ul>
 */
@TraitName("packet")
public class PacketNPC extends Trait {
    // Accessed only on the server thread, like NPC lifecycle and incoming interaction handling.
    private static final Int2ObjectMap<PacketNPC> INTERACTION_TARGETS = new Int2ObjectOpenHashMap<>();
    private EntityPacketTracker packetTracker;
    private Integer registeredEntityId;
    private boolean spawned;

    public PacketNPC() {
        super("packet");
    }

    public EntityPacketTracker getPacketTracker() {
        return packetTracker;
    }

    /** Refresh current viewers without replacing the virtual entity or its server-side mount relations. */
    public void refreshPairing() {
        Entity entity = npc.getEntity();
        EntityPacketTracker current = packetTracker;
        if (entity == null || activeTrait(entity.getId()) != this) return;
        for (ServerPlayer viewer : current.getLinked()) {
            if (activeTrait(entity.getId()) != this || packetTracker != current) return;
            if (!current.isLinked(viewer)) continue;
            current.unlink(viewer);
            if (activeTrait(entity.getId()) == this && packetTracker == current) current.link(viewer);
        }
    }

    /** Resolve only a live virtual entity already paired to this current, eligible player. */
    public static Entity getInteractionTarget(int entityId, ServerPlayer player) {
        PacketNPC trait = activeTrait(entityId);
        return trait != null && trait.packetTracker.isLinked(player) && trait.isViewerEligible(player)
                ? trait.npc.getEntity() : null;
    }

    public static boolean isPacketEntity(Entity entity) {
        PacketNPC trait = activeTrait(entity.getId());
        return trait != null && trait.npc.getEntity() == entity;
    }

    /** Snapshot virtual roots absent from native level iteration, for traversal of their real passengers. */
    public static List<Entity> getRootEntities(ServerLevel level) {
        return INTERACTION_TARGETS.int2ObjectEntrySet().stream()
                .filter(entry -> activeTrait(entry.getIntKey()) == entry.getValue())
                .map(entry -> entry.getValue().npc.getEntity())
                .filter(entity -> entity.level() == level && entity.getVehicle() == null).toList();
    }

    private static PacketNPC activeTrait(int entityId) {
        PacketNPC trait = INTERACTION_TARGETS.get(entityId);
        if (trait == null || !trait.spawned || trait.packetTracker == null)
            return null;
        NPC owner = trait.npc;
        Entity entity = owner.getEntity();
        if (!owner.isSpawned() || owner.getTraitNullable(PacketNPC.class) != trait
                || owner.getOwningRegistry() == null
                || owner.getOwningRegistry().getByUniqueId(owner.getUniqueId()) != owner
                || entity == null || entity.getId() != entityId || entity.isRemoved())
            return null;
        return trait;
    }

    /** Live eligibility for supplemental per-viewer updates between reconciliation ticks. */
    public boolean isViewerEligible(ServerPlayer player) {
        Entity entity = npc.getEntity();
        return entity != null && isViewerEligible(entity, trackingBox(entity), player);
    }

    private AABB trackingBox(Entity entity) {
        return entity.getBoundingBox().inflate(Math.max(0, npc.data().get(NPC.Metadata.TRACKING_RANGE, 64)));
    }

    private boolean isViewerEligible(Entity entity, AABB box, ServerPlayer player) {
        return player != entity && player.connection != null && !player.hasDisconnected()
                && entity.getServer().getPlayerList().getPlayer(player.getUUID()) == player
                && player.level() == entity.level() && box.intersects(player.getBoundingBox())
                && !CitizensAPI.getNPCRegistry().isNPC(player) && NPCVisibility.isVisible(npc, player);
    }

    @Override
    public void onDespawn() {
        unlinkAll();
    }

    /** Releases old-world viewers before the owning NPC transfers its native entity. */
    public void prepareTeleport() {
        unlinkAll(false);
    }

    /** Trait replacement calls the no-argument hook; only the replacement may keep tracking the entity. */
    @Override
    public void onRemove() {
        unlinkAll(false);
    }

    /**
     * @param reason
     *            {@link RemoveReason#REMOVAL} when the trait is being taken off the NPC, in which case the NPC needs a
     *            real world entity putting back; {@link RemoveReason#DESTROYED} when the whole NPC is going away, in which
     *            case respawning it would resurrect something the caller just deleted
     */
    @Override
    public void onRemove(RemoveReason reason) {
        if (reason != RemoveReason.REMOVAL || !spawned || npc.getEntity() == null) {
            unlinkAll();
            return;
        }

        // Clear the old controller before unlinkAll marks its virtual entity removed. The trait is already detached.
        Location at = npc.getStoredLocation();
        npc.despawn(DespawnReason.REMOVAL);
        // After trait replacement the controller may still belong to the retired trait. This detached instance must
        // release its own tracker too; it no longer receives the NPC's onDespawn callback.
        unlinkAll();
        CitizensAPI.getScheduler().runTask(() -> {
            if (at != null && npc.getOwningRegistry().getByUniqueId(npc.getUniqueId()) == npc && !npc.isSpawned()) {
                npc.spawn(at, SpawnReason.RESPAWN);
            }
        });
    }

    @Override
    public void onSpawn() {
        Entity entity = npc.getEntity();
        if (entity == null)
            return;
        if (entity.level().getEntity(entity.getId()) == entity) {
            // A live API attachment must first remove the real entity; never track it through two transports.
            CitizensAPI.getScheduler().runTask(() -> {
                if (npc.getTraitNullable(PacketNPC.class) == this && npc.getEntity() == entity && npc.isSpawned())
                    npc.setEntityType(entity.getType());
            });
            return;
        }
        packetTracker = new EntityPacketTracker(entity);
        spawned = true;
        registeredEntityId = entity.getId();
        INTERACTION_TARGETS.put(entity.getId(), this);
    }

    @Override
    public void run() {
        if (!spawned || packetTracker == null || npc.getEntity() == null)
            return;
        Entity entity = npc.getEntity();
        PacketMounts.position(entity);
        var box = trackingBox(entity);
        // Spectators and invisible players can see entities. This is viewer eligibility, not NPC target selection.
        List<ServerPlayer> viewers = entity.getServer().getPlayerList().getPlayers().stream()
                .filter(player -> isViewerEligible(entity, box, player)).toList();
        for (ServerPlayer linked : packetTracker.getLinked()) {
            if (viewers.stream().noneMatch(player -> player == linked)) packetTracker.unlink(linked);
        }
        EntityPacketTracker current = packetTracker;
        for (ServerPlayer viewer : viewers) {
            // Admission listeners can remove or replace this NPC/trait during any individual link.
            if (!spawned || packetTracker != current || npc.getEntity() != entity
                    || npc.getTraitNullable(PacketNPC.class) != this) return;
            current.link(viewer);
        }
        if (spawned && packetTracker == current && npc.getEntity() == entity) current.run();
    }

    /**
     * Wraps the NPC's entity controller so that spawning positions the entity and starts the packet tracker instead of
     * adding it to the level.
     */
    public EntityController wrap(EntityController controller) {
        return new PacketController(unwrap(controller));
    }

    /** Strip a retired trait's wrapper before selecting the current spawn transport. */
    public static EntityController unwrap(EntityController controller) {
        while (controller instanceof PacketController packet) controller = packet.base;
        return controller;
    }

    private void unlinkAll() {
        unlinkAll(true);
    }

    private void unlinkAll(boolean discard) {
        if (registeredEntityId != null) {
            INTERACTION_TARGETS.remove(registeredEntityId.intValue(), this);
            registeredEntityId = null;
        }
        EntityPacketTracker previous = spawned ? packetTracker : null;
        spawned = false;
        // Retire this tracker before callbacks can replace it or start another transfer.
        if (previous != null) {
            if (discard) previous.unlinkAll(null);
            else previous.unpairAll(null);
        }
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
        public void replaceEntity(Entity entity) {
            base.replaceEntity(entity);
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
