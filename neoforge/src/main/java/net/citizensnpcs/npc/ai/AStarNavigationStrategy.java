package net.citizensnpcs.npc.ai;

import java.util.ArrayList;
import java.util.List;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.ai.AbstractPathStrategy;
import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.ai.PathfinderType;
import net.citizensnpcs.api.ai.TargetType;
import net.citizensnpcs.api.ai.event.CancelReason;
import net.citizensnpcs.api.astar.AStarMachine;
import net.citizensnpcs.api.astar.AStarMachine.AStarState;
import net.citizensnpcs.api.astar.pathfinder.BlockExaminer;
import net.citizensnpcs.api.astar.pathfinder.BlockSource;
import net.citizensnpcs.api.astar.pathfinder.LevelBlockSource;
import net.citizensnpcs.api.astar.pathfinder.MinecraftBlockExaminer;
import net.citizensnpcs.api.astar.pathfinder.Path;
import net.citizensnpcs.api.astar.pathfinder.PathPoint;
import net.citizensnpcs.api.astar.pathfinder.SwimmingNeighbourExaminer;
import net.citizensnpcs.api.astar.pathfinder.VectorGoal;
import net.citizensnpcs.api.astar.pathfinder.VectorNode;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Walks a path worked out by Citizens' own A*, rather than by vanilla navigation.
 * <p>
 * This is what makes NPC movement configurable: the route comes from the {@link BlockExaminer}s on the
 * {@link NavigatorParameters}, so water avoidance, door opening, flight and any custom rule apply. Vanilla navigation
 * has none of those knobs, which is why {@link MCNavigationStrategy} is only the fallback.
 * <p>
 * The search is spread across ticks, {@code npc.pathfinding.citizens.blocks-per-tick} nodes at a time, exactly as
 * upstream does — a full 1024-node search resolved inside a single tick is a visible server hitch when several NPCs
 * repath at once. While the search is still running {@link #update()} reports "not finished" without moving the NPC.
 * <p>
 * {@link PathfinderType#CITIZENS_ASYNC} maps onto the same incremental search rather than onto a worker pool. Upstream
 * goes off-thread because Bukkit block reads from the server thread are slow, and pays for it with a captured chunk
 * snapshot that can go stale; here blocks are read straight from the live
 * {@link net.minecraft.server.level.ServerLevel}, which is both cheap and always current but is not safe to touch from
 * another thread. Slicing the same search across ticks gets the benefit that mattered — no tick spike — without either
 * the snapshot or the thread-safety hazard.
 */
public class AStarNavigationStrategy extends AbstractPathStrategy {
    private Location current;
    private final Location destination;
    private final NPC npc;
    private final NavigatorParameters params;
    private Path plan;
    private PathPlanner planner;

    /** A path that is already known — waypoints, or anything else that supplies its own route. */
    public AStarNavigationStrategy(NPC npc, Iterable<Vec3> path, NavigatorParameters params) {
        super(TargetType.LOCATION);
        this.params = params;
        this.npc = npc;
        List<Vec3> list = new ArrayList<>();
        path.forEach(list::add);
        if (list.isEmpty())
            throw new IllegalArgumentException("a path needs at least one point");
        Vec3 last = list.get(list.size() - 1);
        destination = new Location(npc.getStoredLocation().getWorld(), last.x, last.y, last.z);
        plan = new Path(list);
    }

    public AStarNavigationStrategy(NPC npc, Location dest, NavigatorParameters params) {
        super(TargetType.LOCATION);
        this.params = params;
        this.npc = npc;
        if (dest.getWorld() != null && !MinecraftBlockExaminer.canStandIn(
                dest.getWorld().getBlockState(BlockPos.containing(dest.getX(), dest.getY(), dest.getZ())))) {
            // the destination is inside a block, so aim for the first free space above it instead of failing outright
            Location above = MinecraftBlockExaminer.findValidLocationAbove(dest, 2);
            if (above != null) {
                dest = above;
            }
        }
        destination = dest;
    }

    @Override
    public Location getCurrentDestination() {
        return current != null ? current : destination.clone();
    }

    @Override
    public Iterable<Vec3> getPath() {
        return plan == null ? null : plan.getPath();
    }

    @Override
    public Location getTargetAsLocation() {
        return destination;
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
                return false; // still searching, so the NPC waits where it is
            plan = planner.getPath();
            planner = null;
        }
        if (getCancelReason() != null || plan == null || plan.isComplete())
            return true;
        Location loc = npc.getStoredLocation();
        if (current == null) {
            Vec3 next = plan.getCurrentVector();
            current = new Location(loc.getWorld(), next.x, next.y, next.z);
        }
        if (params.withinMargin(loc, current)) {
            plan.update(npc);
            if (plan.isComplete())
                return true;
            current = null;
            return false;
        }
        plan.run(npc);
        moveTowards(current);
        return false;
    }

    /**
     * Sets up the incremental search, adding the examiners that depend on this NPC and this destination.
     *
     * @return false when no search is possible at all, having set the cancel reason
     */
    private boolean startPlanning() {
        Location start = npc.getStoredLocation();
        if (start == null || start.getWorld() == null) {
            setCancelReason(CancelReason.STUCK);
            return false;
        }
        if (destination.getWorld() != start.getWorld()) {
            // A* is a within-level search; crossing dimensions is a teleport, not a walk
            setCancelReason(CancelReason.STUCK);
            return false;
        }
        if (MinecraftBlockExaminer.isWaterMob(npc.getCosmeticEntity())
                && !params.hasExaminer(SwimmingNeighbourExaminer.class)) {
            // a fish paths through the water column rather than along the sea floor
            params.examiner(new SwimmingNeighbourExaminer());
        }
        if (!params.hasExaminer(AvoidWaterExaminer.class)) {
            params.examiner(new AvoidWaterExaminer(params));
        }
        planner = new AStarPlanner(params, start, destination);
        return true;
    }

    /**
     * Hands the next waypoint to the mob's own move control where there is one, and pushes the entity directly where
     * there is not — an armour stand or a display entity has no move control but still has to get there.
     */
    private void moveTowards(Location target) {
        Entity entity = npc.getEntity();
        if (entity instanceof Mob mob && entity.getType() != EntityType.ARMOR_STAND) {
            mob.getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(),
                    params.speedModifier());
            mob.getLookControl().setLookAt(target.getX(), target.getY(), target.getZ());
            return;
        }
        Vec3 direction = new Vec3(target.getX() - entity.getX(), target.getY() - entity.getY(),
                target.getZ() - entity.getZ());
        double distance = direction.length();
        if (distance < 1.0E-4) {
            return;
        }
        Vec3 step = direction.scale(1 / distance).scale(BASE_SPEED * params.speedModifier());
        if (!(entity instanceof LivingEntity)) {
            // a non-living entity is not moved by delta movement alone in every case, so it is placed directly
            entity.setPos(entity.getX() + step.x, entity.getY() + step.y, entity.getZ() + step.z);
            return;
        }
        // delta movement alone will never climb a step, so add the upward nudge upstream applies
        double dx = target.getX() - entity.getX();
        double dy = target.getY() - entity.getY();
        double dz = target.getZ() - entity.getZ();
        boolean inLiquid = MinecraftBlockExaminer
                .isLiquidOrWaterlogged(entity.level().getBlockState(entity.blockPosition()));
        double upward = entity.getDeltaMovement().y;
        if (dy >= 1 && Math.sqrt(dx * dx + dz * dz) <= 0.4 || dy >= 0.2 && inLiquid) {
            upward = 0.75;
        }
        entity.setDeltaMovement(step.x, upward, step.z);
        entity.hasImpulse = true;
        npc.faceLocation(target);
    }

    /**
     * Makes {@link NavigatorParameters#avoidWater()} mean something: water costs extra to path through, so the search
     * prefers a dry detour where one exists but will still swim when that is the only way through.
     * <p>
     * Upstream declares this as an anonymous examiner inside its planner. It is a named class here so that
     * {@link NavigatorParameters#hasExaminer} can recognise it and a repath cannot stack up duplicates.
     */
    private static class AvoidWaterExaminer implements BlockExaminer {
        private final NavigatorParameters params;

        private AvoidWaterExaminer(NavigatorParameters params) {
            this.params = params;
        }

        @Override
        public float getCost(BlockSource source, PathPoint point) {
            if (!params.avoidWater())
                return 0F;
            Vec3 pos = point.getVector();
            int x = Mth.floor(pos.x), y = Mth.floor(pos.y), z = Mth.floor(pos.z);
            return MinecraftBlockExaminer.isLiquid(source.getBlockAt(x, y + 1, z))
                    || MinecraftBlockExaminer.isLiquidOrWaterlogged(source.getBlockAt(x, y, z)) ? 2F : 0F;
        }

        @Override
        public PassableState isPassable(BlockSource source, PathPoint point) {
            return PassableState.IGNORE;
        }
    }

    /** Runs the A* a fixed number of nodes per tick, giving up once the configured node budget is spent. */
    public static class AStarPlanner implements PathPlanner {
        private int iterations;
        private final int iterationsPerTick = Setting.PATHFINDER_ITERATIONS_PER_TICK.asInt();
        private final int maxIterations = Setting.PATHFINDER_MAX_ITERATIONS.asInt();
        private Path plan;
        private final AStarState<VectorNode> state;

        public AStarPlanner(NavigatorParameters params, Location from, Location to) {
            VectorGoal goal = new VectorGoal(to, (float) params.pathDistanceMargin());
            state = ASTAR.getStateFor(goal, new VectorNode(goal, from, new LevelBlockSource(from.getWorld()), params));
        }

        @Override
        public Path getPath() {
            return plan;
        }

        @Override
        public CancelReason tick() {
            plan = ASTAR.run(state, iterationsPerTick);
            if (plan != null)
                return null;
            if (state.isEmpty())
                return CancelReason.STUCK; // the open set ran dry, so there is no route at all
            if (iterationsPerTick > 0 && maxIterations > 0) {
                iterations += iterationsPerTick;
                if (iterations > maxIterations)
                    return CancelReason.STUCK;
            }
            return null;
        }
    }

    /** A search in progress. Ticked once per NPC tick until it yields a path or gives up. */
    public static interface PathPlanner {
        default void cancel() {
        }

        Path getPath();

        /** @return a reason to give up, or null to keep going */
        CancelReason tick();
    }

    private static final double BASE_SPEED = 0.2;
    private static final AStarMachine<VectorNode, Path> ASTAR = AStarMachine.createWithVectorStorage();
}
