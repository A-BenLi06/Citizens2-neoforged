package net.citizensnpcs.api.persistence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.Messaging;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Persists a {@link Location} as {@code world} plus {@code x/y/z/yaw/pitch}.
 * <p>
 * Upstream writes both {@code worldid} (the Bukkit world UUID) and {@code world} (the world folder name). Minecraft
 * identifies dimensions by {@link ResourceKey}, not UUID, so only {@code world} is written and it now holds a dimension
 * id such as {@code minecraft:overworld}. Existing Citizens saves stay readable: the default Bukkit world names are mapped
 * through {@link #LEGACY_WORLD_NAMES}, {@code <level>/DIM1} and {@code <level>/DIM-1} are recognised by suffix, any other
 * namespace-less name is taken to be a Bukkit level folder and treated as the overworld, and any {@code worldid} present
 * is ignored.
 */
public class LocationPersister implements Persister<Location> {
    @Override
    public Location create(DataKey root) {
        if (!root.keyExists("world"))
            return null;
        String worldId = root.getString("world");
        double x = root.getDouble("x"), y = root.getDouble("y"), z = root.getDouble("z");
        float yaw = normalise(root.getDouble("yaw")), pitch = normalise(root.getDouble("pitch"));
        ServerLevel level = resolve(worldId);
        return level == null ? new LazilyLoadedLocation(worldId, x, y, z, yaw, pitch)
                : new Location(level, x, y, z, yaw, pitch);
    }

    private float normalise(double d) {
        if (Double.isNaN(d))
            return 0F;
        return (float) (!Double.isFinite(d) ? 0 : d);
    }

    private double round(double z) {
        if (Double.isInfinite(z) || Double.isNaN(z))
            return 0F;
        return new BigDecimal(z).setScale(4, RoundingMode.HALF_DOWN).doubleValue();
    }

    @Override
    public void save(Location location, DataKey root) {
        ServerLevel level = location.getWorld();
        if (level != null) {
            root.setString("world", level.dimension().location().toString());
        } else if (location instanceof LazilyLoadedLocation) {
            // never resolved - write back what was on disk rather than dropping it
            root.setString("world", ((LazilyLoadedLocation) location).getWorldId());
        }
        root.setDouble("x", round(location.getX()));
        root.setDouble("y", round(location.getY()));
        root.setDouble("z", round(location.getZ()));
        root.setDouble("yaw", round(location.getYaw()));
        root.setDouble("pitch", round(location.getPitch()));
    }

    /**
     * Resolves a stored world id to a live level.
     * <p>
     * Accepts a dimension id ({@code minecraft:the_nether}), a bare path ({@code the_nether}), or one of the Bukkit
     * world folder names that appear in saves written by the plugin. Shared with {@code Anchor}, which stores world
     * names in the same way.
     *
     * @return the level, or null if there is no server or no match
     */
    public static ServerLevel resolve(String worldId) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || worldId == null || worldId.isEmpty())
            return null;
        String lower = worldId.toLowerCase(Locale.ROOT);
        String mapped = LEGACY_WORLD_NAMES.get(lower);
        if (mapped == null) {
            // Bukkit-on-Forge names the other two dimensions after the level folder: "<level>/DIM-1" and "<level>/DIM1".
            // The level name is whatever the server operator chose, so it cannot be matched by name - only the suffix can.
            if (lower.endsWith("/dim1")) {
                mapped = "minecraft:the_end";
            } else if (lower.endsWith("/dim-1")) {
                mapped = "minecraft:the_nether";
            } else {
                mapped = worldId;
            }
        }
        ResourceLocation location = ResourceLocation.read(mapped).result().orElse(null);
        if (location != null) {
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, location));
            if (level != null)
                return level;
            // tolerate a saved bare path such as "overworld"
            for (ServerLevel candidate : server.getAllLevels()) {
                if (candidate.dimension().location().getPath().equals(location.getPath()))
                    return candidate;
            }
        }
        // A name with no namespace that matches no dimension is a Bukkit world folder: on a Bukkit server the overworld
        // is named after the level folder, so a custom level-name ("uDays") arrives here and would otherwise resolve to
        // nothing, leaving every NPC in it permanently unspawned. Such a name may also be invalid as a ResourceLocation
        // outright, since those reject capitals. Falling back to the overworld is what the name meant on the old server;
        // it is logged because a genuinely separate world (a Multiverse one, say) would land there too.
        if (!worldId.contains(":")) {
            ServerLevel overworld = server.overworld();
            if (WARNED_WORLD_NAMES.add(lower)) {
                Messaging.severe("No dimension matches the stored world name", worldId,
                        "- treating it as the overworld, which is what a Bukkit level folder name means. NPCs saved in a"
                                + " world that no longer exists will appear in the overworld at their old coordinates.");
            }
            return overworld;
        }
        return null;
    }

    /**
     * A Location whose level could not be resolved at load time; resolved on first access instead.
     * <p>
     * Less load-bearing than upstream, since Minecraft loads every dimension during server startup, but kept because
     * NPC data can be read before a server exists and because callers already handle the type.
     */
    public static class LazilyLoadedLocation extends Location {
        private final String worldId;

        public LazilyLoadedLocation(String worldId, double x, double y, double z, float yaw, float pitch) {
            super(null, x, y, z, yaw, pitch);
            this.worldId = worldId;
        }

        @Override
        public ServerLevel getWorld() {
            if (super.getWorld() == null) {
                setWorld(resolve(worldId));
            }
            return super.getWorld();
        }

        public String getWorldId() {
            return worldId;
        }
    }

    /** Default Bukkit world folder names as they appear in existing Citizens saves. */
    private static final Map<String, String> LEGACY_WORLD_NAMES = Map.of("world", "minecraft:overworld", "world_nether",
            "minecraft:the_nether", "world_the_end", "minecraft:the_end");
    /** So the fallback is reported once per world name rather than once per NPC. */
    private static final Set<String> WARNED_WORLD_NAMES = ConcurrentHashMap.newKeySet();
}
