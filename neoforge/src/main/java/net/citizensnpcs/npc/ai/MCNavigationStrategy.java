package net.citizensnpcs.npc.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.citizensnpcs.api.ai.AbstractPathStrategy;
import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.ai.TargetType;
import net.citizensnpcs.api.ai.event.CancelReason;
import net.citizensnpcs.api.astar.pathfinder.DoorExaminer;
import net.citizensnpcs.api.astar.pathfinder.MinecraftBlockExaminer;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;

/**
 * Navigation handed to the mob's own vanilla pathfinder.
 * <p>
 * This is the fallback for {@link net.citizensnpcs.api.ai.PathfinderType#MINECRAFT} and the configured default, as
 * upstream. It moves the way the vanilla mob would, which is consistent with the rest of the world but ignores every
 * {@link net.citizensnpcs.api.astar.pathfinder.BlockExaminer} on the parameters. Two of them are translated into vanilla
 * equivalents rather than dropped: {@code avoidWater} becomes an increased water pathfinding malus, and the presence of a
 * {@link DoorExaminer} becomes {@code setCanOpenDoors}. Only a {@link Mob} has vanilla navigation at all, so anything
 * else has to use the Citizens pathfinder.
 * <p>
 * Vanilla ticks the navigation itself inside {@code Mob.serverAiStep}, so this strategy watches it rather than driving
 * it. Upstream wraps the same thing in an {@code MCNavigator} interface supplied per Minecraft version by its NMS
 * bridge; with one Minecraft version to support, that indirection buys nothing and is gone.
 * <p>
 * Two details are load-bearing, and both were learnt the hard way:
 * <ul>
 * <li>The path is issued from the first {@link #update()}, not from the constructor. Vanilla refuses to compute one while
 * {@code onGround} is false, and an NPC told to walk on the same tick it spawned has not touched the floor yet, so the
 * flag is forced first. Upstream does the same, with the same explanation in a comment.</li>
 * <li>Vanilla reporting the navigation done means <em>finished</em>, not stuck. Treating it as stuck fires a cancel event
 * and runs the stuck action on every successful arrival.</li>
 * </ul>
 */
public class MCNavigationStrategy extends AbstractPathStrategy {
    private boolean issued;
    private final Mob mob;
    private final float oldWaterMalus;
    private final NavigatorParameters parameters;
    private final Predicate<Mob> pathIssuer;
    private final Location target;

    MCNavigationStrategy(NPC npc, Iterable<Vec3> path, NavigatorParameters params) {
        super(TargetType.LOCATION);
        parameters = params;
        mob = asMob(npc);
        List<Vec3> list = new ArrayList<>();
        path.forEach(list::add);
        if (list.isEmpty())
            throw new IllegalArgumentException("a path needs at least one point");
        Vec3 last = list.get(list.size() - 1);
        target = new Location(npc.getStoredLocation().getWorld(), last.x, last.y, last.z);
        pathIssuer = m -> m.getNavigation().moveTo(toVanillaPath(list), parameters.speedModifier());
        oldWaterMalus = prepare();
    }

    MCNavigationStrategy(NPC npc, Location dest, NavigatorParameters params) {
        super(TargetType.LOCATION);
        parameters = params;
        mob = asMob(npc);
        if (dest.getWorld() != null && !MinecraftBlockExaminer.canStandIn(dest.getBlockState())) {
            Location above = MinecraftBlockExaminer.findValidLocationAbove(dest, 2);
            if (above != null) {
                dest = above;
            }
        }
        target = dest;
        Location to = dest;
        pathIssuer = m -> m.getNavigation().moveTo(to.getX(), to.getY(), to.getZ(), parameters.speedModifier());
        oldWaterMalus = prepare();
    }

    @Override
    public Location getCurrentDestination() {
        if (mob == null || mob.getNavigation().getPath() == null)
            return target.clone();
        return Location.fromBlockPosCentred((ServerLevel) mob.level(), mob.getNavigation().getPath().getNextNodePos());
    }

    @Override
    public Iterable<Vec3> getPath() {
        if (mob == null)
            return null;
        net.minecraft.world.level.pathfinder.Path path = mob.getNavigation().getPath();
        if (path == null)
            return null;
        List<Vec3> out = new ArrayList<>(path.getNodeCount());
        for (int i = 0; i < path.getNodeCount(); i++) {
            BlockPos pos = path.getNodePos(i);
            out.add(new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
        }
        return out;
    }

    @Override
    public Location getTargetAsLocation() {
        return target;
    }

    @Override
    public void stop() {
        if (mob == null)
            return;
        if (oldWaterMalus >= 0) {
            mob.setPathfindingMalus(PathType.WATER, oldWaterMalus);
        }
        mob.getNavigation().stop();
    }

    @Override
    public String toString() {
        return "MCNavigationStrategy [target=" + target + "]";
    }

    @Override
    public boolean update() {
        if (getCancelReason() != null)
            return true;
        if (mob == null) {
            setCancelReason(CancelReason.STUCK);
            return true;
        }
        if (!issued) {
            issued = true;
            // The first request is deferred; physics may have reset the spawn-time ground flag in between.
            mob.setOnGround(true);
            if (!pathIssuer.test(mob)) {
                // vanilla could not produce a path at all, which is the only genuine failure here
                setCancelReason(CancelReason.STUCK);
                return true;
            }
        }
        mob.getNavigation().setSpeedModifier(parameters.speedModifier());
        if (parameters.withinMargin(Location.of(mob), target)) {
            stop();
            return true;
        }
        // vanilla has already ticked the navigation this tick; done means it finished walking what it planned
        return mob.getNavigation().isDone();
    }

    /**
     * Puts the vanilla navigator into the state the parameters ask for.
     *
     * @return the water malus to restore on stop, or -1 when it was left alone
     */
    private float prepare() {
        if (mob == null)
            return -1;
        mob.getNavigation().getNodeEvaluator().setCanOpenDoors(parameters.hasExaminer(DoorExaminer.class));
        float old = mob.getPathfindingMalus(PathType.WATER);
        if (parameters.avoidWater() && old >= 0) {
            mob.setPathfindingMalus(PathType.WATER, old + 1F);
            return old;
        }
        return -1;
    }

    /**
     * Wraps a route we were handed in a vanilla path. Nodes are marked walkable because vanilla scores a
     * {@code BLOCKED} node as unusable and would refuse to follow it.
     */
    private static net.minecraft.world.level.pathfinder.Path toVanillaPath(List<Vec3> vectors) {
        List<Node> nodes = new ArrayList<>(vectors.size());
        for (Vec3 vector : vectors) {
            Node node = new Node(Mth.floor(vector.x), Mth.floor(vector.y), Mth.floor(vector.z));
            node.type = PathType.WALKABLE;
            nodes.add(node);
        }
        Vec3 last = vectors.get(vectors.size() - 1);
        return new net.minecraft.world.level.pathfinder.Path(nodes, BlockPos.containing(last.x, last.y, last.z), true);
    }

    private static Mob asMob(NPC npc) {
        return npc.getEntity() instanceof Mob mob ? mob : null;
    }
}
