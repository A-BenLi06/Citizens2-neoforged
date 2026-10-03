package net.citizensnpcs.api.astar.pathfinder;

import net.citizensnpcs.api.util.BoundingBox;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Reads blocks straight from a level.
 * <p>
 * Ordinary NPC navigation reads this source on the server thread. Background graph diagnostics use
 * {@link ChunkSnapshotBlockSource} instead of accessing a live level.
 * <p>
 * A position in an unloaded chunk answers as air rather than forcing the chunk to load, so a long path cannot drag the
 * world in behind it.
 */
public class LevelBlockSource extends BlockSource implements LevelHeightAccessor {
    private final ServerLevel level;

    public LevelBlockSource(ServerLevel level) {
        this.level = level;
    }

    @Override
    public BlockState getBlockAt(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.isLoaded(pos))
            return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        return level.getBlockState(pos);
    }

    @Override
    public BoundingBox getCollisionBox(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        VoxelShape shape = getBlockAt(x, y, z).getCollisionShape(level, pos);
        if (shape.isEmpty())
            return new BoundingBox(x, y, z, x, y, z);
        AABB box = shape.bounds().move(pos);
        return new BoundingBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    public ServerLevel getLevel() {
        return level;
    }

    @Override
    public int getHeight() {
        return level.getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return level.getMinBuildHeight();
    }

    @Override
    public boolean isBlockAvailable(int x, int y, int z) {
        return isYWithinBounds(y) && level.getChunkSource().getChunkNow(Math.floorDiv(x, 16), Math.floorDiv(z, 16)) != null;
    }

    @Override
    public boolean isYWithinBounds(int y) {
        return y >= level.getMinBuildHeight() && y < level.getMaxBuildHeight();
    }
}
