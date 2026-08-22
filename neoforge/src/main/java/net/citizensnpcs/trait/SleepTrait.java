package net.citizensnpcs.trait;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.Location;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;

/**
 * Puts an NPC to sleep at a position.
 * <p>
 * Upstream needs three code paths — Bukkit's player sleep call, an NMS fallback for a non-bed target, and a separate
 * villager call. Vanilla's {@link LivingEntity#startSleeping(BlockPos)} takes any position and works for every living
 * entity, but only sticks on a real bed: {@code LivingEntity.tick} wakes any sleeper whose position holds no bed, every
 * tick. So there are two outcomes rather than one, and which applies depends on the block:
 * <ul>
 * <li><b>A bed.</b> Real sleep — the position is persisted, the bed is marked occupied, and the NPC is laid in it.
 * <li><b>Anything else.</b> Only the sleeping <em>pose</em> is achievable, applied through {@link EntityPoseTrait} so that
 * it is re-asserted every tick and survives a respawn. Trying to sleep properly here would just be undone a tick later.
 * </ul>
 * The state is read back off the entity rather than tracked in a flag, so if something else wakes the NPC the trait puts
 * it back to sleep instead of silently giving up.
 */
@TraitName("sleeptrait")
public class SleepTrait extends Trait {
    @Persist
    private Location at;

    public SleepTrait() {
        super("sleeptrait");
    }

    public Location getSleepingAt() {
        return at;
    }

    public boolean isSleeping() {
        return npc.getEntity() instanceof LivingEntity living && living.isSleeping();
    }

    @Override
    public void onDespawn() {
        npc.getOrAddTrait(EntityPoseTrait.class).setPose(null);
    }

    @Override
    public void run() {
        if (!npc.isSpawned() || !(npc.getEntity() instanceof LivingEntity living))
            return;
        if (at == null || at.getWorld() == null) {
            if (living.isSleeping()) {
                wakeup();
            }
            return;
        }
        BlockPos pos = BlockPos.containing(at.getX(), at.getY(), at.getZ());
        if (at.getWorld().getBlockState(pos).isBed(at.getWorld(), pos, living)) {
            if (!living.isSleeping()) {
                living.startSleeping(pos);
            }
            return;
        }
        // no bed: the pose is the whole of what can be shown, and EntityPoseTrait keeps re-applying it
        EntityPoseTrait pose = npc.getOrAddTrait(EntityPoseTrait.class);
        if (pose.getPose() != Pose.SLEEPING) {
            pose.setPose(Pose.SLEEPING);
        }
    }

    public void setSleeping(Location at) {
        this.at = at;
        if (at == null) {
            wakeup();
        }
    }

    public void wakeup() {
        at = null;
        npc.getOrAddTrait(EntityPoseTrait.class).setPose(null);
        if (npc.getEntity() instanceof LivingEntity living && living.isSleeping()) {
            living.stopSleeping();
        }
    }
}
