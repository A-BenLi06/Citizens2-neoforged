package net.citizensnpcs.api.astar.pathfinder;

import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Makes liquid standable, so a water mob can path through it instead of treating it as a hole in the floor.
 * <p>
 * Upstream matches Bukkit {@code Material} names and carries pre-1.13 {@code STATIONARY_WATER} /
 * {@code STATIONARY_LAVA} fallbacks. Those materials do not exist in 1.21.1, so the fluid is read from the block state's
 * own {@code FluidState}, which also answers correctly for a waterlogged block.
 * <p>
 * Note the deliberate asymmetry kept from upstream: a cell counts as standable when it holds <em>any</em> fluid
 * (waterlogged stairs included), but the block above only counts as swimmable when it is a real liquid block. An NPC may
 * therefore swim through a waterlogged block but not surface into one, because its head would be inside something solid.
 */
public class SwimmingExaminer implements BlockExaminer {
    private boolean canSwimInLava;

    @Override
    public StandableState canStandAt(BlockSource source, PathPoint point) {
        if (MinecraftBlockExaminer.isLiquidOrWaterlogged(source.getBlockAt(point.getVector())))
            return StandableState.STANDABLE;
        return StandableState.IGNORE;
    }

    public boolean canSwimInLava() {
        return canSwimInLava;
    }

    @Override
    public float getCost(BlockSource source, PathPoint point) {
        return 0;
    }

    @Override
    public PassableState isPassable(BlockSource source, PathPoint point) {
        Vec3 pos = point.getVector();
        if (!MinecraftBlockExaminer.isLiquidOrWaterlogged(source.getBlockAt(pos)))
            return PassableState.IGNORE;

        BlockState above = source.getBlockAt(Mth.floor(pos.x), Mth.floor(pos.y) + 1, Mth.floor(pos.z));
        return isSwimmableLiquid(above) || MinecraftBlockExaminer.canStandIn(above) ? PassableState.PASSABLE
                : PassableState.IMPASSABLE;
    }

    public void setCanSwimInLava(boolean canSwimInLava) {
        this.canSwimInLava = canSwimInLava;
    }

    /** A liquid <em>block</em> this NPC is willing to be inside — not merely a block with fluid in it. */
    private boolean isSwimmableLiquid(BlockState state) {
        if (!MinecraftBlockExaminer.isLiquid(state))
            return false;
        return !state.getFluidState().is(FluidTags.LAVA) || canSwimInLava;
    }
}
