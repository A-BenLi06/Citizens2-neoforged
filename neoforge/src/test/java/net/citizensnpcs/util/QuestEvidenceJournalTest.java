package net.citizensnpcs.util;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.citizensnpcs.util.QuestEvidenceJournal.*;

class QuestEvidenceJournalTest {
    @TempDir Path directory;
    private final Scope scope = new Scope(UUID.randomUUID(), UUID.randomUUID(), "10402");
    private final UUID actor = UUID.randomUUID();

    private Admission admission(Generation generation, UUID observation, long units) {
        return new Admission(observation, generation.id(), "10403", actor, "fixture:recipe", "a".repeat(64), units, "{\"component\":\"retained\"}");
    }

    @Test void confirmedAndAppliedEvidenceSurvivesReopenWithoutCountingReplay() throws Exception {
        Admission admission;
        UUID generationId;
        try (var journal=QuestEvidenceJournal.open(directory)) {
            var generation=journal.generation(scope,0); generationId=generation.id();
            admission=admission(generation,UUID.randomUUID(),3);
            var entry=journal.admit(admission);
            assertEquals(State.PREPARED,entry.state()); assertEquals(0,journal.confirmedTotal(generationId,"10403"));
            journal.outcome(entry.id(),Outcome.RETURNED); journal.applied(entry.id());
            assertEquals(State.APPLIED,journal.admit(admission).state()); assertEquals(3,journal.confirmedTotal(generationId,"10403"));
        }
        try (var journal=QuestEvidenceJournal.open(directory)) {
            assertEquals(generationId,journal.generation(scope,0).id());
            var replay=journal.admit(admission); assertEquals(State.APPLIED,replay.state());
            assertEquals(replay,journal.outcome(replay.id(),Outcome.RETURNED));
            assertEquals(3,journal.confirmedTotal(generationId,"10403")); assertEquals(1,journal.entries().size());
        }
    }

    @Test void missingNativeOutcomeRequiresAnAttributedOperatorDecision() throws Exception {
        UUID generationId;
        try (var journal=QuestEvidenceJournal.open(directory)) {
            var generation=journal.generation(scope,0); generationId=generation.id();
            journal.admit(admission(generation,UUID.randomUUID(),4));
        }
        UUID operator=UUID.randomUUID();
        try (var journal=QuestEvidenceJournal.open(directory)) {
            var entry=journal.entries().getFirst(); assertEquals(State.REVIEW,entry.state()); assertEquals(Outcome.MISSING,entry.outcome());
            assertEquals(0,journal.confirmedTotal(generationId,"10403"));
            assertThrows(IllegalStateException.class,()->journal.applied(entry.id()));
            assertThrows(IllegalStateException.class,()->journal.outcome(entry.id(),Outcome.RETURNED));
            assertThrows(IllegalArgumentException.class,()->journal.resolve(entry.id(),operator," ",true));
            var resolved=journal.resolve(entry.id(),operator,"Verified retained native evidence",true);
            assertEquals(operator,resolved.resolution().operatorId()); assertEquals(4,journal.confirmedTotal(generationId,"10403"));
            assertThrows(IllegalStateException.class,()->journal.resolve(entry.id(),operator,"Cannot rewrite a decision",false));
        }
        try (var journal=QuestEvidenceJournal.open(directory)) {
            assertEquals(operator,journal.entries().getFirst().resolution().operatorId());
            assertEquals("{\"component\":\"retained\"}",journal.entries().getFirst().admission().evidence());
        }
    }

    @Test void interruptedEvidenceCanBeRejectedWithoutContributingProgress() throws Exception {
        try (var journal=QuestEvidenceJournal.open(directory)) {
            var generation=journal.generation(scope,0);
            var entry=journal.admit(admission(generation,UUID.randomUUID(),8));
            journal.outcome(entry.id(),Outcome.INTERRUPTED);
            var rejected=journal.resolve(entry.id(),UUID.randomUUID(),"Native remainder processing failed",false);
            assertEquals(State.REJECTED,rejected.state()); assertEquals(0,journal.confirmedTotal(generation.id(),"10403"));
            assertEquals(rejected,journal.admit(entry.admission()));
        }
    }

    @Test void companionValidationHoldsOwnershipAndPrecedesRecoveryPublication() throws Exception {
        String id;
        try (var journal = QuestEvidenceJournal.open(directory)) {
            id = journal.admit(admission(journal.generation(scope, 0), UUID.randomUUID(), 4)).id();
        }
        var before = recordImages();
        var failure = assertThrows(IOException.class, () -> QuestEvidenceJournal.open(directory, () -> {
            assertThrows(IOException.class, () -> QuestEvidenceJournal.open(directory));
            assertEquals(before, recordImages());
            throw new IOException("Invalid companion binding");
        }));
        assertEquals("Invalid companion binding", failure.getMessage());
        assertEquals(before, recordImages());
        try (var journal = QuestEvidenceJournal.open(directory, () -> assertEquals(before, recordImages()))) {
            var recovered = journal.entries().getFirst();
            assertEquals(id, recovered.id());
            assertEquals(State.REVIEW, recovered.state());
            assertEquals(Outcome.MISSING, recovered.outcome());
            assertEquals(1, recovered.revision());
        }
    }

    @Test void damagedHistoryRejectsBeforeCompanionValidationAndRecovery() throws Exception {
        Path path;
        try (var journal = QuestEvidenceJournal.open(directory)) {
            var entry = journal.admit(admission(journal.generation(scope, 0), UUID.randomUUID(), 4));
            path = directory.resolve("entry-" + entry.id() + "-0.json");
        }
        String original = Files.readString(path);
        Files.writeString(path, original.replace("\"recipeRevision\"", "\"unknownRevision\""));
        var before = recordImages();
        assertThrows(IOException.class, () -> QuestEvidenceJournal.open(directory, () -> fail("Damaged history reached companion validation")));
        assertEquals(before, recordImages());
        Files.writeString(path, original);
        try (var journal = QuestEvidenceJournal.open(directory)) {
            assertEquals(State.REVIEW, journal.entries().getFirst().state());
        }
    }

    private java.util.Map<String, String> recordImages() throws IOException {
        var images = new java.util.TreeMap<String, String>();
        try (var paths = Files.list(directory)) {
            for (var path : paths.filter(p -> p.toString().endsWith(".json")).toList())
                images.put(path.getFileName().toString(), Files.readString(path));
        }
        return images;
    }

    @Test void payloadConflictsAndContradictoryOutcomesPreserveAcceptedEvidence() throws Exception {
        try (var journal=QuestEvidenceJournal.open(directory)) {
            var generation=journal.generation(scope,0); UUID observation=UUID.randomUUID();
            var entry=journal.admit(admission(generation,observation,3));
            Path path=directory.resolve("entry-"+entry.id()+"-0.json"); String before=Files.readString(path);
            assertThrows(IllegalArgumentException.class,()->journal.admit(admission(generation,observation,4)));
            assertEquals(before,Files.readString(path));
            journal.outcome(entry.id(),Outcome.RETURNED); Path confirmedPath=directory.resolve("entry-"+entry.id()+"-1.json");
            String confirmed=Files.readString(confirmedPath); assertEquals(before,Files.readString(path));
            assertThrows(IllegalStateException.class,()->journal.outcome(entry.id(),Outcome.INTERRUPTED));
            assertEquals(confirmed,Files.readString(confirmedPath));
        }
    }

    @Test void generationsFenceNativeCounterRollbackAndSeparateTeamsAndEpochs() throws Exception {
        UUID next;
        try (var journal=QuestEvidenceJournal.open(directory)) {
            var first=journal.generation(scope,0); next=journal.generation(scope,1).id();
            assertNotEquals(first.id(),next);
            assertThrows(IllegalStateException.class,()->journal.generation(scope,0));
            assertNotEquals(next,journal.generation(new Scope(scope.epoch(),UUID.randomUUID(),scope.questId()),0).id());
            assertNotEquals(next,journal.generation(new Scope(UUID.randomUUID(),scope.teamId(),scope.questId()),0).id());
        }
        try (var journal=QuestEvidenceJournal.open(directory)) {
            assertEquals(next,journal.generation(scope,1).id());
            assertThrows(IllegalStateException.class,()->journal.generation(scope,0));
        }
    }

    @Test void exclusiveOwnershipRetirementAndImmutableSnapshotsAreEnforced() throws Exception {
        var journal=QuestEvidenceJournal.open(directory);
        try {
            journal.generation(scope,0);
            assertThrows(IOException.class,()->QuestEvidenceJournal.open(directory));
            assertThrows(UnsupportedOperationException.class,()->journal.generations().clear());
            var worker=java.util.concurrent.CompletableFuture.supplyAsync(()->{
                try { journal.entries(); return false; } catch(IllegalStateException expected) { return true; }
            });
            assertTrue(worker.get());
        } finally { journal.close(); }
        assertThrows(IllegalStateException.class,journal::entries);
        try (var reopened=QuestEvidenceJournal.open(directory)) { assertEquals(1,reopened.generations().size()); }
    }

    @Test void orphanedWritesAreRetainedAndRequireReview() throws Exception {
        Path orphan=directory.resolve("entry-fixture.json.tmp-interrupted");
        Files.writeString(orphan,"partial retained source");
        assertThrows(IOException.class,()->QuestEvidenceJournal.open(directory));
        assertEquals("partial retained source",Files.readString(orphan));
    }

    @Test void corruptOrUnsupportedRecordsAreNotRewrittenDuringRecovery() throws Exception {
        Path path;
        try (var journal=QuestEvidenceJournal.open(directory)) {
            var generation=journal.generation(scope,0); var entry=journal.admit(admission(generation,UUID.randomUUID(),1));
            path=directory.resolve("entry-"+entry.id()+"-0.json");
        }
        String unsupported=Files.readString(path).replace("\"recipeRevision\"","\"unknownRevision\"");
        Files.writeString(path,unsupported);
        assertThrows(IOException.class,()->QuestEvidenceJournal.open(directory));
        assertEquals(unsupported,Files.readString(path));
    }

    @Test void totalsUseCheckedArithmetic() throws Exception {
        UUID generationId;
        try (var journal=QuestEvidenceJournal.open(directory)) {
            var generation=journal.generation(scope,0); generationId = generation.id();
            for(long units:new long[]{Long.MAX_VALUE,1}) {
                var entry=journal.admit(admission(generation,UUID.randomUUID(),units)); journal.outcome(entry.id(),Outcome.RETURNED); journal.applied(entry.id());
            }
            assertThrows(ArithmeticException.class,()->journal.confirmedTotal(generation.id(),"10403"));
            assertThrows(ArithmeticException.class,()->journal.appliedTotal(generation.id(),"10403"));
        }
        try (var journal=QuestEvidenceJournal.open(directory)) {
            assertThrows(ArithmeticException.class,()->journal.confirmedTotal(generationId,"10403"));
            assertThrows(ArithmeticException.class,()->journal.appliedTotal(generationId,"10403"));
            assertTrue(journal.confirmed(generationId,"10403").isEmpty());
        }
    }

    @Test void scopedReadsMatchIndependentHistoryAcrossTransitionsAndRecovery() throws Exception {
        UUID observation = UUID.randomUUID();
        try (var journal = QuestEvidenceJournal.open(directory)) {
            for (int cycle = 0; cycle < 3; cycle++) {
                var generation = journal.generation(scope, cycle);
                var unrelated = journal.generation(new Scope(scope.epoch(), UUID.randomUUID(), scope.questId()), cycle);
                for (int i = 0; i < 12; i++) {
                    var admitted = admission(i % 2 == 0 ? generation : unrelated, i < 2 || i == 11 ? observation : UUID.randomUUID(), i + 1);
                    if (i == 11) admitted = new Admission(admitted.observationId(), admitted.generationId(), "10404", admitted.actorId(),
                            admitted.recipeId(), admitted.recipeRevision(), admitted.units(), admitted.evidence());
                    var entry = journal.admit(admitted);
                    assertScopedReads(journal);
                    if (i % 5 == 0) continue; // Restart must rebuild this observation's REVIEW fence.
                    journal.outcome(entry.id(), i % 3 == 0 ? Outcome.INTERRUPTED : Outcome.RETURNED);
                    assertScopedReads(journal);
                    if (i % 3 == 0) journal.resolve(entry.id(), actor, "Verified original native outcome", i % 2 == 0);
                    if (i % 3 != 0 && i % 2 == 0) journal.applied(entry.id());
                    assertScopedReads(journal);
                }
            }
        }
        for (int pass = 0; pass < 2; pass++) try (var journal = QuestEvidenceJournal.open(directory)) {
            assertScopedReads(journal);
            for (var entry : journal.entries()) {
                if (entry.state() == State.REVIEW) journal.resolve(entry.id(), actor, "Independent retained evidence reviewed", pass == 0);
                if (entry.state() == State.CONFIRMED) journal.applied(entry.id());
                assertScopedReads(journal);
            }
            assertThrows(IllegalStateException.class, () -> journal.generation(scope, 1));
            assertEquals(2, journal.generation(scope, 2).nativeCycle());
        }
    }

    @Test void earliestObservationUsesTimestampThenIdRatherThanGenerationOrLoadOrder() throws Exception {
        UUID observation = UUID.randomUUID();
        try (var journal = QuestEvidenceJournal.open(directory)) {
            for (int cycle = 0; cycle < 3; cycle++) {
                var entry = journal.admit(admission(journal.generation(scope, cycle), observation, cycle + 1));
                journal.outcome(entry.id(), Outcome.RETURNED);
            }
        }
        // Valid histories may have equal or nonmonotonic wall-clock timestamps.
        var codec = new com.google.gson.GsonBuilder().serializeNulls().create();
        try (var paths = Files.list(directory)) {
            for (var path : paths.filter(p -> p.getFileName().toString().startsWith("entry-")).toList()) {
                var document = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                var payload = document.getAsJsonObject("payload");
                payload.addProperty("createdAt", 100);
                Files.writeString(path, codec.toJson(document));
            }
        }
        try (var journal = QuestEvidenceJournal.open(directory)) {
            assertScopedReads(journal);
            var earliest = journal.entries().getFirst();
            assertEquals(earliest, journal.previous(observation, "10403").orElseThrow());
            journal.applied(earliest.id());
            assertEquals(State.APPLIED, journal.previous(observation, "10403").orElseThrow().state());
            for (var entry : journal.entries()) if (entry.state() == State.CONFIRMED) journal.applied(entry.id());
            assertScopedReads(journal);
        }
    }

    @Test void failedRevisionRetiresAllScopedReadsAndPreservesOriginalAdmission() throws Exception {
        try (var journal = QuestEvidenceJournal.open(directory)) {
            var generation = journal.generation(scope, 0);
            var entry = journal.admit(admission(generation, UUID.randomUUID(), 3));
            var original = recordImages();
            Path collision = directory.resolve("entry-" + entry.id() + "-1.json");
            Files.writeString(collision, "retained conflicting revision");
            assertThrows(IOException.class, () -> journal.outcome(entry.id(), Outcome.RETURNED));
            assertEquals(original.get("entry-" + entry.id() + "-0.json"), Files.readString(directory.resolve("entry-" + entry.id() + "-0.json")));
            assertEquals("retained conflicting revision", Files.readString(collision));
            assertThrows(IllegalStateException.class, () -> journal.previous(entry.admission().observationId(), "10403"));
            assertThrows(IllegalStateException.class, () -> journal.observation(entry.admission().observationId()));
            assertThrows(IllegalStateException.class, () -> journal.generation(generation.id()));
            assertThrows(IllegalStateException.class, () -> journal.hasReview(generation.id(), "10403"));
            assertThrows(IllegalStateException.class, () -> journal.confirmed(generation.id(), "10403"));
        }
    }

    private void assertScopedReads(QuestEvidenceJournal journal) {
        var all = journal.entries();
        for (var generation : journal.generations()) {
            assertEquals(generation, journal.generation(generation.id()).orElseThrow());
            for (String task : java.util.List.of("10403", "10404")) {
                var relevant = all.stream().filter(e -> e.admission().generationId().equals(generation.id()) && e.admission().taskId().equals("00000000000" + task)).toList();
                assertEquals(relevant.stream().anyMatch(e -> e.state() == State.REVIEW), journal.hasReview(generation.id(), task));
                assertEquals(relevant.stream().filter(e -> e.state() == State.CONFIRMED).toList(), journal.confirmed(generation.id(), task));
                assertEquals(relevant.stream().filter(e -> e.state() == State.CONFIRMED || e.state() == State.APPLIED).mapToLong(e -> e.admission().units()).sum(), journal.confirmedTotal(generation.id(), task));
                assertEquals(relevant.stream().filter(e -> e.state() == State.APPLIED).mapToLong(e -> e.admission().units()).sum(), journal.appliedTotal(generation.id(), task));
                assertThrows(UnsupportedOperationException.class, () -> journal.confirmed(generation.id(), task).add(null));
            }
        }
        for (var entry : all) {
            var observed = all.stream().filter(e -> e.admission().observationId().equals(entry.admission().observationId())).toList();
            assertEquals(observed, journal.observation(entry.admission().observationId()));
            assertEquals(observed.stream().filter(e -> e.admission().taskId().equals(entry.admission().taskId())).findFirst(), journal.previous(entry.admission().observationId(), entry.admission().taskId()));
            assertThrows(UnsupportedOperationException.class, () -> journal.observation(entry.admission().observationId()).clear());
        }
        UUID missing = UUID.randomUUID();
        assertTrue(journal.previous(missing, "10403").isEmpty());
        assertTrue(journal.observation(missing).isEmpty());
        assertTrue(journal.generation(missing).isEmpty());
        assertFalse(journal.hasReview(missing, "10403"));
        assertTrue(journal.confirmed(missing, "10403").isEmpty());
    }

    @Test void aGapInImmutableHistoryIsRejectedWithoutDiscardingRecords() throws Exception {
        String id;
        try (var journal=QuestEvidenceJournal.open(directory)) {
            var entry=journal.admit(admission(journal.generation(scope,0),UUID.randomUUID(),3)); id=entry.id();
            journal.outcome(id,Outcome.RETURNED); journal.applied(id);
        }
        Path middle=directory.resolve("entry-"+id+"-1.json"); String retained=Files.readString(middle);
        Path backup=directory.resolve("retained-middle-record.txt"); Files.move(middle,backup);
        assertThrows(IOException.class,()->QuestEvidenceJournal.open(directory));
        assertEquals(retained,Files.readString(backup)); assertTrue(Files.exists(directory.resolve("entry-"+id+"-2.json")));
    }

    @Test void aChangedActorInLaterHistoryCannotRebindTheAdmittedBeneficiary() throws Exception {
        String id;
        try (var journal=QuestEvidenceJournal.open(directory)) {
            var entry=journal.admit(admission(journal.generation(scope,0),UUID.randomUUID(),3)); id=entry.id(); journal.outcome(id,Outcome.RETURNED);
        }
        Path later=directory.resolve("entry-"+id+"-1.json");
        String changed=Files.readString(later).replace(actor.toString(),UUID.randomUUID().toString()); Files.writeString(later,changed);
        assertThrows(IOException.class,()->QuestEvidenceJournal.open(directory)); assertEquals(changed,Files.readString(later));
    }
}
