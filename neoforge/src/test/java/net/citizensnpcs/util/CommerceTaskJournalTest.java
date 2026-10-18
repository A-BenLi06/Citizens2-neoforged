package net.citizensnpcs.util;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.citizensnpcs.util.CommerceTaskJournal.*;

class CommerceTaskJournalTest {
    @TempDir Path directory;
    private final UUID actor = UUID.randomUUID(), team = UUID.randomUUID(), provider = UUID.randomUUID();
    private Binding binding(Measure measure) {
        return new Binding(UUID.randomUUID(), "30203", "30202", "a".repeat(64), 100, true,
                provider, 5, Shop.SYSTEM, "shop", "offer", Action.BUY_FROM_SHOP, measure,
                measure == Measure.MINOR_UNITS ? "currency" : null, true, actor, 1);
    }
    private Request request(String key, Integer trades) {
        return new Request(Shop.SYSTEM, "shop", "offer", Action.BUY_FROM_SHOP, actor, trades, key, "fixture", null);
    }
    private Receipt receipt(long sequence, Integer trades) {
        return new Receipt(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), sequence, "currency", 30, 6, trades, "components", "STONE", 1);
    }
    private Attribution attribution(int cycle) { return new Attribution(team, cycle, 5, 1); }

    @Test void receiptRecoveryPreservesOriginalTeamCycleAndOperationAcrossReopen() throws Exception {
        String id; Binding binding; Receipt receipt = receipt(6, 2);
        try (var journal = CommerceTaskJournal.open(directory)) {
            binding = journal.bind(binding(Measure.ITEMS)); id = journal.prepare(binding, request("buy", 2), attribution(4)).id();
            assertEquals(State.CONFIRMED, journal.receipt(binding, request("buy", 2), receipt, true).state());
            journal.checkpoint(binding, actor, provider, 6, 6);
        }
        try (var journal = CommerceTaskJournal.open(directory)) {
            assertEquals(6, journal.total(binding, team, 4, false)); assertEquals(0, journal.total(binding, team, 4, true));
            assertEquals(0, journal.total(binding, UUID.randomUUID(), 4, false)); assertEquals(0, journal.total(binding, team, 5, false));
            assertEquals(receipt, journal.entry(id).receipt()); assertEquals(State.APPLIED, journal.applied(id).state());
            assertEquals(journal.entry(id), journal.receipt(binding, request("buy", 2), receipt, true));
        }
        try (var journal = CommerceTaskJournal.open(directory)) {
            assertEquals(6, journal.total(binding, team, 4, true)); assertEquals(6, journal.cursor(binding, actor, provider, 6));
            assertThrows(IllegalStateException.class, () -> journal.prepare(binding, request("regressed", 2), attribution(3)));
            assertThrows(IllegalStateException.class, () -> journal.validateCycle(binding, team, 3));
            journal.validateCycle(binding, team, 4);
        }
    }
    @Test void fullInventoryWaitsAndThenConfirmsOnlyItsOriginalAttribution() throws Exception {
        Binding binding; String id; Receipt receipt = receipt(6, 2);
        try (var journal = CommerceTaskJournal.open(directory)) {
            binding = journal.bind(binding(Measure.TRADES)); id = journal.prepare(binding, request("pending", 2), attribution(0)).id();
            assertEquals(State.WAITING, journal.receipt(binding, request("pending", 2), receipt, false).state());
            assertEquals(0, journal.total(binding, team, 0, false));
            assertThrows(IllegalStateException.class, () -> journal.applied(id));
        }
        try (var journal = CommerceTaskJournal.open(directory)) {
            assertEquals(State.CONFIRMED, journal.ready(id).state()); assertEquals(2, journal.total(binding, team, 0, false));
            assertEquals(attribution(0), journal.entry(id).attribution()); journal.applied(id);
        }
    }
    @Test void missedStartingObservationNeverAutomaticallyUsesTodaysParty() throws Exception {
        try (var journal = CommerceTaskJournal.open(directory)) {
            var binding = journal.bind(binding(Measure.ITEMS)); var receipt = receipt(6, 2);
            var entry = journal.receipt(binding, request("missed", 2), receipt, true);
            assertEquals(State.REVIEW, entry.state()); assertNull(entry.attribution());
            assertEquals(State.REVIEW, journal.ready(entry.id()).state());
            assertEquals(State.REVIEW, journal.receipt(binding, request("missed", 2), receipt, true).state());
            assertThrows(IllegalArgumentException.class, () -> journal.resolve(entry.id(), attribution(0), actor, " ", true));
            journal.resolve(entry.id(), attribution(0), actor, "Verified historical team and cycle against retained records", true);
            assertEquals(6, journal.total(binding, team, 0, false));
        }
    }
    @Test void retainedAttributionCannotBeRedirectedByAnOperator() throws Exception {
        try (var journal = CommerceTaskJournal.open(directory)) {
            var binding = journal.bind(binding(Measure.ITEMS)); var req = request("late", 2);
            journal.prepare(binding, req, new Attribution(team, 0, 7, 1));
            var entry = journal.receipt(binding, req, receipt(6, 2), true);
            assertEquals(State.REVIEW, entry.state());
            assertThrows(IllegalArgumentException.class, () -> journal.resolve(entry.id(), attribution(0), actor, "Changed scope", true));
            journal.resolve(entry.id(), entry.attribution(), actor, "Rejected stale event after receipt", false);
            assertEquals(0, journal.total(binding, team, 0, false));
        }
    }
    @Test void nullLegacyMetadataIsRetainedWithoutInventingTradesOrRequestKeys() throws Exception {
        try (var journal = CommerceTaskJournal.open(directory)) {
            var binding = journal.bind(binding(Measure.TRADES)); var receipt = receipt(6, null);
            var req = new Request(Shop.SYSTEM, "shop", "offer", Action.BUY_FROM_SHOP, actor, null, null, null, receipt.operationId());
            var entry = journal.receipt(binding, req, receipt, true);
            assertEquals(State.REVIEW, entry.state()); assertNull(entry.request().trades()); assertNull(entry.request().requestKey());
            assertThrows(IllegalArgumentException.class, () -> journal.resolve(entry.id(), attribution(0), actor, "Cannot infer quantity", true));
            journal.resolve(entry.id(), null, actor, "Quantity absent in original ledger", false);
        }
        try (var journal = CommerceTaskJournal.open(directory)) { assertEquals(State.REJECTED, journal.entries().getFirst().state()); }
    }
    @Test void currencyMismatchAndUnknownTradeCountCannotBeAcceptedAsBoundUnits() throws Exception {
        try (var journal = CommerceTaskJournal.open(directory)) {
            var binding = journal.bind(binding(Measure.MINOR_UNITS)); var req = request("currency", 2);
            journal.prepare(binding, req, attribution(0)); var original = receipt(6, 2);
            var wrong = new Receipt(original.operationId(), original.transactionId(), original.deliveryClaimId(), 6, "other", 30, 6, 2, "components", "STONE", 1);
            var entry = journal.receipt(binding, req, wrong, false); assertEquals(State.REVIEW, entry.state());
            assertThrows(IllegalArgumentException.class, () -> journal.resolve(entry.id(), entry.attribution(), actor, "Wrong currency", true));
            assertEquals(0, journal.total(binding, team, 0, false));
        }
    }
    @Test void databaseIdentityAndGlobalHighWaterRegressionFailEvenForUnrelatedTrades() throws Exception {
        Binding binding;
        try (var journal = CommerceTaskJournal.open(directory)) {
            binding = journal.bind(binding(Measure.ITEMS)); journal.checkpoint(binding, actor, provider, 15, 9);
        }
        try (var journal = CommerceTaskJournal.open(directory)) {
            assertThrows(IllegalStateException.class, () -> journal.cursor(binding, actor, UUID.randomUUID(), 20));
            assertThrows(IllegalStateException.class, () -> journal.cursor(binding, UUID.randomUUID(), provider, 14));
            assertEquals(9, journal.cursor(binding, actor, provider, 15));
            journal.checkpoint(binding, actor, provider, 20, 9);
            assertThrows(IllegalStateException.class, () -> journal.cursor(binding, actor, provider, 19));
        }
    }
    @Test void changedRequestOrReceiptAndDuplicateOperationAreRejectedBeforeApplication() throws Exception {
        try (var journal = CommerceTaskJournal.open(directory)) {
            var binding = journal.bind(binding(Measure.ITEMS)); var req = request("same", 2); var receipt = receipt(6, 2);
            journal.prepare(binding, req, attribution(0)); journal.receipt(binding, req, receipt, true);
            assertThrows(IllegalArgumentException.class, () -> journal.prepare(binding, request("same", 3), attribution(0)));
            assertThrows(IllegalArgumentException.class, () -> journal.receipt(binding, request("other", 2), receipt, true));
            var changed = new Receipt(receipt.operationId(), UUID.randomUUID(), receipt.deliveryClaimId(), 6, "currency", 30, 6, 2, "components", "STONE", 1);
            assertThrows(IllegalArgumentException.class, () -> journal.receipt(binding, req, changed, true));
            var old = receipt(5, 2); assertThrows(IllegalArgumentException.class, () -> journal.receipt(binding, request("historical", 2), old, true));
        }
    }
    @Test void missingRevisionAndIncompleteWritesNeverReopenForProjection() throws Exception {
        String id;
        try (var journal = CommerceTaskJournal.open(directory)) {
            var binding = journal.bind(binding(Measure.ITEMS)); id = journal.prepare(binding, request("gap", 2), attribution(0)).id();
            journal.receipt(binding, request("gap", 2), receipt(6, 2), true);
        }
        Files.delete(directory.resolve("entry-" + id + "-0.json"));
        assertThrows(java.io.IOException.class, () -> CommerceTaskJournal.open(directory));
    }
    @Test void concurrentWriterInterruptedWriteAndWorkerThreadAreFenced() throws Exception {
        try (var journal = CommerceTaskJournal.open(directory)) {
            assertThrows(java.io.IOException.class, () -> CommerceTaskJournal.open(directory));
            assertTrue(java.util.concurrent.CompletableFuture.supplyAsync(() -> { try { journal.entries(); return false; } catch (IllegalStateException expected) { return true; } }).join());
        }
        Files.writeString(directory.resolve("entry-fixture.json.tmp-interrupted"), "{}");
        assertThrows(java.io.IOException.class, () -> CommerceTaskJournal.open(directory));
    }
    @Test void checkpointDetectsLossOfAnEntireProcessedReceiptHistory() throws Exception {
        String id;
        try (var journal = CommerceTaskJournal.open(directory)) {
            var binding = journal.bind(binding(Measure.ITEMS)); id = journal.prepare(binding, request("lost", 2), attribution(0)).id();
            journal.receipt(binding, request("lost", 2), receipt(6, 2), true); journal.applied(id);
            journal.checkpoint(binding, actor, provider, 6, 6);
        }
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(value -> value.getFileName().toString().startsWith("entry-" + id)).toList()) Files.delete(path);
        }
        assertThrows(java.io.IOException.class, () -> CommerceTaskJournal.open(directory));
    }
    @Test void preparedRequestSurvivesFailedTradeButNeverCountsWithoutReceipt() throws Exception {
        Binding binding; String id;
        try (var journal = CommerceTaskJournal.open(directory)) {
            binding = journal.bind(binding(Measure.ITEMS)); id = journal.prepare(binding, request("failed", 2), attribution(0)).id();
        }
        try (var journal = CommerceTaskJournal.open(directory)) {
            assertEquals(State.PREPARED, journal.entry(id).state()); assertEquals(0, journal.total(binding, team, 0, false));
            assertEquals(team, journal.prepare(binding, request("failed", 2), new Attribution(UUID.randomUUID(), 1, 6, 2)).attribution().teamId());
            assertThrows(IllegalStateException.class, () -> journal.applied(id));
        }
    }
    @Test void boundedMoneyTotalsDoNotOverflowWithLargeValidReceipts() throws Exception {
        try (var journal = CommerceTaskJournal.open(directory)) {
            var binding = journal.bind(binding(Measure.MINOR_UNITS));
            for (int index = 0; index < 2; index++) {
                var request = request("large-" + index, 1); journal.prepare(binding, request, attribution(0));
                var original = receipt(6 + index, 1);
                var large = new Receipt(original.operationId(), original.transactionId(), original.deliveryClaimId(), original.sequence(),
                        "currency", Long.MAX_VALUE, 6, 1, "components", "STONE", 1);
                journal.receipt(binding, request, large, true);
            }
            assertEquals(binding.maximum(), journal.total(binding, team, 0, false));
        }
    }
}
