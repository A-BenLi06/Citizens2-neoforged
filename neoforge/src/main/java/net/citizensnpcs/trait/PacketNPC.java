package net.citizensnpcs.trait;

import java.util.List;
import java.util.function.Consumer;

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
import net.minecraft.server.level.ServerPlayer;
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
    private EntityPacketTracker packetTracker;
    private boolean spawned;

    public PacketNPC() {
        super("packet");
    }

    public EntityPacketTracker getPacketTracker() {
        return packetTracker;
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

    /** Trait replacement calls the no-argument hook; only the replacement may keep tracking the entity. */
    @Override
    public void onRemove() {
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
        if (reason != RemoveReason.REMOVAL || !spawned || npc.getEntity() == null) {
            unlinkAll();
            return;
        }

        // Clear the old controller before unlinkAll marks its virtual entity removed. The trait is already detached.
        Location at = npc.getStoredLocation();
        npc.despawn(DespawnReason.REMOVAL);
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
    }

    @Override
    public void run() {
        if (!spawned || packetTracker == null || npc.getEntity() == null)
            return;
        Entity entity = npc.getEntity();
        var box = trackingBox(entity);
        // Spectators and invisible players can see entities. This is viewer eligibility, not NPC target selection.
        List<ServerPlayer> viewers = entity.getServer().getPlayerList().getPlayers().stream()
                .filter(player -> isViewerEligible(entity, box, player)).toList();
        for (ServerPlayer linked : packetTracker.getLinked()) {
            if (viewers.stream().noneMatch(player -> player == linked)) packetTracker.unlink(linked);
        }
        for (ServerPlayer viewer : viewers) packetTracker.link(viewer);
        packetTracker.run();
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
        if (packetTracker != null) {
            packetTracker.unlinkAll(null);
        }
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
