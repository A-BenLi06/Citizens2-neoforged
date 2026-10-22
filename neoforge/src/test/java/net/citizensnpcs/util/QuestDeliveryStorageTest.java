package net.citizensnpcs.util;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.JsonParser;

/** Real filesystem failures and stopped-history damage; no simulated successful writes. */
class QuestDeliveryStorageTest {
    @TempDir Path directory;
    private final UUID actor = UUID.randomUUID(), team = UUID.randomUUID();
    private QuestDeliveryJournal.Target target(long progress) {
        return new QuestDeliveryJournal.Target(team, "a1", "a2", 0, 3, progress, progress == 3, "a".repeat(64));
    }
    private Path revision(QuestDeliveryJournal.Entry entry, int revision) {
        return directory.resolve("delivery-" + entry.sequence() + "-" + entry.id() + "-" + revision + ".json");
    }
    @Test void failedConfirmationPoisonsWriterAndReopensAsUncertainWithoutAnOutcome() throws Exception {
        QuestDeliveryJournal.Entry prepared;
        String original;
        Path conflict;
        try (var journal = QuestDeliveryJournal.open(directory)) {
            prepared = journal.prepare(actor, target(0), "[{\"count\":3}]");
            original = Files.readString(revision(prepared, 0));
            conflict = Files.createDirectory(revision(prepared, 1));
            assertThrows(IOException.class, () -> journal.confirm(prepared.id(), target(3), "[]"));
            assertThrows(IllegalStateException.class, journal::entries);
            assertThrows(IllegalStateException.class, () -> journal.prepare(UUID.randomUUID(), target(0), "[]"));
            assertEquals(original, Files.readString(revision(prepared, 0)));
            assertTrue(Files.isDirectory(conflict));
            Files.delete(conflict);
            assertThrows(IllegalStateException.class, () -> journal.confirm(prepared.id(), target(3), "[]"));
        }
        try (var journal = QuestDeliveryJournal.open(directory)) {
            var uncertain = journal.get(prepared.id());
            assertEquals(QuestDeliveryJournal.State.REVIEW, uncertain.state());
            assertNull(uncertain.after());
            assertEquals(prepared.inventoryBefore(), uncertain.inventoryBefore());
            assertEquals(original, Files.readString(revision(prepared, 0)));
            assertThrows(IllegalStateException.class, () -> journal.prepare(actor, target(0), "[]"));
            journal.resolve(prepared.id(), UUID.randomUUID(), false, "Compared original native inventory and task saves");
            assertEquals(prepared.id(), journal.prepare(actor, target(0), "[]").previousActorEntry());
        }
    }
    @Test void failedReviewCannotForgetThePreviouslyConfirmedOutcome() throws Exception {
        QuestDeliveryJournal.Entry confirmed;
        String original;
        try (var journal = QuestDeliveryJournal.open(directory)) {
            var prepared = journal.prepare(actor, target(0), "[{\"count\":3}]");
            confirmed = journal.confirm(prepared.id(), target(3), "[]");
            original = Files.readString(revision(confirmed, 1));
            Path conflict = Files.createDirectory(revision(confirmed, 2));
            assertThrows(IOException.class, () -> journal.review(confirmed.id(), "Native save mismatch"));
            assertThrows(IllegalStateException.class, journal::entries);
            assertEquals(original, Files.readString(revision(confirmed, 1)));
            Files.delete(conflict);
        }
        try (var journal = QuestDeliveryJournal.open(directory)) {
            assertEquals(confirmed, journal.get(confirmed.id()));
            var review = journal.review(confirmed.id(), "Native save mismatch persists after reopening");
            assertEquals(confirmed.after(), review.after());
            assertEquals(confirmed.inventoryAfter(), review.inventoryAfter());
            assertThrows(IllegalStateException.class, () -> journal.prepare(actor, target(0), "[]"));
        }
    }
    @Test void failedResolutionRetainsReviewAndTheOriginalNativeImages() throws Exception {
        QuestDeliveryJournal.Entry review;
        String original;
        try (var journal = QuestDeliveryJournal.open(directory)) {
            var prepared = journal.prepare(actor, target(0), "[{\"count\":3}]");
            journal.confirm(prepared.id(), target(3), "[]");
            review = journal.review(prepared.id(), "Native save mismatch");
            original = Files.readString(revision(review, 2));
            Path conflict = Files.createDirectory(revision(review, 3));
            assertThrows(IOException.class, () -> journal.resolve(review.id(), UUID.randomUUID(), true, "Inspected native saves"));
            assertThrows(IllegalStateException.class, () -> journal.get(review.id()));
            assertEquals(original, Files.readString(revision(review, 2)));
            Files.delete(conflict);
        }
        try (var journal = QuestDeliveryJournal.open(directory)) {
            assertEquals(review, journal.get(review.id()));
            assertThrows(IllegalStateException.class, () -> journal.prepare(actor, target(0), "[]"));
            var resolved = journal.resolve(review.id(), UUID.randomUUID(), false, "Retained native effects after inspection");
            assertEquals(review.after(), resolved.after());
            assertEquals(review.inventoryBefore(), resolved.inventoryBefore());
            assertEquals(review.inventoryAfter(), resolved.inventoryAfter());
            assertEquals(original, Files.readString(revision(review, 2)));
        }
    }
    @Test void missingWholeMiddleDeliveryIsRejectedAndMatchingRestorationRecovers() throws Exception {
        QuestDeliveryJournal.Entry middle, last;
        try (var journal = QuestDeliveryJournal.open(directory)) {
            var first = journal.prepare(actor, target(0), "[{\"count\":1}]");
            journal.confirm(first.id(), target(1), "[]");
            middle = journal.prepare(UUID.randomUUID(), target(1), "[{\"count\":1}]");
            journal.confirm(middle.id(), target(2), "[]");
            last = journal.prepare(actor, target(2), "[{\"count\":1}]");
            journal.confirm(last.id(), target(3), "[]");
        }
        String prepared = Files.readString(revision(middle, 0)), confirmed = Files.readString(revision(middle, 1));
        String retainedTail = Files.readString(revision(last, 1));
        Files.delete(revision(middle, 0)); Files.delete(revision(middle, 1));
        assertThrows(IOException.class, () -> QuestDeliveryJournal.open(directory));
        assertEquals(retainedTail, Files.readString(revision(last, 1)));
        Files.writeString(revision(middle, 0), prepared); Files.writeString(revision(middle, 1), confirmed);
        try (var journal = QuestDeliveryJournal.open(directory)) {
            assertEquals(3, journal.entries().size());
            assertEquals(last.id(), journal.latest(actor).orElseThrow().id());
            assertEquals(QuestDeliveryJournal.State.CONFIRMED, journal.get(middle.id()).state());
        }
    }
    @Test void corruptLoadPreservesBytesReleasesOwnershipAndAllowsExactRestoration() throws Exception {
        QuestDeliveryJournal.Entry confirmed;
        try (var journal = QuestDeliveryJournal.open(directory)) {
            var prepared = journal.prepare(actor, target(0), "[{\"count\":3}]");
            confirmed = journal.confirm(prepared.id(), target(3), "[]");
        }
        Path path = revision(confirmed, 1);
        String original = Files.readString(path);
        for (String damage : new String[] {"{", original.replace("\"schema\":1", "\"schema\":2"),
                original.replace(actor.toString(), UUID.randomUUID().toString())}) {
            assertNotEquals(original, damage);
            Files.writeString(path, damage);
            // Repeated failed opens must release the lock and must never repair the source.
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThrows(IOException.class, () -> QuestDeliveryJournal.open(directory));
                assertEquals(damage, Files.readString(path));
            }
        }
        var changed = JsonParser.parseString(original).getAsJsonObject();
        changed.getAsJsonObject("entry").addProperty("inventoryBefore", "[{\"count\":4}]");
        Files.writeString(path, changed.toString());
        assertThrows(IOException.class, () -> QuestDeliveryJournal.open(directory));
        assertEquals(changed.toString(), Files.readString(path));
        Files.writeString(path, original);
        try (var journal = QuestDeliveryJournal.open(directory)) {
            assertEquals(confirmed, journal.get(confirmed.id()));
        }
    }
}
