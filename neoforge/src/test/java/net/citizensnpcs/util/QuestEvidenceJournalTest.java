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
        try (var journal=QuestEvidenceJournal.open(directory)) {
            var generation=journal.generation(scope,0);
            for(long units:new long[]{Long.MAX_VALUE,1}) {
                var entry=journal.admit(admission(generation,UUID.randomUUID(),units)); journal.outcome(entry.id(),Outcome.RETURNED);
            }
            assertThrows(ArithmeticException.class,()->journal.confirmedTotal(generation.id(),"10403"));
        }
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
