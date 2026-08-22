package net.citizensnpcs.api.astar.pathfinder;

import java.util.ArrayList;
import java.util.List;

import net.citizensnpcs.api.astar.pathfinder.BlockExaminer.ReplacementNeighbourGenerator;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Pathfinding for something that flies: every cell is standable, and all 26 surrounding cells are candidates.
 * <p>
 * Because it replaces the neighbour expansion rather than adding to it, the ground-walking rules never get a say — which
 * is the point, since a flying NPC has no need of a floor. Liquid is impassable (a flier drowns or burns in it) and cobweb
 * costs extra.
 * <p>
 * No corner-cutting guard is applied, unlike {@link VectorNode}'s land expansion: a flier passing diagonally between two
 * blocks is legitimate, so requiring both straight steps beside it would rule out valid routes.
 */
public class FlyingBlockExaminer implements ReplacementNeighbourGenerator {
    @Override
    public StandableState canStandAt(BlockSource source, PathPoint point) {
        return StandableState.STANDABLE;
    }

    @Override
    public float getCost(BlockSource source, PathPoint point) {
        Vec3 pos = point.getVector();
        int x = Mth.floor(pos.x), y = Mth.floor(pos.y), z = Mth.floor(pos.z);
        if (source.getBlockAt(x, y + 1, z).is(Blocks.COBWEB) || source.getBlockAt(x, y, z).is(Blocks.COBWEB))
            return 2F;
        return 0F;
    }

    @Override
    public List<PathPoint> getNeighbours(BlockSource source, PathPoint point) {
        List<PathPoint> neighbours = new ArrayList<>(26);
        Vec3 base = point.getVector();
        int baseX = Mth.floor(base.x), baseY = Mth.floor(base.y), baseZ = Mth.floor(base.z);
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                if (!source.isYWithinBounds(baseY + y))
                    continue;
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0)
                        continue;
                    neighbours.add(point.createChild(baseX + x, baseY + y, baseZ + z));
                }
            }
        }
        return neighbours;
    }

    @Override
    public PassableState isPassable(BlockSource source, PathPoint point) {
        Vec3 pos = point.getVector();
        int x = Mth.floor(pos.x), y = Mth.floor(pos.y), z = Mth.floor(pos.z);
        BlockState in = source.getBlockAt(x, y, z);
        BlockState above = source.getBlockAt(x, y + 1, z);
        if (MinecraftBlockExaminer.isLiquid(in) || MinecraftBlockExaminer.isLiquid(above))
            return PassableState.IMPASSABLE;

        return MinecraftBlockExaminer.canStandIn(in, above) ? PassableState.PASSABLE : PassableState.IMPASSABLE;
    }
}
