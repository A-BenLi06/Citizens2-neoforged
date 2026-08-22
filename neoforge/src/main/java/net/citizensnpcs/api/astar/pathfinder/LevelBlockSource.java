package net.citizensnpcs.api.astar.pathfinder;

import net.citizensnpcs.api.util.BoundingBox;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Reads blocks straight from a level.
 * <p>
 * Upstream has three sources — a live one, a chunk-snapshot one and an async cache — because Bukkit block access from
 * another thread is unsafe and its chunk queries are slow. Citizens pathfinding here runs on the server thread against a
 * level that is already in memory, so one direct source covers it; a snapshot source would only be needed if path
 * calculation moved off-thread.
 * <p>
 * A position in an unloaded chunk answers as air rather than forcing the chunk to load, so a long path cannot drag the
 * world in behind it.
 */
public class LevelBlockSource extends BlockSource {
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
    public boolean isYWithinBounds(int y) {
        return y >= level.getMinBuildHeight() && y < level.getMaxBuildHeight();
    }
}
