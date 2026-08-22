package net.citizensnpcs.trait;

import java.util.UUID;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.EntityUtil;
import net.citizensnpcs.api.util.Location;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;

/**
 * Keeps an NPC leashed across restarts — either to another entity, or to a fence post.
 * <p>
 * The two are stored separately because a fence knot is not a durable entity: it is recreated at its block position on
 * spawn, whereas an entity holder is looked up by UUID. Leashing is a {@link Leashable} interface in 1.21.1 rather than
 * something only mobs can do.
 */
@TraitName("leashedtrait")
public class LeashedTrait extends Trait {
    @Persist
    private Location leashedLocation;
    @Persist
    private UUID leashedTo;

    public LeashedTrait() {
        super("leashedtrait");
    }

    public Location getLeashedLocation() {
        return leashedLocation;
    }

    public UUID getLeashedTo() {
        return leashedTo;
    }

    @Override
    public void onSpawn() {
        if (!(npc.getEntity() instanceof Leashable leashable))
            return;
        if (leashedTo != null) {
            Entity holder = EntityUtil.getEntity(leashedTo);
            if (holder != null) {
                leashable.setLeashedTo(holder, true);
            }
        }
        if (leashedLocation != null && leashedLocation.getWorld() != null) {
            LeashFenceKnotEntity knot = LeashFenceKnotEntity.getOrCreateKnot(leashedLocation.getWorld(),
                    BlockPos.containing(leashedLocation.getX(), leashedLocation.getY(), leashedLocation.getZ()));
            if (knot != null) {
                leashable.setLeashedTo(knot, true);
            }
        }
    }

    /**
     * Records where the leash currently ends, so a player re-leashing the NPC by hand is persisted. Skipped while the
     * NPC's leash is protected, which is the point of that flag.
     */
    @Override
    public void run() {
        if (!(npc.getEntity() instanceof Leashable leashable)
                || npc.data().get(NPC.Metadata.LEASH_PROTECTED, npc.isProtected()))
            return;
        if (!leashable.isLeashed())
            return;
        Entity holder = leashable.getLeashHolder();
        if (holder == null)
            return;
        if (holder instanceof LeashFenceKnotEntity) {
            Location at = Location.of(holder);
            if (leashedLocation == null || leashedLocation.getWorld() != at.getWorld()
                    || at.distanceSquared(leashedLocation) > 1) {
                leashedLocation = at;
            }
            leashedTo = null;
        } else {
            leashedLocation = null;
            leashedTo = holder.getUUID();
        }
    }

    public void setLeashedLocation(Location location) {
        leashedLocation = location;
        leashedTo = null;
    }

    public void setLeashedTo(UUID uuid) {
        leashedTo = uuid;
        leashedLocation = null;
    }
}
