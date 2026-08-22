package net.citizensnpcs.api.util;

import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/**
 * A mutable {@code (level, x, y, z, yaw, pitch)} tuple.
 * <p>
 * Replaces {@code org.bukkit.Location} from upstream Citizens2. Minecraft has no equivalent type: {@link Vec3} carries
 * no level reference and no rotation, and {@link BlockPos} is block-granular. Method names mirror the Bukkit original so
 * that ported call sites read unchanged; Minecraft-native accessors ({@link #getBlockPos()}, {@link #getBlockState()},
 * {@link #toVec3()}) are added alongside.
 * <p>
 * The level reference is <i>not</i> weak (unlike Bukkit's world reference). Callers holding a Location across a level
 * unload must null it out themselves; {@link #isWorldLoaded()} reports whether the level is still live.
 */
public class Location implements Cloneable {
    private ServerLevel level;
    private float pitch;
    private float yaw;
    private double x;
    private double y;
    private double z;

    public Location(ServerLevel level, double x, double y, double z) {
        this(level, x, y, z, 0.0F, 0.0F);
    }

    public Location(ServerLevel level, double x, double y, double z, float yaw, float pitch) {
        this.level = level;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public Location add(double x, double y, double z) {
        this.x += x;
        this.y += y;
        this.z += z;
        return this;
    }

    public Location add(Location other) {
        checkSameWorld(other);
        return add(other.x, other.y, other.z);
    }

    public Location add(Vec3 vec) {
        return add(vec.x, vec.y, vec.z);
    }

    private void checkSameWorld(Location other) {
        if (other == null || other.level != level)
            throw new IllegalArgumentException("cannot combine locations from different levels");
    }

    @Override
    public Location clone() {
        return new Location(level, x, y, z, yaw, pitch);
    }

    /**
     * @return the distance to {@code other}, or {@link Double#NaN} if the levels differ
     */
    public double distance(Location other) {
        return Math.sqrt(distanceSquared(other));
    }

    /**
     * @return the squared distance to {@code other}, or {@link Double#NaN} if the levels differ
     */
    public double distanceSquared(Location other) {
        if (other == null || other.level != level)
            return Double.NaN;
        double dx = x - other.x, dy = y - other.y, dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!(obj instanceof Location))
            return false;
        Location other = (Location) obj;
        return level == other.level && Double.compare(x, other.x) == 0 && Double.compare(y, other.y) == 0
                && Double.compare(z, other.z) == 0 && Float.compare(yaw, other.yaw) == 0
                && Float.compare(pitch, other.pitch) == 0;
    }

    public BlockPos getBlockPos() {
        return BlockPos.containing(x, y, z);
    }

    public BlockState getBlockState() {
        return level == null ? null : level.getBlockState(getBlockPos());
    }

    public int getBlockX() {
        return floor(x);
    }

    public int getBlockY() {
        return floor(y);
    }

    public int getBlockZ() {
        return floor(z);
    }

    public LevelChunk getChunk() {
        return level == null ? null : level.getChunk(getBlockX() >> 4, getBlockZ() >> 4);
    }

    public ChunkPos getChunkPos() {
        return new ChunkPos(getBlockX() >> 4, getBlockZ() >> 4);
    }

    /**
     * @return a unit vector pointing in the direction described by {@link #getYaw()} and {@link #getPitch()}
     */
    public Vec3 getDirection() {
        double rotX = Math.toRadians(yaw), rotY = Math.toRadians(pitch);
        double xz = Math.cos(rotY);
        return new Vec3(-xz * Math.sin(rotX), -Math.sin(rotY), xz * Math.cos(rotX));
    }

    public float getPitch() {
        return pitch;
    }

    /**
     * @param face
     *            offset in blocks
     * @return a new Location offset from this one, keeping level and rotation
     */
    public Location getRelative(int dx, int dy, int dz) {
        return new Location(level, x + dx, y + dy, z + dz, yaw, pitch);
    }

    /** Named {@code getWorld} to match the upstream Bukkit call sites. */
    public ServerLevel getWorld() {
        return level;
    }

    public double getX() {
        return x;
    }

    public float getYaw() {
        return yaw;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    @Override
    public int hashCode() {
        return Objects.hash(System.identityHashCode(level), x, y, z, yaw, pitch);
    }

    public boolean isChunkLoaded() {
        return level != null && level.hasChunk(getBlockX() >> 4, getBlockZ() >> 4);
    }

    public boolean isWorldLoaded() {
        return level != null && level.getServer() != null && level.getServer().getLevel(level.dimension()) == level;
    }

    public Location multiply(double factor) {
        x *= factor;
        y *= factor;
        z *= factor;
        return this;
    }

    public Location setDirection(Vec3 vec) {
        double dx = vec.x, dz = vec.z;
        if (dx == 0 && dz == 0) {
            pitch = vec.y > 0 ? -90 : 90;
            return this;
        }
        double twoPi = 2 * Math.PI;
        yaw = (float) Math.toDegrees((Math.atan2(-dx, dz) + twoPi) % twoPi);
        pitch = (float) Math.toDegrees(Math.atan(-vec.y / Math.sqrt(dx * dx + dz * dz)));
        return this;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

    public void setWorld(ServerLevel level) {
        this.level = level;
    }

    public void setX(double x) {
        this.x = x;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public void setY(double y) {
        this.y = y;
    }

    public void setZ(double z) {
        this.z = z;
    }

    public Location subtract(double x, double y, double z) {
        return add(-x, -y, -z);
    }

    public Location subtract(Location other) {
        checkSameWorld(other);
        return add(-other.x, -other.y, -other.z);
    }

    public Location subtract(Vec3 vec) {
        return add(-vec.x, -vec.y, -vec.z);
    }

    @Override
    public String toString() {
        return "Location{level=" + (level == null ? "null" : level.dimension().location()) + ", x=" + x + ", y=" + y
                + ", z=" + z + ", yaw=" + yaw + ", pitch=" + pitch + "}";
    }

    public Vec3 toVec3() {
        return new Vec3(x, y, z);
    }

    /** Upstream alias for {@link #toVec3()}. */
    public Vec3 toVector() {
        return toVec3();
    }

    public double lengthSquared() {
        return x * x + y * y + z * z;
    }

    public Location zero() {
        x = y = z = 0;
        return this;
    }

    private static int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    /**
     * Looks up a level by its registry key, e.g. {@code minecraft:the_nether} or the bare {@code the_nether}.
     * <p>
     * Upstream takes Bukkit world names, which are directory names with no namespace; a level's identity here is its
     * dimension key, which is also what {@code /execute in} accepts.
     *
     * @return the level, or null if the server has none by that name
     */
    public static ServerLevel levelByName(String name, net.minecraft.server.MinecraftServer server) {
        if (name == null || name.isEmpty() || server == null)
            return null;
        ResourceLocation id = ResourceLocation.tryParse(name.contains(":") ? name : "minecraft:" + name);
        if (id == null)
            return null;
        return server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, id));
    }

    public static Location fromBlockPos(ServerLevel level, BlockPos pos) {
        return new Location(level, pos.getX(), pos.getY(), pos.getZ());
    }

    /** The entity's current position and rotation. Body yaw is used, not head yaw. */
    public static Location fromEntity(Entity entity) {
        return entity == null || !(entity.level() instanceof ServerLevel) ? null
                : new Location((ServerLevel) entity.level(), entity.getX(), entity.getY(), entity.getZ(),
                        entity.getYRot(), entity.getXRot());
    }

    /** The centre of the block containing {@code pos}, at the block's floor. */
    public static Location fromBlockPosCentred(ServerLevel level, BlockPos pos) {
        return new Location(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    public static Location of(ServerLevel level, Vec3 vec) {
        return new Location(level, vec.x, vec.y, vec.z);
    }

    public static Location of(ServerLevel level, Vec3 vec, float yaw, float pitch) {
        return new Location(level, vec.x, vec.y, vec.z, yaw, pitch);
    }

    /** Alias for {@link #fromEntity(Entity)}. */
    public static Location of(Entity entity) {
        return fromEntity(entity);
    }
}
