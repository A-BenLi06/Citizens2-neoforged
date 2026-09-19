package net.yuuniverse.interactions;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InfluenceTest {
    @TempDir Path folder;

    @Test void legacyRecordsRoundTripWithoutLosingOtherFields() throws Exception {
        UUID player = UUID.randomUUID();
        Path file = folder.resolve(player + ".yml");
        Files.writeString(file, "name: Previous\ninfluence: ['Faction;25', 'retired;-7', 'npc.v1_伍德;-2147483648']\n"
                + "saved_dialogues: [story.node.line]\ncooldowns: ['story;1234']\ncustom: {keep: yes}\n");
        var store = new ProgressStore(folder.toFile());
        assertEquals(1, store.loadAll());
        assertEquals(25, store.getInfluence(player, "Faction"));
        assertEquals(0, store.getInfluence(player, "faction"));
        assertEquals(0, store.getInfluence(UUID.randomUUID(), "Faction"));
        assertEquals(Integer.MIN_VALUE, store.getInfluence(player, "npc.v1_伍德"));
        assertEquals(-5, store.changeInfluence(player, "Renamed", "Faction", value -> Influence.Operation.REMOVE.apply(value, 30)));
        store.saveDirty();
        var restart = new ProgressStore(folder.toFile());
        assertEquals(1, restart.loadAll());
        assertEquals(-5, restart.getInfluence(player, "Faction"));
        assertEquals(-7, restart.getInfluence(player, "retired"));
        assertTrue(restart.hasSeen(player, "story.node.line"));
        assertEquals(1234, restart.cooldownStartedAt(player, "story"));
        var record = (java.util.Map<?, ?>) new org.yaml.snakeyaml.Yaml().load(Files.readString(file));
        assertEquals("Renamed", record.get("name"));
        assertEquals(java.util.Map.of("keep", true), record.get("custom"));
    }

    @Test void invalidInfluenceRecordsStayUntouched() throws Exception {
        for (String field : java.util.List.of("influence: wrong", "influence: [12]", "influence: [missing]",
                "influence: ['npc;2147483648']", "influence: ['npc;NaN']", "influence: [';1']",
                "influence: ['npc;1', 'npc;2']", "influence: ['bad;key;1']")) {
            UUID player = UUID.randomUUID();
            Path file = folder.resolve(player + ".yml");
            String original = field + "\nsaved_dialogues: []\n";
            Files.writeString(file, original);
            var store = new ProgressStore(folder.toFile());
            store.loadAll();
            assertFalse(store.isReadable(player), field);
            assertThrows(IllegalStateException.class, () -> store.getInfluence(player, "npc"));
            assertThrows(IllegalStateException.class, () -> store.changeInfluence(player, "name", "npc", value -> 5));
            store.saveDirty();
            assertEquals(original, Files.readString(file));
        }
    }

    @Test void overflowAndInvalidDeltaDoNotMutateRecord() {
        var store = new ProgressStore(folder.toFile());
        UUID player = UUID.randomUUID();
        store.changeInfluence(player, "name", "npc", value -> Integer.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> store.changeInfluence(player, "new", "npc",
                value -> Influence.Operation.ADD.apply(value, 1)));
        assertEquals(Integer.MAX_VALUE, store.getInfluence(player, "npc"));
        for (int amount : new int[] {0, -1, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> Influence.Operation.ADD.apply(0, amount));
            assertThrows(IllegalArgumentException.class, () -> Influence.Operation.REMOVE.apply(0, amount));
        }
        assertEquals(Integer.MIN_VALUE, Influence.Operation.SET.apply(12, Integer.MIN_VALUE));
        assertThrows(ArithmeticException.class, () -> Influence.Operation.REMOVE.apply(Integer.MIN_VALUE, 1));
    }

    @Test void numericConditionsAreExactAndFailClosed() {
        assertTrue(Conditions.holds("-3 < -2", value -> value));
        assertTrue(Conditions.holds("2147483647 >= 2147483647", value -> value));
        assertTrue(Conditions.holds("-2147483648 <= -2147483648", value -> value));
        assertTrue(Conditions.holds("0.1 < 0.10000000000000001", value -> value));
        assertFalse(Conditions.holds("0 > 0", value -> value));
        assertFalse(Conditions.holds("NaN >= 0", value -> value));
        assertFalse(Conditions.holds("Infinity > 0", value -> value));
        assertFalse(Conditions.holds("unknown != 0", value -> value.equals("unknown") ? null : value));
        assertTrue(Conditions.holds("3 >= other", value -> value.equals("other") ? "2" : value));
        assertTrue(Conditions.holds("%checkitem_lorecontains:Rank > 3% == yes",
                value -> value.equals("%checkitem_lorecontains:Rank > 3%") ? "yes" : value));
    }

    @Test void adminParametersAcceptLegacyUnicodeAndQuotedNames() throws Exception {
        assertEquals(new InfluenceCommands.Parameters("伍德.v1", -2), InfluenceCommands.parse("伍德.v1\t-2", "set"));
        assertEquals(new InfluenceCommands.Parameters("faction with spaces", 3), InfluenceCommands.parse("\"faction with spaces\" 3", "add"));
        assertEquals(new InfluenceCommands.Parameters("伍德", 0), InfluenceCommands.parse("伍德", "get"));
        for (String raw : java.util.List.of("伍德 0", "伍德 -1", "伍德 1.5", "伍德 2147483648", "伍德 1 extra", "伍德", "\"伍德\"1"))
            assertThrows(IllegalArgumentException.class, () -> InfluenceCommands.parse(raw, "add"));
        assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class, () -> InfluenceCommands.parse("\"broken 2", "set"));
        assertThrows(IllegalArgumentException.class, () -> InfluenceCommands.parse("伍德 1", "get"));
    }
}
