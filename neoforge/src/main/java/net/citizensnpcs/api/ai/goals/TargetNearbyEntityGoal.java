package net.citizensnpcs.api.ai.goals;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.event.CancelReason;
import net.citizensnpcs.api.ai.tree.Behavior;
import net.citizensnpcs.api.ai.tree.BehaviorStatus;
import net.citizensnpcs.api.npc.NPC;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * A sample {@link Behavior} that picks the nearest entity within a radius that passes a filter and follows it with
 * {@link Navigator#setTarget(Entity, boolean)}.
 * <p>
 * Upstream takes {@code Function<Entity, Boolean>} for the filter and a {@code Set<EntityType>} of enum constants for the
 * type shortcut; here the filter is a {@link Predicate} and the set holds registry objects, so entity types added by other
 * mods work in it too.
 */
public class TargetNearbyEntityGoal implements Behavior {
    private final boolean aggressive;
    private final Predicate<Entity> filter;
    private boolean finished;
    private final NPC npc;
    private final double radius;
    private CancelReason reason;
    private Entity target;

    private TargetNearbyEntityGoal(NPC npc, boolean aggressive, double radius, Predicate<Entity> filter) {
        this.npc = npc;
        this.filter = filter;
        this.aggressive = aggressive;
        this.radius = radius;
    }

    @Override
    public void reset() {
        npc.getNavigator().cancelNavigation();
        target = null;
        finished = false;
        reason = null;
    }

    @Override
    public BehaviorStatus run() {
        if (finished)
            return reason == null ? BehaviorStatus.SUCCESS : BehaviorStatus.FAILURE;
        return BehaviorStatus.RUNNING;
    }

    @Override
    public boolean shouldExecute() {
        if (!npc.isSpawned())
            return false;
        Entity self = npc.getEntity();
        List<Entity> nearby = self.level().getEntities(self, self.getBoundingBox().inflate(radius));
        nearby.sort((a, b) -> Double.compare(a.distanceToSqr(self), b.distanceToSqr(self)));
        target = null;
        for (Entity entity : nearby) {
            if (filter.test(entity)) {
                target = entity;
                break;
            }
        }
        if (target == null)
            return false;
        npc.getNavigator().setTarget(target, aggressive);
        npc.getNavigator().getLocalParameters().addSingleUseCallback(cancelReason -> {
            reason = cancelReason;
            finished = true;
        });
        return true;
    }

    public static class Builder {
        private boolean aggressive;
        private Predicate<Entity> filter = e -> false;
        private final NPC npc;
        private double radius = 10D;

        public Builder(NPC npc) {
            this.npc = npc;
        }

        public Builder aggressive(boolean aggressive) {
            this.aggressive = aggressive;
            return this;
        }

        public TargetNearbyEntityGoal build() {
            return new TargetNearbyEntityGoal(npc, aggressive, radius, filter);
        }

        public Builder radius(double radius) {
            this.radius = radius;
            return this;
        }

        public Builder targetFilter(Predicate<Entity> filter) {
            this.filter = filter;
            return this;
        }

        public Builder targets(Set<EntityType<?>> targetTypes) {
            this.filter = e -> targetTypes.contains(e.getType());
            return this;
        }
    }

    public static Builder builder(NPC npc) {
        return new Builder(npc);
    }
}
