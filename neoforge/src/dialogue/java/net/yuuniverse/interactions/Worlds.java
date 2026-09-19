package net.yuuniverse.interactions;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Resolves loaded dimension identities and explicitly mapped legacy world names without guessing a destination.
 */
public final class Worlds {
    private static volatile Map<String, ResourceLocation> aliases = Map.of();
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");
    private static final ResourceLocation NETHER = ResourceLocation.withDefaultNamespace("the_nether");
    private static final ResourceLocation END = ResourceLocation.withDefaultNamespace("the_end");

    private Worlds() {
    }

    public static ServerLevel resolve(MinecraftServer server, String bukkitName) {
        if (server == null) return null;
        Set<ResourceLocation> loaded = new LinkedHashSet<>();
        for (ServerLevel level : server.getAllLevels()) loaded.add(level.dimension().location());
        ResourceLocation id = resolveId(bukkitName, server.getWorldData().getLevelName(), loaded);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    static ResourceLocation resolveId(String requested, String currentWorldName, Set<ResourceLocation> loaded) {
        return resolveId(requested, currentWorldName, loaded, aliases);
    }

    /** Alias maps have the normalized keys returned by readAliases. Native IDs cannot be shadowed by an alias. */
    static ResourceLocation resolveId(String requested, String currentWorldName, Set<ResourceLocation> loaded,
            Map<String, ResourceLocation> configured) {
        if (requested == null || requested.isBlank()) return null;
        String name = requested.toLowerCase(Locale.ENGLISH);
        if (name.indexOf(':') >= 0) {
            ResourceLocation id = ResourceLocation.tryParse(name);
            return id != null && id.toString().equals(name) && loaded.contains(id) ? id : null;
        }
        if (configured.containsKey(name)) {
            ResourceLocation id = configured.get(name);
            return loaded.contains(id) ? id : null;
        }
        String root = currentWorldName == null ? "" : currentWorldName.toLowerCase(Locale.ENGLISH);
        Set<ResourceLocation> matches = new LinkedHashSet<>();
        for (ResourceLocation id : loaded) {
            if (id.getPath().equals(name)) matches.add(id);
            if (!root.isEmpty()) {
                if (id.equals(OVERWORLD) && root.equals(name)) matches.add(id);
                else if (id.equals(NETHER) && (name.equals(root + "/dim-1") || name.equals(root + "_nether"))) matches.add(id);
                else if (id.equals(END) && (name.equals(root + "/dim1") || name.equals(root + "_the_end"))) matches.add(id);
                else if (!id.equals(OVERWORLD) && !id.equals(NETHER) && !id.equals(END)
                        && (name.equals(root + "/" + id.getNamespace() + "/" + id.getPath())
                            || name.equals(root + "_" + (id.getNamespace() + "_" + id.getPath()).replace('/', '_'))))
                    matches.add(id);
            }
        }
        return matches.size() == 1 ? matches.iterator().next() : null;
    }

    /** Publish only complete valid maps; a failed reload leaves the last valid aliases available. */
    public static boolean load(File file) {
        try {
            if (!Files.exists(file.toPath())) {
                Files.createDirectories(file.toPath().toAbsolutePath().getParent());
                try {
                    Files.writeString(file.toPath(), "# Legacy world names mapped to canonical dimension IDs.\n"
                            + "# Names are case-insensitive; targets must be loaded dimensions.\n"
                            + "# Example: 'legacy_world': 'minecraft:overworld'\n{}\n",
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    // Read the configuration created by another writer instead of replacing it.
                }
            }
            Map<String, ResourceLocation> updated = readAliases(file);
            aliases = updated;
            LoggerFactory.getLogger("interactions").info("Loaded {} world alias(es).", updated.size());
            return true;
        } catch (Exception failure) {
            LoggerFactory.getLogger("interactions").error("Could not read {}; retaining previous world aliases",
                    file, failure);
            return false;
        }
    }

    static Map<String, ResourceLocation> readAliases(File file) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setCodePointLimit(1024 * 1024);
        try (var reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Object loaded = new Yaml(new SafeConstructor(options)).load(reader);
            if (!(loaded instanceof Map<?, ?> values)) throw new IllegalArgumentException("Expected a world alias map");
            Map<String, ResourceLocation> result = new LinkedHashMap<>();
            for (var entry : values.entrySet()) {
                if (!(entry.getKey() instanceof String name) || name.isBlank() || name.indexOf(':') >= 0
                        || !(entry.getValue() instanceof String target) || target.indexOf(':') < 0)
                    throw new IllegalArgumentException("Expected a legacy world name and a namespaced dimension ID");
                ResourceLocation id = ResourceLocation.tryParse(target);
                if (id == null || !id.toString().equals(target))
                    throw new IllegalArgumentException("Invalid world alias dimension ID: " + target);
                if (result.putIfAbsent(name.toLowerCase(Locale.ENGLISH), id) != null)
                    throw new IllegalArgumentException("Duplicate world alias: " + name);
            }
            return Map.copyOf(result);
        }
    }

    static void clear() { aliases = Map.of(); }
}
