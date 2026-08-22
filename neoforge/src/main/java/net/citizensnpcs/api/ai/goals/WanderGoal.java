package net.citizensnpcs.api.ai.goals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.citizensnpcs.api.ai.tree.Behavior;
import net.citizensnpcs.api.ai.tree.BehaviorStatus;
import net.citizensnpcs.api.astar.pathfinder.MinecraftBlockExaminer;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * Wanders about within a region, either by pathfinding to a random reachable spot or, when {@code pathfind} is off, by
 * nudging the NPC one block at a time.
 * <p>
 * Two of upstream's builder options were built on libraries this port drops:
 * <ul>
 * <li>{@code regionCentres}/{@code tree} indexed the allowed area in a {@code ch.ethz.globis.phtree} PH-tree. The
 * capability matters — it is how a wandering NPC is kept inside its waypoint region — so it is kept, as a list of boxes
 * with a containment test. A PH-tree earns its keep over thousands of regions; an NPC has a handful, and a linear scan
 * over those beats descending a tree.</li>
 * <li>{@code worldguardRegion} took a {@code Supplier<Object>} and reflectively cast it to a WorldGuard
 * {@code ProtectedRegion}. WorldGuard does not exist here, and {@link Builder#filter} already covers it: a caller with
 * some other notion of "allowed area" passes a predicate. The reflective hole is gone rather than kept as a stub.</li>
 * </ul>
 */
public class WanderGoal implements Behavior {
    private int delay;
    private int delayedTicks;
    private final Predicate<Location> filter;
    private boolean forceFinish;
    private int movingTicks;
    private final NPC npc;
    private boolean pathfind;
    private boolean paused;
    private final Function<NPC, Location> picker;
    private Location target;
    private int xrange;
    private int yrange;

    private WanderGoal(NPC npc, boolean pathfind, int xrange, int yrange, Supplier<List<AABB>> regions, int delay,
            Predicate<Location> filter, Function<NPC, Location> picker) {
        this.npc = npc;
        this.pathfind = pathfind;
        this.xrange = xrange;
        this.yrange = yrange;
        this.delay = delay;
        this.picker = picker;
        this.filter = filter != null ? filter : candidate -> defaultFilter(npc, regions, candidate);
    }

    /** Avoids water when the navigator is set to, and stays inside the configured regions when there are any. */
    private static boolean defaultFilter(NPC npc, Supplier<List<AABB>> regions, Location candidate) {
        if (npc.getNavigator().getDefaultParameters().avoidWater()) {
            BlockPos pos = candidate.getBlockPos();
            if (MinecraftBlockExaminer.isLiquidOrWaterlogged(candidate.getWorld().getBlockState(pos.above()))
                    || MinecraftBlockExaminer.isLiquidOrWaterlogged(candidate.getWorld().getBlockState(pos.above(2))))
                return false;
        }
        if (regions == null)
            return true;
        List<AABB> boxes = regions.get();
        if (boxes == null || boxes.isEmpty())
            return true;
        for (AABB box : boxes) {
            if (box.contains(candidate.getX(), candidate.getY(), candidate.getZ()))
                return true;
        }
        return false;
    }

    public void pause() {
        this.paused = true;
        if (target == null)
            return;
        if (pathfind) {
            npc.getNavigator().cancelNavigation();
        } else {
            npc.setMoveDestination(null);
        }
    }

    @Override
    public void reset() {
        target = null;
        movingTicks = 0;
        delayedTicks = delay;
        forceFinish = false;
    }

    @Override
    public BehaviorStatus run() {
        if (paused || forceFinish)
            return BehaviorStatus.SUCCESS;
        if (pathfind)
            return npc.getNavigator().isNavigating() ? BehaviorStatus.RUNNING : BehaviorStatus.SUCCESS;
        if (target == null || !npc.isSpawned() || target.getWorld() != npc.getStoredLocation().getWorld())
            return BehaviorStatus.SUCCESS;
        if (npc.getStoredLocation().distance(target) < 0.1)
            return BehaviorStatus.SUCCESS;
        npc.setMoveDestination(target);
        if (movingTicks-- <= 0) {
            npc.setMoveDestination(null);
            return BehaviorStatus.SUCCESS;
        }
        return BehaviorStatus.RUNNING;
    }

    public void setDelay(int delayTicks) {
        this.delay = delayTicks;
        this.delayedTicks = delayTicks;
        forceFinish = true;
    }

    public void setPathfind(boolean pathfind) {
        this.pathfind = pathfind;
        forceFinish = true;
    }

    public void setXYRange(int xrange, int yrange) {
        this.xrange = xrange;
        this.yrange = yrange;
        forceFinish = true;
    }

    @Override
    public boolean shouldExecute() {
        if (!npc.isSpawned() || npc.getNavigator().isNavigating() || paused || delayedTicks-- > 0)
            return false;
        Location dest = findRandomPosition();
        if (dest == null)
            return false;
        if (pathfind) {
            npc.getNavigator().setTarget(dest);
            // an NPC that cannot reach where it picked should pick again, not be teleported there
            npc.getNavigator().getLocalParameters().stuckAction(null);
            npc.getNavigator().getLocalParameters().addSingleUseCallback(reason -> forceFinish = true);
        } else {
            Random random = new Random();
            dest = dest.clone();
            dest.setX(dest.getX() + random.nextDouble() * 0.5);
            dest.setZ(dest.getZ() + random.nextDouble() * 0.5);
            movingTicks = 20 + random.nextInt(20);
        }
        this.target = dest;
        return true;
    }

    public void unpause() {
        this.paused = false;
    }

    private Location findRandomPosition() {
        if (picker != null)
            return picker.apply(npc);
        // without pathfinding the NPC is only shoved one block at a time, so a wide search would pick somewhere
        // it could never reach
        return MinecraftBlockExaminer.findRandomValidLocation(npc.getStoredLocation(), pathfind ? xrange : 1,
                pathfind ? yrange : 1, filter, RANDOM);
    }

    public static Builder builder(NPC npc) {
        return new Builder(npc);
    }

    public static class Builder {
        private int delay = 10;
        private Predicate<Location> filter;
        private final NPC npc;
        private boolean pathfind = true;
        private Function<NPC, Location> picker;
        private Supplier<List<AABB>> regions;
        private int xrange = 10;
        private int yrange = 2;

        private Builder(NPC npc) {
            this.npc = npc;
        }

        public WanderGoal build() {
            return new WanderGoal(npc, pathfind, xrange, yrange, regions, delay, filter, picker);
        }

        public Builder delay(int delay) {
            this.delay = delay;
            return this;
        }

        public Builder destinationPicker(Function<NPC, Location> picker) {
            this.picker = picker;
            return this;
        }

        public Builder filter(Predicate<Location> filter) {
            this.filter = filter;
            return this;
        }

        public Builder pathfind(boolean pathfind) {
            this.pathfind = pathfind;
            return this;
        }

        /**
         * Confines the wander to a box of {@code xrange} by {@code yrange} around each supplied centre. This is what a
         * waypoint provider passes so the NPC stays near its route.
         */
        public Builder regionCentres(Supplier<Iterable<Location>> supplier) {
            this.regions = () -> {
                List<AABB> boxes = new ArrayList<>();
                for (Location centre : supplier.get()) {
                    boxes.add(new AABB(centre.getBlockX() - xrange, centre.getBlockY() - yrange,
                            centre.getBlockZ() - xrange, centre.getBlockX() + xrange + 1,
                            centre.getBlockY() + yrange + 1, centre.getBlockZ() + xrange + 1));
                }
                return boxes;
            };
            return this;
        }

        /** The boxes directly, for a caller that already knows its own bounds. */
        public Builder regions(Supplier<List<AABB>> supplier) {
            this.regions = supplier;
            return this;
        }

        public Builder xrange(int xrange) {
            this.xrange = xrange;
            return this;
        }

        public Builder yrange(int yrange) {
            this.yrange = yrange;
            return this;
        }
    }

    private static final Random RANDOM = new Random();
}
