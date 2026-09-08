package net.yuuniverse.interactions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Acceptance probes kept separate from the default suite: these expose open parity defects. */
public class LegacyParityAuditTest {
    @TempDir
    Path directory;

    @Test
    void legacyPlingSoundKeepsTheUnderscoreInsideNoteBlock() {
        assertEquals("minecraft:block.note_block.pling", Actions.soundId("BLOCK_NOTE_BLOCK_PLING").toString());
    }

    @Test
    void legacyBellSoundKeepsTheUnderscoreInsideNoteBlock() {
        assertEquals("minecraft:block.note_block.bell", Actions.soundId("BLOCK_NOTE_BLOCK_BELL").toString());
    }

    @Test
    void namespacedSoundIsPreserved() {
        assertEquals("minecraft:block.note_block.pling", Actions.soundId("minecraft:block.note_block.pling").toString());
    }

    @Test
    void progressSaveDoesNotEraseExistingCooldownRecords() throws Exception {
        UUID player = UUID.randomUUID();
        Path file = directory.resolve(player + ".yml");
        Files.writeString(file, "name: AuditPlayer\nsaved_dialogues: []\ncooldowns:\n- story;1900000000000\n");
        ProgressStore store = new ProgressStore(directory.toFile());
        assertEquals(1, store.loadAll());
        store.markSeen(player, "AuditPlayer", "story.conversation1.dialogue1");
        store.saveDirty();
        assertTrue(Files.readString(file).contains("story;1900000000000"),
                "Saving dialogue progress must preserve the existing cooldown record");
    }
}
