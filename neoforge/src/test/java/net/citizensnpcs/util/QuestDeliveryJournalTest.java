package net.citizensnpcs.util;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuestDeliveryJournalTest {
    @TempDir Path directory;
    private final UUID actor = UUID.randomUUID(), team = UUID.randomUUID();
    private QuestDeliveryJournal.Target target(long progress) {
        return new QuestDeliveryJournal.Target(team, "a1", "a2", 0, 3, progress, progress == 3, "a".repeat(64));
    }
    @Test void restartRetainsUnknownOutcomeAndFencesBothInventoryAndTeam() throws Exception {
        UUID id;
        try (var journal = QuestDeliveryJournal.open(directory)) {
            id = journal.prepare(actor, target(0), "[{\"count\":3,\"components\":{\"name\":\"original\"}}]").id();
        }
        try (var journal = QuestDeliveryJournal.open(directory)) {
            var retained = journal.get(id);
            assertEquals(QuestDeliveryJournal.State.REVIEW, retained.state());
            assertTrue(retained.inventoryBefore().contains("original"));
            assertNull(retained.after());
            assertThrows(IllegalStateException.class, () -> journal.prepare(actor,
                    new QuestDeliveryJournal.Target(UUID.randomUUID(), "a3", "a4", 0, 3, 0, false, "b".repeat(64)), "[]"));
            assertThrows(IllegalStateException.class, () -> journal.prepare(UUID.randomUUID(), target(0), "[]"));
            journal.resolve(id, UUID.randomUUID(), false, "Native inventory and progress were restored from the same stopped backup");
            assertNotNull(journal.prepare(actor, target(0), "[]"));
        }
    }
    @Test void confirmedPartialDeliveriesRetainTheActorChainAndExactImages() throws Exception {
        UUID first, second;
        try (var journal = QuestDeliveryJournal.open(directory)) {
            first = journal.prepare(actor, target(0), "[{\"count\":2}]").id();
            journal.confirm(first, target(2), "[]");
            second = journal.prepare(actor, target(2), "[{\"count\":1}]").id();
            journal.confirm(second, target(3), "[]");
        }
        try (var journal = QuestDeliveryJournal.open(directory)) {
            assertEquals(first, journal.get(second).previousActorEntry());
            assertEquals(second, journal.latest(actor).orElseThrow().id());
            assertEquals("[{\"count\":2}]", journal.get(first).inventoryBefore());
            assertEquals(QuestDeliveryJournal.State.CONFIRMED, journal.get(first).state());
            assertTrue(target(3).retains(target(2)));
            assertFalse(target(0).retains(target(2)));
        }
    }
    @Test void reviewAndResolutionNeverRewriteTheNativeOutcome() throws Exception {
        try (var journal = QuestDeliveryJournal.open(directory)) {
            var entry = journal.prepare(actor, target(0), "[{\"count\":3}]");
            journal.confirm(entry.id(), target(3), "[]");
            journal.review(entry.id(), "Player file predates this delivery");
            UUID operator = UUID.randomUUID();
            var resolved = journal.resolve(entry.id(), operator, true, "Compared saved component-sensitive images and native team data");
            assertEquals(target(3), resolved.after());
            assertEquals("[]", resolved.inventoryAfter());
            assertEquals(operator, resolved.resolution().operatorId());
            assertThrows(IllegalStateException.class, () -> journal.confirm(entry.id(), target(3), "[]"));
        }
    }
    @Test void changedDefinitionsNoProgressAndMissingOperatorReasonsAreRejected() throws Exception {
        try (var journal = QuestDeliveryJournal.open(directory)) {
            var entry = journal.prepare(actor, target(0), "[]");
            var changed = new QuestDeliveryJournal.Target(team, "a1", "a2", 0, 3, 3, true, "b".repeat(64));
            assertThrows(IllegalArgumentException.class, () -> journal.confirm(entry.id(), changed, "[]"));
            assertThrows(IllegalArgumentException.class, () -> journal.confirm(entry.id(), target(0), "[]"));
            assertEquals(QuestDeliveryJournal.State.PREPARED, journal.get(entry.id()).state());
            journal.review(entry.id(), "Native provider changed");
            assertThrows(IllegalArgumentException.class, () -> journal.resolve(entry.id(), UUID.randomUUID(), false, " "));
        }
    }
    @Test void exclusiveWriterAndOwnerThreadAreRequired() throws Exception {
        try (var journal = QuestDeliveryJournal.open(directory)) {
            assertThrows(IOException.class, () -> QuestDeliveryJournal.open(directory));
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread other = new Thread(() -> { try { journal.entries(); } catch (Throwable thrown) { failure.set(thrown); } });
            other.start(); other.join(); assertInstanceOf(IllegalStateException.class, failure.get());
        }
    }
    @Test void missingImmutableRevisionFailsOpeningWithoutRepair() throws Exception {
        try (var journal = QuestDeliveryJournal.open(directory)) {
            var entry = journal.prepare(actor, target(0), "[]"); journal.confirm(entry.id(), target(3), "[]");
        }
        try (var files = Files.list(directory)) {
            Files.delete(files.filter(path -> path.getFileName().toString().endsWith("-0.json")).findFirst().orElseThrow());
        }
        assertThrows(IOException.class, () -> QuestDeliveryJournal.open(directory));
    }
    @Test void incompleteWritesAreRetainedAndBlockOpening() throws Exception {
        Path incomplete = directory.resolve("delivery-1-incomplete.json.tmp-test"); Files.writeString(incomplete, "{");
        assertThrows(IOException.class, () -> QuestDeliveryJournal.open(directory)); assertTrue(Files.exists(incomplete));
    }
    @Test void cycleAdvanceIsDistinctFromProgressRollback() {
        var next = new QuestDeliveryJournal.Target(team, "a1", "a2", 1, 3, 0, false, "a".repeat(64));
        assertTrue(next.retains(target(3))); assertFalse(target(3).retains(next));
    }
}
