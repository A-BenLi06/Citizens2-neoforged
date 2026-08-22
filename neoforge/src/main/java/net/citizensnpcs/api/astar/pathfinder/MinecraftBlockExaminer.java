package net.citizensnpcs.api.astar.pathfinder;

import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

import net.citizensnpcs.api.astar.pathfinder.PathPoint.PathCallback;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

/**
 * The default walking rules: where a normal land NPC can stand, what it can walk through, and what costs extra.
 * <p>
 * Upstream expresses these as hand-maintained {@code EnumSet}s of Bukkit {@code Material} names, with version probes for
 * the ones that did not always exist. Vanilla already groups the same blocks with tags — {@code CLIMBABLE},
 * {@code FENCES}, {@code WALLS}, {@code DOORS} — so the rules are written against those instead. That is both shorter
 * and correct for blocks added by other mods, which a name list can never be.
 */
public class MinecraftBlockExaminer implements BlockExaminer {
    private NPC npc;

    public MinecraftBlockExaminer() {
    }

    public MinecraftBlockExaminer(NPC npc) {
        this.npc = npc;
    }

    @Override
    public StandableState canStandAt(BlockSource source, PathPoint point) {
        Vec3 pos = point.getVector();
        int x = Mth.floor(pos.x);
        int y = Mth.floor(pos.y);
        int z = Mth.floor(pos.z);
        if (!source.isYWithinBounds(y))
            return StandableState.NOT_STANDABLE;

        BlockState below = source.getBlockAt(x, y - 1, z);
        BlockState in = source.getBlockAt(x, y, z);
        if (!canStandOn(below) && !isLiquid(in) && !isLiquid(below) && !isClimbable(below))
            return StandableState.NOT_STANDABLE;

        if (!canJumpOn(below)) {
            // a fence or wall cannot be jumped onto, only walked alongside, so a step up onto one is rejected
            if (point.getParentPoint() == null)
                return StandableState.NOT_STANDABLE;
            Vec3 parent = point.getParentPoint().getVector();
            if ((parent.x != pos.x || parent.z != pos.z) && pos.y - parent.y == 1)
                return StandableState.NOT_STANDABLE;
        }
        return StandableState.STANDABLE;
    }

    @Override
    public float getCost(BlockSource source, PathPoint point) {
        Vec3 pos = point.getVector();
        int x = Mth.floor(pos.x);
        int y = Mth.floor(pos.y);
        int z = Mth.floor(pos.z);
        BlockState above = source.getBlockAt(x, y + 1, z);
        BlockState below = source.getBlockAt(x, y - 1, z);
        BlockState in = source.getBlockAt(x, y, z);
        if (above.is(Blocks.COBWEB) || in.is(Blocks.COBWEB) || below.is(Blocks.SOUL_SAND) || below.is(Blocks.ICE))
            return 2F;
        if ((npc == null || !isWaterMob(npc.getCosmeticEntity())) && isLiquidOrWaterlogged(in))
            return in.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) ? 4F : 2F;
        return 0F;
    }

    @Override
    public PassableState isPassable(BlockSource source, PathPoint point) {
        Vec3 pos = point.getVector();
        int x = Mth.floor(pos.x);
        int y = Mth.floor(pos.y);
        int z = Mth.floor(pos.z);
        BlockState in = source.getBlockAt(x, y, z);
        BlockState above = source.getBlockAt(x, y + 1, z);
        BlockState below = source.getBlockAt(x, y - 1, z);

        if (isClimbable(in) && (isClimbable(above) || isClimbable(below))) {
            point.addCallback(new LadderClimber());
            return PassableState.PASSABLE;
        }
        return canStandIn(in) && canStandIn(above) ? PassableState.PASSABLE : PassableState.IMPASSABLE;
    }

    /**
     * Drives an NPC up a ladder or vine. Vanilla movement has no notion of "climb to the next waypoint", so the upward
     * push is applied directly while the NPC is on the climbable column, as upstream does.
     */
    private static class LadderClimber implements PathCallback {
        private boolean added;

        @Override
        public void run(NPC npc, BlockPos point, List<BlockPos> path, int index) {
            if (added || !npc.isSpawned())
                return;
            added = true;
            Entity entity = npc.getEntity();
            entity.setDeltaMovement(entity.getDeltaMovement().x, CLIMB_SPEED, entity.getDeltaMovement().z);
            entity.hasImpulse = true;
        }

        private static final double CLIMB_SPEED = 0.2;
    }

    private static boolean canJumpOn(BlockState state) {
        return !state.is(BlockTags.FENCES) && !state.is(BlockTags.WALLS) && !state.is(BlockTags.FENCE_GATES);
    }

    /**
     * Whether an NPC can occupy this block. A bottom slab and an open trapdoor are standable-in even though they block
     * motion in general, which is the pair of exceptions upstream also carves out.
     */
    public static boolean canStandIn(BlockState state) {
        if (!state.blocksMotion())
            return true;
        if (state.getBlock() instanceof SlabBlock)
            return state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.BOTTOM;
        if (state.getBlock() instanceof TrapDoorBlock)
            return state.getValue(BlockStateProperties.OPEN);
        return false;
    }

    public static boolean canStandIn(BlockState... states) {
        for (BlockState state : states) {
            if (!canStandIn(state))
                return false;
        }
        return true;
    }

    /** Whether an NPC can be supported by this block. A closed trapdoor counts as a floor. */
    public static boolean canStandOn(BlockState state) {
        if (state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK) || state.is(BlockTags.FIRE)
                || state.is(BlockTags.CAMPFIRES))
            return false;
        if (state.getBlock() instanceof TrapDoorBlock)
            return !state.getValue(BlockStateProperties.OPEN);
        return state.blocksMotion();
    }

    public static boolean isClimbable(BlockState state) {
        return state.is(BlockTags.CLIMBABLE);
    }

    public static boolean isDoor(BlockState state) {
        return state.is(BlockTags.DOORS);
    }

    public static boolean isGate(BlockState state) {
        return state.is(BlockTags.FENCE_GATES);
    }

    public static boolean isLiquid(BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock;
    }

    /** True for a liquid block and for a solid block with water in it, which both slow an NPC down. */
    public static boolean isLiquidOrWaterlogged(BlockState state) {
        return !state.getFluidState().isEmpty();
    }

    public static boolean isWaterMob(Entity entity) {
        return entity instanceof WaterAnimal;
    }

    /**
     * A standable position near {@code base}, searched outwards. Used when a destination turns out to be inside a wall.
     *
     * @return the adjusted location, or null when nothing within the radius will do
     */
    public static Location findValidLocation(Location location, int radius) {
        return findValidLocation(location, radius, radius);
    }

    public static Location findValidLocation(Location location, int xradius, int yradius) {
        return findValidLocation(location, xradius, yradius, null);
    }

    public static Location findValidLocation(Location location, int xradius, int yradius,
            Predicate<Location> filter) {
        if (location.getWorld() == null)
            return null;
        if (isValidStandingPosition(location) && (filter == null || filter.test(location)))
            return location;
        for (int y = 0; y <= yradius; y++) {
            for (int x = -xradius; x <= xradius; x++) {
                for (int z = -xradius; z <= xradius; z++) {
                    for (int dy : y == 0 ? new int[] { 0 } : new int[] { y, -y }) {
                        Location candidate = new Location(location.getWorld(), location.getBlockX() + x + 0.5,
                                location.getBlockY() + dy, location.getBlockZ() + z + 0.5, location.getYaw(),
                                location.getPitch());
                        if (isValidStandingPosition(candidate) && (filter == null || filter.test(candidate)))
                            return candidate;
                    }
                }
            }
        }
        return null;
    }

    public static Location findValidLocationAbove(Location location, int radius) {
        return findValidLocation(location, 0, radius);
    }

    public static Location findRandomValidLocation(Location base, int xrange, int yrange) {
        return findRandomValidLocation(base, xrange, yrange, null, new Random());
    }

    public static Location findRandomValidLocation(Location base, int xrange, int yrange, Predicate<Location> filter,
            Random random) {
        if (base.getWorld() == null)
            return null;
        for (int attempt = 0; attempt < RANDOM_ATTEMPTS; attempt++) {
            Location candidate = new Location(base.getWorld(),
                    base.getBlockX() + random.nextInt(xrange * 2 + 1) - xrange + 0.5,
                    base.getBlockY() + random.nextInt(yrange * 2 + 1) - yrange,
                    base.getBlockZ() + random.nextInt(xrange * 2 + 1) - xrange + 0.5, base.getYaw(), base.getPitch());
            if (isValidStandingPosition(candidate) && (filter == null || filter.test(candidate)))
                return candidate;
        }
        return null;
    }

    private static boolean isValidStandingPosition(Location location) {
        BlockPos pos = BlockPos.containing(location.getX(), location.getY(), location.getZ());
        return canStandOn(location.getWorld().getBlockState(pos.below()))
                && canStandIn(location.getWorld().getBlockState(pos), location.getWorld().getBlockState(pos.above()));
    }

    private static final int RANDOM_ATTEMPTS = 20;
}
