package net.yuuniverse.interactions;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.world.BossEvent;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DialogueDisplaySettingsTest {
    @TempDir Path directory;

    @Test void defaultBossBarMatchesTheOriginalConfiguration() {
        var value = DialogueSettings.DEFAULT.bossBar();
        assertTrue(value.enabled()); assertFalse(value.changeProgressWithTime());
        assertEquals(BossEvent.BossBarColor.BLUE, value.color());
        assertEquals(BossEvent.BossBarOverlay.NOTCHED_10, value.overlay());
    }

    @Test void readsEveryLegacyBossBarStyle() {
        Map<String, BossEvent.BossBarOverlay> styles = Map.of("SOLID", BossEvent.BossBarOverlay.PROGRESS,
                "SEGMENTED_6", BossEvent.BossBarOverlay.NOTCHED_6, "SEGMENTED_10", BossEvent.BossBarOverlay.NOTCHED_10,
                "SEGMENTED_12", BossEvent.BossBarOverlay.NOTCHED_12, "SEGMENTED_20", BossEvent.BossBarOverlay.NOTCHED_20);
        styles.forEach((name, overlay) -> assertEquals(overlay, BossBarSettings.read(Map.of("style", name)).overlay()));
    }

    @Test void loadsAllBossBarFieldsAndRetainsPreviousSettingsOnInvalidInput() throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, "boss_bar: {enabled: false, color: red, style: SOLID, change_progress_with_time: true}\n");
        var value = DialogueSettings.load(file.toFile(), DialogueSettings.DEFAULT);
        assertFalse(value.bossBar().enabled()); assertTrue(value.bossBar().changeProgressWithTime());
        assertEquals(BossEvent.BossBarColor.RED, value.bossBar().color());
        assertEquals(BossEvent.BossBarOverlay.PROGRESS, value.bossBar().overlay());
        Files.writeString(file, "boss_bar: {enabled: true, color: missing}\n");
        assertSame(value, DialogueSettings.load(file.toFile(), value));
        assertThrows(IllegalArgumentException.class, () -> BossBarSettings.read(Map.of("enabled", "yes")));
    }

    @Test void progressFillsOncePerSecondAndOptionsAreFull() {
        assertEquals(1, DialogueBossBar.progress(false, false, 4, 0));
        assertEquals(0, DialogueBossBar.progress(true, false, 4, 19));
        assertEquals(0.25F, DialogueBossBar.progress(true, false, 4, 20));
        assertEquals(0.25F, DialogueBossBar.progress(true, false, 4, 39));
        assertEquals(0.5F, DialogueBossBar.progress(true, false, 4, 40));
        assertEquals(1, DialogueBossBar.progress(true, false, 4, 500));
        assertEquals(1, DialogueBossBar.progress(true, true, 4, 0));
        for (double duration : new double[] {-1, 0, Double.NaN, Double.POSITIVE_INFINITY})
            assertEquals(0, DialogueBossBar.progress(true, false, duration, 100));
    }

    @Test void usesLegacyTitlesAndRemovesCenteredMarkerFromTheName() throws Exception {
        Path file = directory.resolve("messages.yml");
        Files.writeString(file, "bossBarTitleConversation: 'Talk: %name%'\nbossBarTitleSelectOption: 'Choose: %name%'\n");
        var messages = DialogueMessages.load(file.toFile(), DialogueMessages.DEFAULT);
        assertEquals("Talk: NPC", messages.bossBarTitle("{centered}&aNPC", false).getString());
        assertEquals("Choose: NPC", messages.bossBarTitle("{centered}&aNPC", true).getString());
        assertTrue(DialogueMessages.DEFAULT.bossBarTitle("NPC", false).getString().contains("NPC"));
        assertTrue(DialogueMessages.DEFAULT.bossBarTitle("NPC", true).getString().contains("Select an Option!"));
        Files.writeString(file, "bossBarTitleConversation: [wrong]\n");
        assertSame(messages, DialogueMessages.load(file.toFile(), messages));
    }

    @Test void readsHologramDefaultsAndExplicitOffsets() {
        assertEquals(new HologramSettings(false, 2.7, 0), HologramSettings.read(null));
        assertEquals(new HologramSettings(true, 3.5, -2), HologramSettings.read(
                Map.of("enabled", true, "offset_y", 3.5, "offset_horizontal", -2)));
        assertThrows(IllegalArgumentException.class, () -> HologramSettings.read(Map.of("offset_y", "high")));
        assertThrows(IllegalArgumentException.class, () -> HologramSettings.read(Map.of("offset_y", Double.NaN)));
    }

    @Test void hologramPositionsUseTheLegacyTopOffsetAndViewerDirection() {
        var settings = new HologramSettings(true, 2.7, 2);
        Vec3 position = settings.top(Vec3.ZERO, new Vec3(0, 0, -5), 0, 3);
        assertEquals(-2, position.x, 1E-8);
        assertEquals(3.1, position.y, 1E-8);
        assertEquals(0, position.z, 1E-8);
        Vec3 samePosition = settings.top(Vec3.ZERO, Vec3.ZERO, 90, 1);
        assertTrue(Double.isFinite(samePosition.x) && Double.isFinite(samePosition.y) && Double.isFinite(samePosition.z));
        assertEquals(2, samePosition.horizontalDistance(), 1E-8);
    }

    @Test void conversationLoaderPreservesHologramSettings() throws Exception {
        Files.writeString(directory.resolve("display.yml"), """
                starts_with: [NPC with id 1]
                hologram_dialogues:
                  enabled: true
                  offset_y: 3.25
                  offset_horizontal: -1.5
                conversation:
                  conversation1:
                    dialogue:
                      dialogue1: {text: [Display], time: 2}
                """);
        var library = new ConversationLibrary(); library.load(directory.toFile());
        assertEquals(new HologramSettings(true, 3.25, -1.5), library.forNpc(1).hologram);
    }
}
