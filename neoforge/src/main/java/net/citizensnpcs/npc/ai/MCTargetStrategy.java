package net.citizensnpcs.npc.ai;

import net.citizensnpcs.api.ai.AttackStrategy;
import net.citizensnpcs.api.ai.EntityTarget;
import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.ai.PathStrategy;
import net.citizensnpcs.api.ai.PathfinderType;
import net.citizensnpcs.api.ai.TargetType;
import net.citizensnpcs.api.ai.event.CancelReason;
import net.citizensnpcs.api.astar.pathfinder.MinecraftBlockExaminer;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Follows a moving entity, optionally attacking it.
 * <p>
 * A moving target cannot be handled by planning once and walking the result, so this repaths every
 * {@link NavigatorParameters#updatePathRate()} ticks. Which navigator does the walking is decided per tick and can change
 * mid-chase: vanilla navigation when the pathfinder type says so, Citizens A* otherwise, and a straight line once the
 * target is within {@link NavigatorParameters#straightLineTargetingDistance()}, where repathing every tick costs more
 * than it buys.
 * <p>
 * Repeated failures are tolerated before giving up — a target that steps somewhere briefly unreachable should not end the
 * chase, so ten consecutive STUCK results are absorbed first.
 */
public class MCTargetStrategy implements PathStrategy, EntityTarget {
    private final boolean aggressive;
    private int attackDelay;
    private CancelReason cancelReason;
    private final Entity handle;
    private final NPC npc;
    private final NavigatorParameters parameters;
    private final Entity target;
    private TargetNavigator targetNavigator;
    private int updateCounter = -1;

    public MCTargetStrategy(NPC npc, Entity target, boolean aggressive, NavigatorParameters params) {
        this.npc = npc;
        this.parameters = params;
        this.handle = npc.getEntity();
        this.target = target;
        this.aggressive = aggressive;
        targetNavigator = handle instanceof Mob && params.pathfinderType() == PathfinderType.MINECRAFT
                ? new VanillaTargeter()
                : new AStarTargeter();
    }

    @Override
    public void clearCancelReason() {
        cancelReason = null;
    }

    @Override
    public CancelReason getCancelReason() {
        return cancelReason;
    }

    @Override
    public Location getCurrentDestination() {
        return targetNavigator.getCurrentDestination();
    }

    @Override
    public Iterable<Vec3> getPath() {
        return targetNavigator.getPath();
    }

    @Override
    public Entity getTarget() {
        return target;
    }

    @Override
    public Location getTargetAsLocation() {
        return Location.of(target);
    }

    @Override
    public TargetType getTargetType() {
        return TargetType.ENTITY;
    }

    @Override
    public boolean isAggressive() {
        return aggressive;
    }

    @Override
    public void stop() {
        targetNavigator.stop();
    }

    @Override
    public String toString() {
        return "MCTargetStrategy [target=" + target + "]";
    }

    @Override
    public boolean update() {
        if (target == null || target.isRemoved() || target instanceof LivingEntity living && !living.isAlive()) {
            cancelReason = CancelReason.TARGET_DIED;
            return true;
        }
        if (target.level() != handle.level()) {
            cancelReason = CancelReason.TARGET_MOVED_WORLD;
            return true;
        }
        if (cancelReason != null)
            return true;

        if (parameters.straightLineTargetingDistance() > 0 && !(targetNavigator instanceof StraightLineTargeter)) {
            targetNavigator = new StraightLineTargeter(targetNavigator);
        }
        if (!aggressive && parameters.withinMargin(Location.of(handle), Location.of(target))) {
            stop();
            return false;
        } else if (updateCounter == -1 || updateCounter++ > parameters.updatePathRate()) {
            targetNavigator.setPath();
            updateCounter = 0;
        }
        targetNavigator.update();

        npc.faceLocation(Location.of(target));
        if (aggressive && canAttack()) {
            attack();
            attackDelay = parameters.attackDelayTicks();
        }
        attackDelay--;
        return false;
    }

    /**
     * Attackable when the cooldown has expired, the two bounding boxes overlap vertically (so an NPC cannot hit
     * something far above or below it), the target is inside the attack range and nothing is in the way.
     */
    private boolean canAttack() {
        if (attackDelay > 0 || !(handle instanceof LivingEntity attacker) || !(target instanceof LivingEntity))
            return false;
        AABB handleBB = handle.getBoundingBox(), targetBB = target.getBoundingBox();
        return handleBB.maxY > targetBB.minY && handleBB.minY < targetBB.maxY
                && handle.position().distanceTo(target.position()) <= parameters.attackRange()
                && attacker.hasLineOfSight(target);
    }

    private void attack() {
        AttackStrategy strategy = parameters.attackStrategy();
        AttackStrategy fallback = parameters.defaultAttackStrategy();
        LivingEntity attacker = (LivingEntity) handle;
        LivingEntity victim = (LivingEntity) target;
        if (strategy != null && !strategy.handle(attacker, victim) && strategy != fallback && fallback != null) {
            fallback.handle(attacker, victim);
        }
    }

    /**
     * Drops the target location down onto solid ground for a walking NPC, so a target standing on a ledge or floating in
     * water does not make the NPC try to path into mid-air.
     */
    private Location groundedTargetLocation() {
        Location location = parameters.entityTargetLocationMapper().apply(target);
        if (location == null)
            throw new IllegalStateException("entity target location mapper should not return null");
        if (npc.isFlyable())
            return location;
        BlockPos pos = location.getBlockPos();
        int floor = location.getWorld().getMinBuildHeight();
        while (!MinecraftBlockExaminer.canStandOn(location.getWorld().getBlockState(pos.below()))) {
            pos = pos.below();
            if (pos.getY() <= floor)
                return location;
        }
        return new Location(location.getWorld(), location.getX(), pos.getY(), location.getZ(), location.getYaw(),
                location.getPitch());
    }

    /** Replans with the Citizens pathfinder each time the target has moved enough to matter. */
    private class AStarTargeter implements TargetNavigator {
        private int failureTimes;
        private PathStrategy strategy;

        @Override
        public Location getCurrentDestination() {
            return strategy == null ? null : strategy.getCurrentDestination();
        }

        @Override
        public Iterable<Vec3> getPath() {
            return strategy == null ? null : strategy.getPath();
        }

        @Override
        public void setPath() {
            Location location = groundedTargetLocation();
            if (strategy != null) {
                strategy.stop();
            }
            strategy = npc.isFlyable() ? new FlyingAStarNavigationStrategy(npc, location, parameters)
                    : new AStarNavigationStrategy(npc, location, parameters);
            strategy.update();
            CancelReason subReason = strategy.getCancelReason();
            if (subReason == CancelReason.STUCK) {
                // a target can step somewhere briefly unreachable; only a persistent failure ends the chase
                if (failureTimes++ > MAX_FAILURES) {
                    cancelReason = subReason;
                }
            } else {
                failureTimes = 0;
                cancelReason = subReason;
            }
        }

        @Override
        public void stop() {
            if (strategy != null) {
                strategy.stop();
            }
        }

        @Override
        public void update() {
            if (strategy != null) {
                strategy.update();
            }
        }
    }

    /** Hands the chase to the mob's own vanilla navigation, which already knows how to follow an entity. */
    private class VanillaTargeter implements TargetNavigator {
        @Override
        public Location getCurrentDestination() {
            Mob mob = (Mob) handle;
            net.minecraft.world.level.pathfinder.Path path = mob.getNavigation().getPath();
            if (path == null)
                return Location.of(target);
            return Location.fromBlockPosCentred((net.minecraft.server.level.ServerLevel) mob.level(),
                    path.getNextNodePos());
        }

        @Override
        public Iterable<Vec3> getPath() {
            return null;
        }

        @Override
        public void setPath() {
            Mob mob = (Mob) handle;
            // vanilla refuses to compute a path while onGround is false, which is the state of an NPC told to chase
            // something on the same tick it spawned; see MCNavigationStrategy for the same forcing
            mob.setOnGround(true);
            mob.getNavigation().moveTo(target, parameters.speedModifier());
        }

        @Override
        public void stop() {
            ((Mob) handle).getNavigation().stop();
        }

        @Override
        public void update() {
            // vanilla ticks the navigation itself inside Mob.serverAiStep
        }
    }

    /**
     * Walks straight at the target once it is close, falling back to whatever navigator was in use when it is not.
     */
    private class StraightLineTargeter implements TargetNavigator {
        private PathStrategy active;
        private final TargetNavigator fallback;

        private StraightLineTargeter(TargetNavigator navigator) {
            fallback = navigator;
        }

        @Override
        public Location getCurrentDestination() {
            return active == null ? fallback.getCurrentDestination() : active.getCurrentDestination();
        }

        @Override
        public Iterable<Vec3> getPath() {
            return active != null ? active.getPath() : fallback.getPath();
        }

        @Override
        public void setPath() {
            Location location = parameters.entityTargetLocationMapper().apply(target);
            if (location == null)
                throw new IllegalStateException("entity target location mapper should not return null");
            if (parameters.straightLineTargetingDistance() > 0
                    && npc.getStoredLocation().distance(location) <= parameters.straightLineTargetingDistance()) {
                active = new StraightLineNavigationStrategy(npc, target, parameters);
                return;
            }
            active = null;
            fallback.setPath();
        }

        @Override
        public void stop() {
            if (active != null) {
                active.stop();
            }
            fallback.stop();
        }

        @Override
        public void update() {
            if (active != null) {
                active.update();
            } else {
                fallback.update();
            }
        }
    }

    public static interface TargetNavigator {
        Location getCurrentDestination();

        Iterable<Vec3> getPath();

        void setPath();

        void stop();

        void update();
    }

    private static final int MAX_FAILURES = 10;
}
