package net.citizensnpcs.util;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.citizensnpcs.util.QuestRewardJournal.*;

class QuestRewardJournalTest {
    @TempDir Path directory;
    private final UUID team = UUID.randomUUID(), actor = UUID.randomUUID();
    private Binding binding(boolean teamReward, boolean repeatable) {
        return new Binding(UUID.randomUUID(), "20404", "20402", teamReward, repeatable, "a".repeat(64), "currency", 2, 375, UUID.randomUUID(), 1);
    }
    private Intent intent(Binding binding, UUID beneficiary, int cycle) { return new Intent(binding, team, binding.teamReward() ? team : beneficiary, beneficiary, cycle); }

    @Test void acceptedPaymentReplayKeepsExactOperationAndReceiptAcrossReopen() throws Exception {
        String id, operation; UUID receipt = UUID.randomUUID();
        try (var journal = QuestRewardJournal.open(directory)) {
            var binding = journal.bind(binding(false, true)); var claim = journal.prepare(intent(binding, actor, 0)); id = claim.id(); operation = claim.intent().operationKey();
            assertEquals(State.PREPARED, claim.state()); journal.accepted(id, 1);
        }
        try (var journal = QuestRewardJournal.open(directory)) {
            assertEquals(State.ACCEPTED, journal.get(id).state()); assertEquals(operation, journal.get(id).intent().operationKey());
            journal.paid(id, receipt); assertEquals(journal.get(id), journal.paid(id, receipt));
        }
        try (var journal = QuestRewardJournal.open(directory)) { assertEquals(receipt, journal.get(id).transactionId()); assertEquals(State.PAID, journal.get(id).state()); }
    }
    @Test void uncertainClaimCannotBePaidOrReinterpretedAfterRestart() throws Exception {
        Binding binding; String id;
        try (var journal = QuestRewardJournal.open(directory)) { binding = journal.bind(binding(true, true)); id = journal.prepare(intent(binding, actor, 0)).id(); }
        try (var journal = QuestRewardJournal.open(directory)) {
            assertEquals(State.REVIEW, journal.get(id).state()); assertThrows(IllegalStateException.class, () -> journal.paid(id, UUID.randomUUID()));
            assertThrows(IllegalStateException.class, () -> journal.prepare(intent(binding, UUID.randomUUID(), 1)));
            assertThrows(IllegalArgumentException.class, () -> journal.resolve(id, actor, " ", true));
            var resolved = journal.resolve(id, actor, "Verified accepted native claim against retained backup", true);
            assertEquals(actor, resolved.resolution().operatorId()); journal.paid(id, UUID.randomUUID());
            assertThrows(IllegalStateException.class, () -> journal.resolve(id, actor, "Rewrite review", false));
        }
    }
    @Test void teamOwnerDeduplicatesBeneficiariesButIndividualClaimsAreSeparate() throws Exception {
        try (var journal = QuestRewardJournal.open(directory)) {
            var shared = journal.bind(binding(true, false)); var claim = journal.prepare(intent(shared, actor, 0)); journal.accepted(claim.id(), 0);
            assertThrows(IllegalStateException.class, () -> journal.prepare(intent(shared, UUID.randomUUID(), 0)));
            assertEquals(actor, journal.get(claim.id()).intent().beneficiaryId());
            var personal = new Binding(UUID.randomUUID(), "20405", "20402", false, false, "b".repeat(64), "currency", 2, 250, actor, 1);
            journal.bind(personal); var first = journal.prepare(intent(personal, actor, 0)); journal.accepted(first.id(), 0);
            var second = journal.prepare(intent(personal, UUID.randomUUID(), 0));
            assertNotEquals(first.intent().operationKey(), second.intent().operationKey());
        }
    }
    @Test void repeatGenerationAndNativeRollbackAreFenced() throws Exception {
        try (var journal = QuestRewardJournal.open(directory)) {
            var binding = journal.bind(binding(false, true)); var first = journal.prepare(intent(binding, actor, 4)); journal.accepted(first.id(), 5);
            assertThrows(IllegalStateException.class, () -> journal.prepare(intent(binding, actor, 4)));
            assertThrows(IllegalStateException.class, () -> journal.prepare(intent(binding, UUID.randomUUID(), 3)));
            var second = journal.prepare(intent(binding, actor, 5)); assertNotEquals(first.intent().operationKey(), second.intent().operationKey());
            assertThrows(IllegalArgumentException.class, () -> journal.accepted(second.id(), 8));
        }
    }
    @Test void rejectedReviewCannotIssueMoneyOrAcquireAnotherBeneficiary() throws Exception {
        try (var journal = QuestRewardJournal.open(directory)) {
            var binding = journal.bind(binding(true, false)); var claim = journal.prepare(intent(binding, actor, 0)); journal.review(claim.id());
            journal.resolve(claim.id(), actor, "Native mutation was not accepted", false);
            assertThrows(IllegalStateException.class, () -> journal.paid(claim.id(), UUID.randomUUID()));
            assertThrows(IllegalStateException.class, () -> journal.prepare(intent(binding, UUID.randomUUID(), 0)));
        }
    }
    @Test void corruptedOrIncompleteHistoryNeverReopensForPayment() throws Exception {
        String id;
        try (var journal = QuestRewardJournal.open(directory)) { var binding = journal.bind(binding(false, true)); id = journal.prepare(intent(binding, actor, 0)).id(); journal.accepted(id, 1); }
        Path admission = directory.resolve("claim-" + id + "-0.json"); Files.delete(admission);
        assertThrows(java.io.IOException.class, () -> QuestRewardJournal.open(directory));
    }
    @Test void temporaryWriteAndConcurrentWriterRejectBeforeAdmission() throws Exception {
        try (var journal = QuestRewardJournal.open(directory)) { assertThrows(java.io.IOException.class, () -> QuestRewardJournal.open(directory)); }
        Files.writeString(directory.resolve("claim-fixture.json.tmp-interrupted"), "{}");
        assertThrows(java.io.IOException.class, () -> QuestRewardJournal.open(directory));
    }
    @Test void invalidOwnershipAmountsAndWorkerThreadFailBeforeMutation() throws Exception {
        var binding = binding(true, true);
        assertThrows(IllegalArgumentException.class, () -> new Intent(binding, team, actor, actor, 0));
        assertThrows(IllegalArgumentException.class, () -> new Binding(binding.epoch(), "20404", "20402", true, true, "a".repeat(64), "currency", 2, 0, actor, 1));
        try (var journal = QuestRewardJournal.open(directory)) {
            assertTrue(java.util.concurrent.CompletableFuture.supplyAsync(() -> { try { journal.bindings(); return false; } catch (IllegalStateException expected) { return true; } }).join());
        }
    }
}
