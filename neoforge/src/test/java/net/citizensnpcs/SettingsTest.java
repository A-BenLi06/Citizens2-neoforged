package net.citizensnpcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.Storage;
import net.citizensnpcs.api.util.YamlStorage;

/**
 * Covers the parts of {@link Settings} the port had to rewrite: reading through {@code YamlStorage} instead of Bukkit's
 * configuration API, writing missing keys back with their defaults, and honouring upstream's {@code migrateFrom} paths.
 */
public class SettingsTest {
    @Test
    public void enclosingReloadCanRestoreRuntimeValuesAndDurationIdentity(@TempDir Path dir) throws IOException {
        Settings settings = new Settings(dir.toFile());
        Setting.DEFAULT_NPC_LIMIT.set(37);
        Duration previous = Setting.DEFAULT_RANDOM_LOOK_DELAY.asDuration();
        Runnable restore = settings.captureRuntimeRestore();
        write(dir, "npc:\n  limits:\n    default-limit: 7\n");
        settings.reload();
        assertEquals(7, Setting.DEFAULT_NPC_LIMIT.asInt());
        restore.run();
        assertEquals(37, Setting.DEFAULT_NPC_LIMIT.asInt());
        assertSame(previous, Setting.DEFAULT_RANDOM_LOOK_DELAY.asDuration());
    }

    @Test
    public void runtimeRestoreDoesNotEnableSavingAnUnreadableSource(@TempDir Path dir) throws IOException {
        Settings settings = new Settings(dir.toFile());
        Runnable restore = settings.captureRuntimeRestore();
        String malformed = "npc: [unterminated\n";
        write(dir, malformed);
        assertThrows(IllegalStateException.class, settings::reload);
        restore.run();
        assertThrows(IllegalStateException.class, settings::save);
        assertEquals(malformed, read(dir));
    }

    private static void write(Path dir, String yaml) throws IOException {
        Files.write(dir.resolve("config.yml"), yaml.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path dir) throws IOException {
        return new String(Files.readAllBytes(dir.resolve("config.yml")), StandardCharsets.UTF_8);
    }

    @Test
    public void missingKeysAreWrittenBackWithDefaults(@TempDir Path dir) throws IOException {
        new Settings(dir.toFile());

        String written = read(dir);
        assertTrue(written.contains("default-limit: 10"), written);
        assertTrue(written.contains("range: 10"), written);
        assertEquals(10, Setting.DEFAULT_NPC_LIMIT.asInt());
        assertEquals(10.0, Setting.DEFAULT_LOOK_CLOSE_RANGE.asDouble());
    }

    @Test
    public void existingValuesAreRead(@TempDir Path dir) throws IOException {
        write(dir, "npc:\n  limits:\n    default-limit: 42\n  chat:\n    options:\n      range: 12\n");
        new Settings(dir.toFile());

        assertEquals(42, Setting.DEFAULT_NPC_LIMIT.asInt());
        assertEquals(12.0, Setting.CHAT_RANGE.asDouble());
    }

    @Test
    public void oldPathMigratesToNewOne(@TempDir Path dir) throws IOException {
        // upstream moved realistic-looking out of the look-close section; a config written before that must still work
        write(dir, "npc:\n  default:\n    look-close:\n      realistic-looking: true\n");
        new Settings(dir.toFile());

        assertTrue(Setting.DEFAULT_REALISTIC_LOOKING.asBoolean());
        String written = read(dir);
        assertTrue(written.contains("realistic-looking: true"), written);
        // the value now lives under npc.default, and the stale key is gone
        assertFalse(written.replaceAll("(?s)look-close:.*?(?=\\n  [a-z]|$)", "").contains("realistic-looking: true"),
                written);
    }

    @Test
    public void newPathWinsOverOldOne(@TempDir Path dir) throws IOException {
        write(dir, "npc:\n  default:\n    realistic-looking: false\n    look-close:\n      realistic-looking: true\n");
        new Settings(dir.toFile());

        assertFalse(Setting.DEFAULT_REALISTIC_LOOKING.asBoolean());
    }

    @Test
    public void durationsParseToTicks(@TempDir Path dir) throws IOException {
        write(dir, "npc:\n  default:\n    look-close:\n      random-look-delay: 3s\n"
                + "  tablist:\n    remove-packet-delay: 2t\n  text:\n    speech-bubble-ticks: 50t\n");
        new Settings(dir.toFile());

        assertEquals(60, Setting.DEFAULT_RANDOM_LOOK_DELAY.asTicks());
        assertEquals(2, Setting.TABLIST_REMOVE_PACKET_DELAY.asTicks());
        assertEquals(50, Setting.DEFAULT_TEXT_SPEECH_BUBBLE_DURATION.asTicks());
    }

    @Test
    public void listSettingsRoundTrip(@TempDir Path dir) throws IOException {
        write(dir, "npc:\n  default:\n    talk-close:\n      text:\n      - one\n      - two\n");
        new Settings(dir.toFile());

        List<String> text = Setting.DEFAULT_TEXT.asList();
        assertEquals(List.of("one", "two"), text);
    }

    @Test
    public void reloadPicksUpEdits(@TempDir Path dir) throws IOException {
        Settings settings = new Settings(dir.toFile());
        assertEquals(10, Setting.DEFAULT_NPC_LIMIT.asInt());

        write(dir, "npc:\n  limits:\n    default-limit: 7\n");
        settings.reload();
        assertEquals(7, Setting.DEFAULT_NPC_LIMIT.asInt());
    }

    @Test
    public void malformedReloadPreservesLiveSettingsAndFile(@TempDir Path dir) throws IOException {
        Settings settings = new Settings(dir.toFile());
        Setting.DEFAULT_NPC_LIMIT.set(37);
        Duration duration = Setting.DEFAULT_RANDOM_LOOK_DELAY.asDuration();
        Map<Setting, String> live = snapshot();
        String malformed = "npc: [unterminated\n";
        write(dir, malformed);

        assertThrows(IllegalStateException.class, settings::reload);

        assertEquals(live, snapshot());
        assertSame(duration, Setting.DEFAULT_RANDOM_LOOK_DELAY.asDuration());
        assertEquals(malformed, read(dir));
        assertThrows(IllegalStateException.class, settings::save);
        assertEquals(malformed, read(dir));
        assertThrows(IllegalStateException.class, () -> new Settings(dir.toFile()));
        assertEquals(live, snapshot());
        assertEquals(malformed, read(dir));
    }

    @Test
    public void failedMigrationSaveDoesNotPublishAnySettings(@TempDir Path dir) throws IOException {
        YamlStorage backing = new YamlStorage(dir.resolve("config.yml").toFile());
        class FailingStorage implements Storage {
            boolean failSave;
            Runnable beforeSave = () -> { };

            @Override public DataKey getKey(String root) { return backing.getKey(root); }
            @Override public boolean load() { return backing.load(); }
            @Override public void saveAsync() { save(); }
            @Override public void save() {
                beforeSave.run();
                if (failSave) throw new IllegalStateException("Expected settings write failure");
                backing.save();
            }
        }
        FailingStorage storage = new FailingStorage();
        Settings settings = new Settings(dir.resolve("config.yml").toFile(), storage);
        Duration duration = Setting.DEFAULT_RANDOM_LOOK_DELAY.asDuration();
        Map<Setting, String> live = snapshot();
        boolean debugging = Messaging.isDebugging();
        String replacement = "general:\n  debug-mode: true\nnpc:\n  limits:\n    default-limit: 72\n"
                + "  default:\n    look-close:\n      random-look-delay: 9s\n      realistic-looking: true\n";
        write(dir, replacement);
        storage.beforeSave = () -> {
            assertEquals(live, snapshot(), "Values must remain unpublished during persistence");
            assertSame(duration, Setting.DEFAULT_RANDOM_LOOK_DELAY.asDuration());
            assertEquals(debugging, Messaging.isDebugging());
        };
        storage.failSave = true;

        assertThrows(IllegalStateException.class, settings::reload);

        assertEquals(live, snapshot());
        assertSame(duration, Setting.DEFAULT_RANDOM_LOOK_DELAY.asDuration());
        assertEquals(debugging, Messaging.isDebugging());
        assertEquals(replacement, read(dir));
        storage.failSave = false;
        settings.reload();
        assertEquals(72, Setting.DEFAULT_NPC_LIMIT.asInt());
        assertEquals(180, Setting.DEFAULT_RANDOM_LOOK_DELAY.asTicks());
        assertTrue(Setting.DEFAULT_REALISTIC_LOOKING.asBoolean());
        assertTrue(Messaging.isDebugging());
        assertTrue(read(dir).contains("realistic-looking: true"));
    }

    private static Map<Setting, String> snapshot() {
        Map<Setting, String> values = new LinkedHashMap<>();
        for (Setting setting : Setting.values()) values.put(setting, setting.asString());
        return values;
    }

    @Test
    public void defaultsMatchUpstream(@TempDir Path dir) throws IOException {
        new Settings(dir.toFile());

        // these are the values a fresh upstream config.yml carries; drifting from them changes behaviour silently
        assertEquals("[<npc>]: <text>", Setting.CHAT_FORMAT.asString());
        assertEquals("<npc>: <text>", Setting.CHAT_FORMAT_TO_TARGET.asString());
        assertEquals("*", Setting.TALK_ITEM.asString());
        assertTrue(Setting.NPC_SKIN_FETCH_DEFAULT.asBoolean());
        assertFalse(Setting.NPC_SKIN_USE_LATEST.asBoolean());
        assertTrue(Setting.TALK_CLOSE_TO_NPCS.asBoolean());
        assertFalse(Setting.CHAT_BYSTANDERS_HEAR_TARGETED_CHAT.asBoolean());
        assertEquals(2, Setting.CHAT_MAX_NUMBER_OF_TARGETS.asInt());
    }

    /** The file must survive a load/save cycle without the enum's in-memory state drifting from disk. */
    @Test
    public void saveThenReloadIsStable(@TempDir Path dir) throws IOException {
        Settings settings = new Settings(dir.toFile());
        settings.save();
        String first = read(dir);

        settings.reload();
        settings.save();
        assertEquals(first, read(dir));
    }
}
