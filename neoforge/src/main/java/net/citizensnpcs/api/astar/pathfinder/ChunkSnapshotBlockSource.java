package net.citizensnpcs.api.astar.pathfinder;

import java.util.HashMap;
import java.util.Map;

import net.citizensnpcs.api.util.BoundingBox;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Immutable block-state palettes from loaded chunks, with no retained live level or chunk references. */
public final class ChunkSnapshotBlockSource extends BlockSource implements BlockGetter {
    private final Map<Long, PalettedContainer<BlockState>[]> chunks;
    private final int minY, height;

    private ChunkSnapshotBlockSource(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Capture chunks on the server thread");
        if (minX >= maxX || minZ >= maxZ) throw new IllegalArgumentException("Empty snapshot rectangle");
        minY = level.getMinBuildHeight();
        height = level.getHeight();
        Map<Long, PalettedContainer<BlockState>[]> captured = new HashMap<>();
        for (int cx = Math.floorDiv(minX, 16); cx <= Math.floorDiv(maxX - 1, 16); cx++) {
            for (int cz = Math.floorDiv(minZ, 16); cz <= Math.floorDiv(maxZ - 1, 16); cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                LevelChunkSection[] live = chunk.getSections();
                @SuppressWarnings("unchecked")
                PalettedContainer<BlockState>[] sections = (PalettedContainer<BlockState>[]) new PalettedContainer<?>[live.length];
                for (int i = 0; i < live.length; i++) {
                    if (!live[i].hasOnlyAir()) sections[i] = live[i].getStates().copy();
                }
                captured.put(ChunkPos.asLong(cx, cz), sections);
            }
        }
        chunks = Map.copyOf(captured);
    }

    /** Rectangle bounds are in blocks and exclusive at the maximum. Does not load or ticket chunks. */
    public static ChunkSnapshotBlockSource capture(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        return new ChunkSnapshotBlockSource(level, minX, minZ, maxX, maxZ);
    }

    public int getCapturedChunkCount() {
        return chunks.size();
    }

    @Override
    public BlockState getBlockAt(int x, int y, int z) {
        PalettedContainer<BlockState>[] sections = chunks.get(ChunkPos.asLong(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
        if (sections == null || !isYWithinBounds(y)) return Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> section = sections[getSectionIndex(y)];
        return section == null ? Blocks.AIR.defaultBlockState() : section.get(x & 15, y & 15, z & 15);
    }

    @Override
    public boolean isBlockAvailable(int x, int y, int z) {
        return isYWithinBounds(y) && chunks.containsKey(ChunkPos.asLong(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
    }

    @Override
    public BoundingBox getCollisionBox(int x, int y, int z) {
        BlockPos position = new BlockPos(x, y, z);
        VoxelShape shape = getBlockAt(x, y, z).getCollisionShape(this, position);
        if (shape.isEmpty()) return new BoundingBox(x, y, z, x, y, z);
        AABB bounds = shape.bounds().move(position);
        return new BoundingBox(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
    }

    @Override public int getHeight() { return height; }
    @Override public int getMinBuildHeight() { return minY; }
    @Override public boolean isYWithinBounds(int y) { return y >= minY && y < getMaxBuildHeight(); }
    @Override public BlockState getBlockState(BlockPos pos) { return getBlockAt(pos); }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockAt(pos).getFluidState(); }
    @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
}
