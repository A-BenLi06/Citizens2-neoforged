package net.yuuniverse.interactions;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Per-player dialogue progress, in the same files the old plugin wrote.
 * <p>
 * The format is small enough to keep exactly: one file per player UUID holding {@code saved_dialogues} - entries spelled
 * {@code <conversation file>.<node>.<dialogue>} - and a {@code cooldowns} list. Keeping it means the 57 players who
 * already have progress on the old server keep it here too, rather than being sent back through story they have seen.
 * <p>
 * Writes are deliberately plain text rather than SnakeYAML's dumper: the file has two keys and the old one was written
 * this way, so a human diffing an old and a new file sees no churn.
 */
public final class ProgressStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("interactions");

    private final File folder;
    private final Map<UUID, Set<String>> seen = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();

    public ProgressStore(File folder) {
        this.folder = folder;
    }

    /** @return how many player files were found */
    public int loadAll() {
        seen.clear();
        names.clear();
        File[] files = folder.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".yml"));
        if (files == null)
            return 0;
        int loaded = 0;
        for (File file : files) {
            UUID uuid;
            try {
                uuid = UUID.fromString(file.getName().substring(0, file.getName().length() - 4));
            } catch (Exception ex) {
                continue;
            }
            try {
                read(file, uuid);
                loaded++;
            } catch (Exception ex) {
                LOGGER.error("Could not read dialogue progress {}: {}", file.getName(), ex.toString());
            }
        }
        LOGGER.info("Loaded dialogue progress for {} player(s).", loaded);
        return loaded;
    }

    @SuppressWarnings("unchecked")
    private void read(File file, UUID uuid) throws Exception {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(8 * 1024 * 1024);
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            Object loaded = new Yaml(options).load(reader);
            if (!(loaded instanceof Map))
                return;
            Map<String, Object> root = (Map<String, Object>) loaded;
            if (root.get("name") != null) {
                names.put(uuid, String.valueOf(root.get("name")));
            }
            Set<String> entries = new LinkedHashSet<>();
            if (root.get("saved_dialogues") instanceof List<?> list) {
                for (Object entry : list) {
                    entries.add(String.valueOf(entry));
                }
            }
            seen.put(uuid, entries);
        }
    }

    public boolean hasSeen(UUID player, String key) {
        Set<String> entries = seen.get(player);
        return entries != null && entries.contains(key);
    }

    public void markSeen(UUID player, String name, String key) {
        seen.computeIfAbsent(player, u -> new LinkedHashSet<>()).add(key);
        if (name != null) {
            names.put(player, name);
        }
        dirty.add(player);
    }

    /** Writes only what changed, so a server with hundreds of players does not rewrite every file on a save. */
    public void saveDirty() {
        if (dirty.isEmpty())
            return;
        List<UUID> pending = new ArrayList<>(dirty);
        dirty.removeAll(pending);
        folder.mkdirs();
        for (UUID uuid : pending) {
            File file = new File(folder, uuid + ".yml");
            try (PrintWriter writer = new PrintWriter(file, StandardCharsets.UTF_8)) {
                writer.println("name: " + names.getOrDefault(uuid, ""));
                writer.println("saved_dialogues:");
                for (String entry : seen.getOrDefault(uuid, Set.of())) {
                    writer.println("- " + entry);
                }
                writer.println("cooldowns: []");
            } catch (Exception ex) {
                LOGGER.error("Could not write dialogue progress for {}: {}", uuid, ex.toString());
                dirty.add(uuid);
            }
        }
    }
}
