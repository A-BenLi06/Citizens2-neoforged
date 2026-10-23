package net.citizensnpcs.audit;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;
import com.google.gson.*;
import net.citizensnpcs.util.QuestEvidenceJournal;
import net.citizensnpcs.util.QuestEvidenceJournal.*;

/** Real immutable files, identical read workload before/after indexing; no tick latency threshold. */
public final class CraftStorageAudit {
    private static volatile long sink;
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final String TASK = "00000000000000A2";
    private static final UUID ABSENT = UUID.randomUUID();
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        boolean indexed = args[1].equals("indexed");
        if (!indexed) Files.createDirectory(root);
        var result = new JsonObject();
        result.addProperty("createdAt", OffsetDateTime.now().toString());
        result.addProperty("mode", args[1]);
        result.addProperty("java", System.getProperty("java.runtime.version"));
        result.addProperty("scope", "Warm local journal reads; indexed reflection dispatch included; excludes native state and forced-write latency");
        var samples = new JsonArray(); result.add("samples", samples);
        for (int size : new int[]{256, 1024, 4096}) {
            Path directory = root.resolve("entries-" + size);
            long start = System.nanoTime();
            if (!indexed) try (var journal = QuestEvidenceJournal.open(directory)) {
                for (int i = 0; i < size; i++) {
                    var scope = new Scope(new UUID(1, i / 8), new UUID(2, i / 8), "a1");
                    var generation = journal.generation(scope, 0);
                    var entry = journal.admit(new Admission(new UUID(3, i), generation.id(), TASK,
                            new UUID(4, i % 128), "fixture:recipe", "a".repeat(64), 4, "{\"original\":true}"));
                    journal.outcome(entry.id(), i % 8 == 7 ? Outcome.INTERRUPTED : Outcome.RETURNED);
                    if (i % 8 < 6) journal.applied(entry.id());
                }
            }
            var sample = new JsonObject(); samples.add(sample);
            sample.addProperty("entries", size);
            if (!indexed) sample.addProperty("appendAndForceMillis", (System.nanoTime() - start) / 1e6);
            start = System.nanoTime();
            try (var journal = QuestEvidenceJournal.open(directory)) {
                sample.addProperty("warmOpenMillis", (System.nanoTime() - start) / 1e6);
                var entries = journal.entries();
                require(entries.size() == size);
                var selected = entries.stream().filter(e -> e.admission().observationId().equals(new UUID(3, size - 2))).findFirst().orElseThrow();
                var generation = journal.generations().stream().filter(g -> g.id().equals(selected.admission().generationId())).findFirst().orElseThrow();
                Method previous = indexed ? QuestEvidenceJournal.class.getMethod("previous", UUID.class, String.class) : null;
                Method observation = indexed ? QuestEvidenceJournal.class.getMethod("observation", UUID.class) : null;
                Method lookup = indexed ? QuestEvidenceJournal.class.getMethod("generation", UUID.class) : null;
                Method review = indexed ? QuestEvidenceJournal.class.getMethod("hasReview", UUID.class, String.class) : null;
                Method confirmed = indexed ? QuestEvidenceJournal.class.getMethod("confirmed", UUID.class, String.class) : null;
                Runnable reads = () -> {
                    try {
                        if (indexed) {
                            require(((Optional<?>)previous.invoke(journal, ABSENT, TASK)).isEmpty());
                            require(((List<?>)observation.invoke(journal, selected.admission().observationId())).size() == 1);
                            require(((Optional<?>)lookup.invoke(journal, generation.id())).orElseThrow().equals(generation));
                            require((boolean)review.invoke(journal, generation.id(), TASK));
                            require(((List<?>)confirmed.invoke(journal, generation.id(), TASK)).size() == 1);
                        } else {
                            require(journal.entries().stream().noneMatch(e -> e.admission().observationId().equals(ABSENT) && e.admission().taskId().equals(TASK)));
                            require(journal.entries().stream().filter(e -> e.admission().observationId().equals(selected.admission().observationId())).count() == 1);
                            require(journal.generations().stream().filter(g -> g.id().equals(generation.id())).findFirst().orElseThrow().equals(generation));
                            require(journal.entries().stream().anyMatch(e -> e.admission().generationId().equals(generation.id()) && e.admission().taskId().equals(TASK) && e.state() == State.REVIEW));
                            require(journal.entries().stream().filter(e -> e.admission().generationId().equals(generation.id()) && e.admission().taskId().equals(TASK) && e.state() == State.CONFIRMED).count() == 1);
                        }
                        require(journal.generation(generation.scope(), 0).equals(generation));
                        sink += journal.appliedTotal(generation.id(), TASK);
                        require(journal.confirmedTotal(generation.id(), TASK) == 28);
                    } catch (Exception failure) { throw new IllegalStateException(failure); }
                };
                for (int i = 0; i < 200; i++) reads.run();
                start = System.nanoTime(); long allocated = allocation();
                for (int i = 0; i < 2000; i++) reads.run();
                sample.addProperty("readIterations", 2000);
                sample.addProperty("readsNanosPerCall", (System.nanoTime() - start) / 2000.0);
                sample.addProperty("readsAllocatedBytesPerCall", (allocation() - allocated) / 2000.0);
                require(journal.appliedTotal(generation.id(), TASK) == 24);
            }
            try (var paths = Files.list(directory)) {
                require(paths.filter(p -> p.toString().endsWith(".json")).count() == size * 2L + size * 3L / 4 + size / 8);
            }
            System.out.println("[CRAFTSTORAGE] PASS " + sample);
            Files.writeString(root.resolve(args[1] + "-result.json"), JSON.toJson(result) + "\n");
        }
    }
    private static long allocation() { return THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()); }
    private static void require(boolean condition) { if (!condition) throw new IllegalStateException("Retained journal workload changed"); }
}
