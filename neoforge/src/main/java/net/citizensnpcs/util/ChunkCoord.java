package net.citizensnpcs.util;

import java.util.Objects;

import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.schedulers.Schedulers;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * A level plus chunk coordinate, used to key NPCs awaiting a chunk load.
 * <p>
 * Upstream identifies the world by its Bukkit UUID; Minecraft has no such thing, so the dimension
 * {@link ResourceKey} is used instead. Value semantics are unchanged, so this stays usable as a map key.
 */
public class ChunkCoord {
    public final ResourceKey<Level> dimension;
    public final int x;
    public final int z;

    public ChunkCoord(LevelChunk chunk) {
        this(chunk.getLevel().dimension(), chunk.getPos().x, chunk.getPos().z);
    }

    public ChunkCoord(Location loc) {
        this(loc.getWorld() == null ? null : loc.getWorld().dimension(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }

    public ChunkCoord(ServerLevel level, ChunkPos pos) {
        this(level.dimension(), pos.x, pos.z);
    }

    public ChunkCoord(ResourceKey<Level> dimension, int x, int z) {
        this.x = x;
        this.z = z;
        this.dimension = dimension;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;

        if (obj == null || getClass() != obj.getClass())
            return false;

        ChunkCoord other = (ChunkCoord) obj;
        if (!Objects.equals(dimension, other.dimension))
            return false;

        return x == other.x && z == other.z;
    }

    public LevelChunk getChunk() {
        ServerLevel level = getWorld();
        return level != null ? level.getChunk(x, z) : null;
    }

    /** @return whether the chunk is already loaded, without forcing a load */
    public boolean isLoaded() {
        ServerLevel level = getWorld();
        return level != null && level.hasChunk(x, z);
    }

    public ServerLevel getWorld() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null || dimension == null ? null : server.getLevel(dimension);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 + (dimension == null ? 0 : dimension.hashCode())) + x) + z;
    }

    /** Adds or removes a forced-load ticket, the equivalent of Bukkit's {@code Chunk#setForceLoaded}. */
    public void setForceLoaded(boolean forced) {
        ServerLevel level = getWorld();
        if (level == null)
            return;
        Schedulers.get().runTask(() -> level.setChunkForced(x, z, forced));
    }

    @Override
    public String toString() {
        return "[" + x + "," + z + "]";
    }
}
