package net.citizensnpcs.util;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.List;
import java.util.Comparator;
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
    private void queriesMatchHistory(QuestDeliveryJournal journal, List<UUID> actors, List<QuestDeliveryJournal.Target> targets) {
        var retained = journal.entries();
        for (var actor : actors) {
            assertEquals(retained.stream().filter(e -> e.actorId().equals(actor))
                    .max(Comparator.comparingLong(QuestDeliveryJournal.Entry::sequence)), journal.latest(actor));
            for (var target : targets) {
                assertEquals(retained.stream().filter(QuestDeliveryJournal::pending).filter(e -> e.actorId().equals(actor)
                        || e.before().teamId().equals(target.teamId()) && e.before().taskId().equals(target.taskId()))
                        .min(Comparator.comparingLong(QuestDeliveryJournal.Entry::sequence)), journal.pending(actor, target));
            }
        }
        for (var target : targets) {
            assertEquals(retained.stream().filter(e -> e.before().teamId().equals(target.teamId())
                    && e.before().taskId().equals(target.taskId()))
                    .max(Comparator.comparingLong(QuestDeliveryJournal.Entry::sequence)), journal.latestTask(target));
        }
    }
    @Test void indexedQueriesPreserveEveryHistoricalReviewAndTheLatestActorAndTaskAcrossReopen() throws Exception {
        UUID other = UUID.randomUUID();
        var actors = List.of(actor, other, UUID.randomUUID());
        var first = target(0);
        var second = new QuestDeliveryJournal.Target(team, "b1", "b2", 0, 3, 0, false, "b".repeat(64));
        var secondAfter = new QuestDeliveryJournal.Target(team, "b1", "b2", 0, 3, 3, true, "b".repeat(64));
        // Query ownership stays actor or team/task, independently of today's quest/cycle/definition.
        var targets = List.of(first, second,
                new QuestDeliveryJournal.Target(team, "a1", "ff", 8, 5, 1, false, "c".repeat(64)),
                new QuestDeliveryJournal.Target(UUID.randomUUID(), "a1", "a2", 0, 3, 0, false, "a".repeat(64)));
        var ids = new java.util.ArrayList<UUID>();
        try (var journal = QuestDeliveryJournal.open(directory)) {
            for (int i = 0; i < 4; i++) {
                boolean onFirst = i == 0 || i == 3;
                var entry = journal.prepare(i % 2 == 0 ? actor : other, onFirst ? first : second, "[]");
                ids.add(entry.id()); queriesMatchHistory(journal, actors, targets);
                journal.confirm(entry.id(), onFirst ? target(3) : secondAfter, "[]");
                queriesMatchHistory(journal, actors, targets);
            }
            for (var id : ids) {
                journal.review(id, "Original native save requires inspection");
                queriesMatchHistory(journal, actors, targets);
            }
        }
        try (var journal = QuestDeliveryJournal.open(directory)) {
            queriesMatchHistory(journal, actors, targets);
            assertEquals(ids.get(2), journal.latest(actor).orElseThrow().id());
            assertEquals(ids.get(3), journal.latestTask(first).orElseThrow().id());
            assertEquals(ids.getFirst(), journal.pending(actor, second).orElseThrow().id());
            for (int index : new int[] {0, 2, 1}) {
                journal.resolve(ids.get(index), UUID.randomUUID(), false, "Inspected retained native effects");
                queriesMatchHistory(journal, actors, targets);
            }
            // Another actor/task still needs review, but this independent scope may proceed.
            var entry = journal.prepare(actor, second, "[]");
            journal.confirm(entry.id(), secondAfter, "[]");
            queriesMatchHistory(journal, actors, targets);
            journal.resolve(ids.get(3), UUID.randomUUID(), true, "Inspected original native outcome");
            queriesMatchHistory(journal, actors, targets);
            assertTrue(journal.pending(actor, first).isEmpty());
        }
        try (var journal = QuestDeliveryJournal.open(directory)) { queriesMatchHistory(journal, actors, targets); }
    }
    @Test void startupRecoveryIndexesEachPreparedActorAndTaskWithoutDroppingOtherFences() throws Exception {
        UUID other = UUID.randomUUID(), fresh = UUID.randomUUID();
        var elsewhere = new QuestDeliveryJournal.Target(UUID.randomUUID(), "b1", "b2", 0, 3, 0, false, "b".repeat(64));
        var actors = List.of(actor, other, fresh); var targets = List.of(target(0), elsewhere);
        UUID initial;
        try (var journal = QuestDeliveryJournal.open(directory)) { initial = journal.prepare(actor, target(0), "[]").id(); }
        try (var journal = QuestDeliveryJournal.open(directory)) {
            queriesMatchHistory(journal, actors, targets);
            assertEquals(QuestDeliveryJournal.State.REVIEW, journal.pending(fresh, target(0)).orElseThrow().state());
            journal.resolve(initial, fresh, false, "Inspected saved native inventory and task");
            journal.prepare(other, target(0), "[]"); journal.prepare(actor, elsewhere, "[]");
            queriesMatchHistory(journal, actors, targets);
        }
        try (var journal = QuestDeliveryJournal.open(directory)) {
            queriesMatchHistory(journal, actors, targets);
            var first = journal.pending(actor, target(0)).orElseThrow();
            journal.resolve(first.id(), fresh, true, "Retained native effects after inspection");
            queriesMatchHistory(journal, actors, targets);
            assertTrue(journal.pending(actor, target(0)).isPresent());
            assertEquals(QuestDeliveryJournal.State.REVIEW, journal.pending(actor, target(0)).orElseThrow().state());
        }
    }
}
