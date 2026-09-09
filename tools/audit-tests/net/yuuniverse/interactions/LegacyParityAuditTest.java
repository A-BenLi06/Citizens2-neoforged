package net.yuuniverse.interactions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

/** Acceptance probes kept separate from the default suite: these expose open parity defects. */
public class LegacyParityAuditTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.server.Bootstrap.bootStrap();
    }
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
    void bukkitCommandPrefixDoesNotRewriteItemArguments() {
        String args = " @s minecraft:paper[minecraft:custom_name='\"minecraft:give\"'] 1";
        assertEquals("give" + args, LegacyCommand.normalize("/minecraft:give" + args, "give"::equals));
        assertEquals("setblock\t~ ~ ~ minecraft:stone",
                LegacyCommand.normalize("minecraft:setblock\t~ ~ ~ minecraft:stone", "setblock"::equals));
    }

    @Test
    void registeredNamespacedAndModCommandsKeepTheirIdentity() {
        assertEquals("minecraft:give @s stone", LegacyCommand.normalize("minecraft:give @s stone", name -> true));
        assertEquals("other:give @s stone", LegacyCommand.normalize("other:give @s stone", "give"::equals));
        assertEquals("minecraft:missing", LegacyCommand.normalize("minecraft:missing", name -> false));
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

    @Test
    void cooldownsUseLegacyStartTimesAndRemainIndependentAfterRestart() {
        UUID player = UUID.randomUUID();
        ProgressStore store = new ProgressStore(directory.toFile());
        store.setCooldown(player, "Player", "bank", 100000);
        store.setCooldown(player, "Player", "station", 110000);
        store.saveDirty();
        ProgressStore restart = new ProgressStore(directory.toFile());
        assertEquals(1, restart.loadAll());
        assertTrue(restart.isCoolingDown(player, "bank", 30, 120000));
        assertFalse(restart.isCoolingDown(player, "bank", 30, 130000));
        assertFalse(restart.isCoolingDown(player, "unrelated", 3600, 120000));
        assertTrue(restart.isCoolingDown(player, "station", 30, 130000));
    }

    @Test
    void yamlSensitiveDialogueKeysAndUnknownFieldsSurvive() throws Exception {
        UUID player = UUID.randomUUID();
        Path file = directory.resolve(player + ".yml");
        Files.writeString(file, "influence: [faction;25]\nsaved_dialogues: []\ncooldowns: []\n");
        ProgressStore store = new ProgressStore(directory.toFile());
        store.loadAll();
        String key = "story: #one.conversation1.dialogue1";
        store.markSeen(player, "Player", key);
        store.saveDirty();
        store.loadAll();
        assertTrue(store.hasSeen(player, key));
        assertTrue(Files.readString(file).contains("faction;25"));
    }

    @Test
    void corruptRecordCannotBeReplacedWithEmptyProgress() throws Exception {
        UUID player = UUID.randomUUID();
        Path file = directory.resolve(player + ".yml");
        String invalid = "saved_dialogues: [unterminated";
        Files.writeString(file, invalid);
        ProgressStore store = new ProgressStore(directory.toFile());
        assertEquals(0, store.loadAll());
        assertFalse(store.isReadable(player));
        assertThrows(IllegalStateException.class, () -> store.markSeen(player, "Player", "new"));
        store.saveDirty();
        assertEquals(invalid, Files.readString(file));
    }

    @Test
    void randomDialogueCanChooseEveryLineWithoutChangingStoredOrder() {
        Conversation.Node node = new Conversation.Node("conversation1");
        node.randomDialogue = true;
        for (int i = 0; i < 3; i++) {
            Conversation.Line line = new Conversation.Line();
            line.key = "dialogue" + i;
            node.lines.add(line);
        }
        var picked = new java.util.HashSet<String>();
        var random = net.minecraft.util.RandomSource.create(71235);
        for (int i = 0; i < 100; i++)
            picked.add(node.orderedLines(random).getFirst().key);
        assertEquals(3, picked.size());
        assertEquals("dialogue0", node.lines.getFirst().key);
    }
}
