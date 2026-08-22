package net.citizensnpcs.api.astar.pathfinder;

import net.citizensnpcs.api.util.BoundingBox;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Read-only block access for the pathfinder, so that path calculation can run against a snapshot or an async cache
 * rather than the live level.
 * <p>
 * Upstream exposes {@code getMaterialAt} and {@code getBlockDataAt} separately, mirroring Bukkit's split between
 * {@code Material} and {@code BlockData}. Minecraft's {@link BlockState} carries both the block identity and its
 * properties, so the two collapse into {@link #getBlockAt}; {@link #getMaterialAt} remains as a convenience returning
 * just the {@link Block}.
 */
public abstract class BlockSource {
    public abstract BlockState getBlockAt(int x, int y, int z);

    public BlockState getBlockAt(BlockPos pos) {
        return getBlockAt(pos.getX(), pos.getY(), pos.getZ());
    }

    public BlockState getBlockAt(Vec3 position) {
        return getBlockAt(floor(position.x), floor(position.y), floor(position.z));
    }

    public abstract BoundingBox getCollisionBox(int x, int y, int z);

    public BoundingBox getCollisionBox(BlockPos pos) {
        return getCollisionBox(pos.getX(), pos.getY(), pos.getZ());
    }

    public BoundingBox getCollisionBox(Vec3 pos) {
        return getCollisionBox(floor(pos.x), floor(pos.y), floor(pos.z));
    }

    /** @return the block type at that position, ignoring its state properties */
    public Block getMaterialAt(int x, int y, int z) {
        return getBlockAt(x, y, z).getBlock();
    }

    public Block getMaterialAt(Vec3 pos) {
        return getMaterialAt(floor(pos.x), floor(pos.y), floor(pos.z));
    }

    public abstract boolean isYWithinBounds(int y);

    private static int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }
}
