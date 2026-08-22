package net.citizensnpcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.citizensnpcs.Settings.Setting;

/**
 * Covers the parts of {@link Settings} the port had to rewrite: reading through {@code YamlStorage} instead of Bukkit's
 * configuration API, writing missing keys back with their defaults, and honouring upstream's {@code migrateFrom} paths.
 */
public class SettingsTest {
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
