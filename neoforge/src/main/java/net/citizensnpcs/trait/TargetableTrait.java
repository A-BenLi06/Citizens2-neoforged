package net.citizensnpcs.trait;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.EntityUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

/**
 * Whether mobs are allowed to target this NPC.
 * <p>
 * Unset means "targetable if the NPC is not protected", which is upstream's default. Enforced from
 * {@link net.citizensnpcs.EventListen} on the platform's change-target event.
 * <p>
 * The set of current targeters is tracked so that turning targetability off drops the aggro that is already in flight,
 * rather than only stopping new targeting. It is deliberately not persisted: aggro is a live relationship.
 */
@TraitName("targetable")
public class TargetableTrait extends Trait {
    @Persist
    private Boolean targetable;
    private Set<UUID> targeters;

    public TargetableTrait() {
        super("targetable");
    }

    /** Internal: called when something takes this NPC as its target. */
    public void addTargeter(UUID uuid) {
        if (targeters == null) {
            targeters = new HashSet<>();
        }
        targeters.add(uuid);
    }

    public void clearTargeters() {
        if (targeters == null)
            return;
        for (UUID uuid : targeters) {
            Entity entity = EntityUtil.getEntity(uuid);
            if (entity instanceof Mob mob && mob.isAlive()) {
                mob.setTarget(null);
            }
        }
        targeters = null;
    }

    public boolean isTargetable() {
        return targetable == null ? !npc.isProtected() : targetable;
    }

    @Override
    public void onDespawn() {
        clearTargeters();
    }

    /** Internal: called when something that was targeting this NPC targets something else. */
    public void removeTargeter(UUID uuid) {
        if (targeters != null) {
            targeters.remove(uuid);
        }
    }

    public void setTargetable(boolean targetable) {
        if (Boolean.valueOf(targetable).equals(this.targetable))
            return;
        this.targetable = targetable;
        if (!targetable) {
            clearTargeters();
        }
    }
}
