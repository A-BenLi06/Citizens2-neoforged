package net.citizensnpcs.npc.ai;

import java.util.ArrayList;
import java.util.List;

import net.citizensnpcs.api.ai.AbstractPathStrategy;
import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.ai.TargetType;
import net.citizensnpcs.api.ai.event.CancelReason;
import net.citizensnpcs.api.astar.pathfinder.FlyingBlockExaminer;
import net.citizensnpcs.api.astar.pathfinder.MinecraftBlockExaminer;
import net.citizensnpcs.api.astar.pathfinder.Path;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.npc.ai.AStarNavigationStrategy.AStarPlanner;
import net.citizensnpcs.npc.ai.AStarNavigationStrategy.PathPlanner;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * The flying counterpart of {@link AStarNavigationStrategy}: the same A* search, but through open air rather than over a
 * walkable surface, and driven by easing the entity velocity instead of by a move control.
 * <p>
 * The search itself differs only in its examiner — {@link FlyingBlockExaminer} replaces the neighbour expansion so that
 * all 26 surrounding cells are candidates and no floor is required. The incremental planner is shared with
 * {@link AStarNavigationStrategy}, so the per-tick node budget applies here too.
 */
public class FlyingAStarNavigationStrategy extends AbstractPathStrategy {
    private final NPC npc;
    private final NavigatorParameters params;
    private Path plan;
    private PathPlanner planner;
    private final Location target;
    private Vec3 vector;

    public FlyingAStarNavigationStrategy(NPC npc, Iterable<Vec3> path, NavigatorParameters params) {
        super(TargetType.LOCATION);
        this.params = params;
        this.npc = npc;
        List<Vec3> list = new ArrayList<>();
        path.forEach(list::add);
        if (list.isEmpty())
            throw new IllegalArgumentException("a path needs at least one point");
        Vec3 last = list.get(list.size() - 1);
        target = new Location(npc.getStoredLocation().getWorld(), last.x, last.y, last.z);
        setPlan(new Path(list));
    }

    public FlyingAStarNavigationStrategy(NPC npc, Location dest, NavigatorParameters params) {
        super(TargetType.LOCATION);
        this.params = params;
        this.npc = npc;
        target = dest;
    }

    @Override
    public Location getCurrentDestination() {
        return vector != null ? new Location(target.getWorld(), vector.x, vector.y, vector.z) : target.clone();
    }

    @Override
    public Iterable<Vec3> getPath() {
        return plan == null ? null : plan.getPath();
    }

    @Override
    public Location getTargetAsLocation() {
        return target;
    }

    @Override
    public void stop() {
        if (planner != null) {
            planner.cancel();
            planner = null;
        }
        plan = null;
    }

    @Override
    public boolean update() {
        if (plan == null && planner == null && !startPlanning())
            return true;
        if (planner != null) {
            CancelReason reason = planner.tick();
            if (reason != null) {
                setCancelReason(reason);
                return true;
            }
            if (planner.getPath() == null)
                return false;
            setPlan(planner.getPath());
            planner = null;
        }
        if (getCancelReason() != null || plan == null || plan.isComplete() || !npc.isSpawned())
            return true;
        Entity entity = npc.getEntity();
        Location current = Location.of(entity);
        if (params.withinMargin(new Vec3(current.getX(), current.getY(), current.getZ()), vector)) {
            plan.update(npc);
            if (plan.isComplete())
                return true;
            vector = plan.getCurrentVector();
        }
        startGlidingIfWearingElytra(entity, current);
        flyTowards(entity, current);
        plan.run(npc);
        return false;
    }

    private void setPlan(Path path) {
        plan = path;
        if (plan == null || plan.isComplete()) {
            setCancelReason(CancelReason.STUCK);
            return;
        }
        vector = plan.getCurrentVector();
    }

    private boolean startPlanning() {
        Location start = npc.getStoredLocation();
        if (start == null || start.getWorld() == null || target.getWorld() != start.getWorld()) {
            setCancelReason(CancelReason.STUCK);
            return false;
        }
        if (!params.hasExaminer(FlyingBlockExaminer.class)) {
            params.examiner(new FlyingBlockExaminer());
        }
        planner = new AStarPlanner(params, start, target);
        return true;
    }

    /**
     * Eases the velocity towards the next waypoint. The ender dragon is exempt from the rotation and lift, because its
     * yaw is driven by its own body animation and overriding it makes the whole model spin.
     */
    private void flyTowards(Entity entity, Location current) {
        double dx = vector.x - current.getX();
        double dy = vector.y - current.getY();
        double dz = vector.z - current.getZ();

        Vec3 velocity = entity.getDeltaMovement();
        double motX = velocity.x + (Math.signum(dx) * 0.5D - velocity.x) * 0.1;
        double motY = velocity.y + (Math.signum(dy) - velocity.y) * 0.1;
        double motZ = velocity.z + (Math.signum(dz) * 0.5D - velocity.z) * 0.1;
        entity.setDeltaMovement(new Vec3(motX, motY, motZ).scale(params.speed()));
        entity.hasImpulse = true;

        if (entity.getType() == EntityType.ENDER_DRAGON)
            return;
        if (entity instanceof LivingEntity living) {
            living.yya = 0.5F;
        }
        npc.faceLocation(new Location(current.getWorld(), vector.x, vector.y, vector.z));
    }

    /**
     * Puts a player NPC wearing an elytra into the gliding pose while it is off the ground, so the client draws it
     * flying rather than standing upright in mid-air. Upstream sends this as {@code PlayerAnimation.START_ELYTRA}.
     */
    private void startGlidingIfWearingElytra(Entity entity, Location current) {
        if (entity.getType() != EntityType.PLAYER || !(entity instanceof LivingEntity living))
            return;
        if (!living.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA) || living.isFallFlying())
            return;
        if (MinecraftBlockExaminer.canStandOn(
                current.getWorld().getBlockState(entity.blockPosition().below())))
            return;
        living.setSharedFlag(FALL_FLYING_FLAG, true);
    }

    private static final int FALL_FLYING_FLAG = 7;
}
