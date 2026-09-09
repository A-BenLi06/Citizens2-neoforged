package net.yuuniverse.interactions;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Preserves legacy player records, including conversation-scoped cooldown start timestamps. */
public final class ProgressStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("interactions");
    private final File folder;
    private final Map<UUID, Map<String, Object>> records = new HashMap<>();
    private final Set<UUID> dirty = new LinkedHashSet<>();
    private final Set<UUID> unreadable = new LinkedHashSet<>();

    public ProgressStore(File folder) {
        this.folder = folder;
    }

    public synchronized int loadAll() {
        if (!dirty.isEmpty()) {
            saveDirty();
            if (!dirty.isEmpty())
                throw new IllegalStateException("Unsaved dialogue progress cannot be discarded by reload");
        }
        records.clear();
        unreadable.clear();
        File[] files = folder.listFiles(f -> f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null)
            return 0;
        for (File file : files) {
            UUID uuid;
            try {
                uuid = UUID.fromString(file.getName().substring(0, file.getName().length() - 4));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            try (var reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                LoaderOptions options = new LoaderOptions();
                options.setCodePointLimit(8 * 1024 * 1024);
                Object loaded = new Yaml(new SafeConstructor(options)).load(reader);
                if (!(loaded instanceof Map<?, ?> map))
                    throw new IOException("Player record must be a YAML mapping");
                Map<String, Object> record = new LinkedHashMap<>();
                map.forEach((key, value) -> record.put(String.valueOf(key), value));
                for (String field : List.of("saved_dialogues", "cooldowns")) {
                    if (record.get(field) != null && !(record.get(field) instanceof List<?>))
                        throw new IOException(field + " must be a YAML list");
                }
                records.put(uuid, record);
            } catch (Exception ex) {
                unreadable.add(uuid);
                LOGGER.error("Could not read {}; the original record will not be overwritten", file.getName(), ex);
            }
        }
        LOGGER.info("Loaded dialogue progress for {} player(s).", records.size());
        return records.size();
    }

    public synchronized boolean isReadable(UUID player) {
        return !unreadable.contains(player);
    }

    public synchronized boolean hasSeen(UUID player, String key) {
        return values(records.get(player), "saved_dialogues").contains(key);
    }

    public synchronized void markSeen(UUID player, String name, String key) {
        Map<String, Object> record = writable(player, name);
        List<Object> seen = new ArrayList<>(values(record, "saved_dialogues"));
        if (!seen.contains(key))
            seen.add(key);
        record.put("saved_dialogues", seen);
        dirty.add(player);
    }

    public synchronized long cooldownStartedAt(UUID player, String conversation) {
        long startedAt = 0;
        for (Object value : values(records.get(player), "cooldowns")) {
            String entry = String.valueOf(value);
            int separator = entry.lastIndexOf(';');
            if (separator < 0 || !entry.substring(0, separator).equals(conversation))
                continue;
            try {
                startedAt = Math.max(startedAt, Long.parseLong(entry.substring(separator + 1)));
            } catch (NumberFormatException ignored) {
                // Preserve unknown records rather than destroying them during a write.
            }
        }
        return startedAt;
    }

    public synchronized boolean isCoolingDown(UUID player, String conversation, int seconds, long now) {
        long started = cooldownStartedAt(player, conversation);
        return seconds > 0 && started > 0 && now < started + seconds * 1000L;
    }

    public synchronized void setCooldown(UUID player, String name, String conversation, long startedAt) {
        Map<String, Object> record = writable(player, name);
        List<Object> cooldowns = new ArrayList<>(values(record, "cooldowns"));
        cooldowns.removeIf(value -> {
            String entry = String.valueOf(value);
            int split = entry.lastIndexOf(';');
            return split >= 0 && entry.substring(0, split).equals(conversation);
        });
        if (startedAt > 0)
            cooldowns.add(conversation + ";" + startedAt);
        record.put("cooldowns", cooldowns);
        dirty.add(player);
    }

    private Map<String, Object> writable(UUID player, String name) {
        if (!isReadable(player))
            throw new IllegalStateException("Dialogue progress is unreadable for " + player);
        Map<String, Object> record = records.computeIfAbsent(player, id -> new LinkedHashMap<>());
        if (name != null)
            record.put("name", name);
        return record;
    }

    private static List<?> values(Map<String, Object> record, String field) {
        return record != null && record.get(field) instanceof List<?> list ? list : List.of();
    }

    /** Replace only fully written records; failed writes remain dirty for retry. */
    public synchronized void saveDirty() {
        for (UUID player : new ArrayList<>(dirty)) {
            Path temporary = null;
            try {
                Files.createDirectories(folder.toPath());
                temporary = Files.createTempFile(folder.toPath(), player + ".", ".tmp");
                DumperOptions options = new DumperOptions();
                options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
                try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    new Yaml(options).dump(records.get(player), writer);
                }
                Path destination = folder.toPath().resolve(player + ".yml");
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                dirty.remove(player);
            } catch (IOException ex) {
                LOGGER.error("Could not save dialogue progress for {}; will retry", player, ex);
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException ex) {
                        LOGGER.warn("Could not remove temporary dialogue record {}", temporary, ex);
                    }
                }
            }
        }
    }
}
