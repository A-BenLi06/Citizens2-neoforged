package net.citizensnpcs.api.astar.pathfinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.citizensnpcs.api.astar.pathfinder.BlockExaminer.ReplacementNeighbourGenerator;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Replaces the normal 26-neighbour walk expansion with one that only ever offers cells full of liquid, so a fish paths
 * through water the way it actually swims rather than being routed along the sea floor.
 * <p>
 * Only the six face neighbours and the four horizontal diagonals are offered; a diagonal is offered only when both
 * straight steps beside it are swimmable, which is the same corner-cutting guard {@link VectorNode} applies on land.
 */
public class SwimmingNeighbourExaminer implements ReplacementNeighbourGenerator {
    private boolean canSwimInLava;

    @Override
    public StandableState canStandAt(BlockSource source, PathPoint point) {
        return StandableState.STANDABLE;
    }

    @Override
    public float getCost(BlockSource source, PathPoint point) {
        return 0;
    }

    @Override
    public List<PathPoint> getNeighbours(BlockSource source, PathPoint point) {
        if (isPassable(source, point) != PassableState.PASSABLE)
            return Collections.emptyList();

        Vec3 base = point.getVector();
        int bx = Mth.floor(base.x), by = Mth.floor(base.y), bz = Mth.floor(base.z);
        List<PathPoint> out = new ArrayList<>(10);

        addIfPassable(source, point, out, bx + 1, by, bz);
        addIfPassable(source, point, out, bx - 1, by, bz);
        addIfPassable(source, point, out, bx, by, bz + 1);
        addIfPassable(source, point, out, bx, by, bz - 1);
        addIfPassable(source, point, out, bx, by + 1, bz);
        addIfPassable(source, point, out, bx, by - 1, bz);

        addDiagonalIfPassable(source, point, out, bx, by, bz, 1, 1);
        addDiagonalIfPassable(source, point, out, bx, by, bz, 1, -1);
        addDiagonalIfPassable(source, point, out, bx, by, bz, -1, 1);
        addDiagonalIfPassable(source, point, out, bx, by, bz, -1, -1);

        return out;
    }

    @Override
    public PassableState isPassable(BlockSource source, PathPoint point) {
        Vec3 pos = point.getVector();
        int y = Mth.floor(pos.y);
        if (!source.isYWithinBounds(y))
            return PassableState.IMPASSABLE;

        return isSwimCellPassable(source, Mth.floor(pos.x), y, Mth.floor(pos.z)) ? PassableState.PASSABLE
                : PassableState.IMPASSABLE;
    }

    public void setCanSwimInLava(boolean canSwimInLava) {
        this.canSwimInLava = canSwimInLava;
    }

    private void addDiagonalIfPassable(BlockSource source, PathPoint parent, List<PathPoint> out, int bx, int by,
            int bz, int dx, int dz) {
        if (!source.isYWithinBounds(by))
            return;
        if (!isSwimCellPassable(source, bx + dx, by, bz) || !isSwimCellPassable(source, bx, by, bz + dz)
                || !isSwimCellPassable(source, bx + dx, by, bz + dz))
            return;

        out.add(parent.createChild(bx + dx, by, bz + dz));
    }

    private void addIfPassable(BlockSource source, PathPoint parent, List<PathPoint> out, int x, int y, int z) {
        if (!source.isYWithinBounds(y) || !isSwimCellPassable(source, x, y, z))
            return;
        out.add(parent.createChild(x, y, z));
    }

    private boolean isSwimCellPassable(BlockSource source, int x, int y, int z) {
        BlockState state = source.getBlockAt(x, y, z);
        if (!MinecraftBlockExaminer.isLiquidOrWaterlogged(state))
            return false;
        if (MinecraftBlockExaminer.isLiquid(state))
            return !state.getFluidState().is(FluidTags.LAVA) || canSwimInLava;
        // a waterlogged non-liquid block: upstream lets it through, which covers kelp, seagrass and coral fans
        return true;
    }
}
