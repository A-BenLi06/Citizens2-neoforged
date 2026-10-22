package net.citizensnpcs.audit;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import com.google.gson.*;
import net.citizensnpcs.util.QuestDeliveryJournal;

/** Opt-in real-file storage scaling measurement; it does not impose production latency thresholds. */
public final class DeliveryStorageAudit {
    private static volatile long sink;
    private static final String DEFINITION = "a".repeat(64);
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static QuestDeliveryJournal.Target target(UUID team, int size, int progress) {
        return new QuestDeliveryJournal.Target(team, "a1", "a2", 0, size + 1L, progress, false, DEFINITION);
    }
    public static void main(String[] args) throws Exception {
        Path artifacts = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(artifacts)) throw new IllegalArgumentException("Expected existing artifact root");
        UUID run = UUID.randomUUID();
        Path root = Files.createDirectory(artifacts.resolve("delivery-storage-load-" + run));
        var result = new JsonObject();
        result.addProperty("createdAt", OffsetDateTime.now().toString());
        result.addProperty("run", run.toString());
        result.addProperty("java", System.getProperty("java.runtime.version"));
        result.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        result.addProperty("processors", Runtime.getRuntime().availableProcessors());
        result.addProperty("maxHeapBytes", Runtime.getRuntime().maxMemory());
        result.addProperty("scope", "Journal API on real local files; warm filesystem cache; no Minecraft tick or production latency claim");
        var rows = new JsonArray(); result.add("samples", rows);
        for (int size : new int[] {1024, 4096, 16384}) {
            Path directory = Files.createDirectory(root.resolve("entries-" + size));
            var actors = new ArrayList<UUID>();
            for (int i = 0; i < 128; i++) actors.add(UUID.randomUUID());
            UUID team = UUID.randomUUID();
            var latest = new HashMap<UUID, UUID>();
            var sample = new JsonObject(); rows.add(sample);
            sample.addProperty("entries", size); sample.addProperty("actors", actors.size());
            long started = System.nanoTime(), allocated = allocation();
            Path first = null; byte[] original = null;
            try (var journal = QuestDeliveryJournal.open(directory)) {
                for (int i = 0; i < size; i++) {
                    UUID actor = actors.get(i % actors.size());
                    var entry = journal.prepare(actor, target(team, size, i), "[{\"count\":1,\"components\":{\"name\":\"retained parcel\"}}]");
                    require(Objects.equals(entry.previousActorEntry(), latest.put(actor, entry.id())), "Original predecessor changed during append");
                    journal.confirm(entry.id(), target(team, size, i + 1), "[]");
                    if (i == 0) { first = directory.resolve("delivery-1-" + entry.id() + "-0.json"); original = Files.readAllBytes(first); }
                }
            }
            sample.addProperty("appendAndForceMillis", (System.nanoTime() - started) / 1_000_000.0);
            sample.addProperty("appendThreadAllocatedBytes", allocation() - allocated);
            long bytes = 0, files = 0;
            try (var paths = Files.list(directory)) {
                for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) { bytes += Files.size(path); files++; }
            }
            require(files == size * 2L, "Immutable revisions lost");
            sample.addProperty("immutableFiles", files); sample.addProperty("immutableBytes", bytes);
            started = System.nanoTime(); allocated = allocation();
            try (var journal = QuestDeliveryJournal.open(directory)) {
                sample.addProperty("warmReopenMillis", (System.nanoTime() - started) / 1_000_000.0);
                sample.addProperty("reopenThreadAllocatedBytes", allocation() - allocated);
                require(journal.entries().size() == size, "Reopen lost entries");
                for (UUID actor : actors) require(journal.latest(actor).orElseThrow().id().equals(latest.get(actor)), "Reopen changed actor chain");
                for (int i = 0; i < 1000; i++) sink ^= journal.latest(actors.get(i % actors.size())).orElseThrow().sequence();
                started = System.nanoTime(); allocated = allocation();
                for (int i = 0; i < 10000; i++) sink ^= journal.latest(actors.get(i % actors.size())).orElseThrow().sequence();
                sample.addProperty("latestLookupIterations", 10000);
                sample.addProperty("latestLookupNanosPerCall", (System.nanoTime() - started) / 10000.0);
                sample.addProperty("latestLookupAllocatedBytesPerCall", (allocation() - allocated) / 10000.0);
                var admissionTarget = target(team, size, size);
                for (int i = 0; i < 1000; i++) admissionReads(journal, actors.get(i % actors.size()), admissionTarget);
                started = System.nanoTime(); allocated = allocation();
                for (int i = 0; i < 10000; i++) admissionReads(journal, actors.get(i % actors.size()), admissionTarget);
                sample.addProperty("admissionReadIterations", 10000);
                sample.addProperty("admissionReadNanosPerCall", (System.nanoTime() - started) / 10000.0);
                sample.addProperty("admissionReadAllocatedBytesPerCall", (allocation() - allocated) / 10000.0);
                started = System.nanoTime(); allocated = allocation();
                for (int i = 0; i < 1000; i++) sink += journal.entries().size();
                sample.addProperty("snapshotIterations", 1000);
                sample.addProperty("snapshotNanosPerCall", (System.nanoTime() - started) / 1000.0);
                sample.addProperty("snapshotAllocatedBytesPerCall", (allocation() - allocated) / 1000.0);
                require(Arrays.equals(original, Files.readAllBytes(first)), "Reopen rewrote original admission");
                sample.addProperty("firstAdmissionSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
                sample.addProperty("retainedChainsAndImmutableImages", true);
            }
            System.out.println("[DELIVERYSTORAGEAUDIT] PASS " + sample);
            Files.writeString(root.resolve("result.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result) + "\n");
        }
        result.addProperty("complete", true);
        Files.writeString(root.resolve("result.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result) + "\n");
        System.out.println("[DELIVERYSTORAGEAUDIT] COMPLETE " + root);
    }
    private static long allocation() { return THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()); }
    private static void admissionReads(QuestDeliveryJournal journal, UUID actor, QuestDeliveryJournal.Target target) {
        require(journal.pending(actor, target).isEmpty(), "Confirmed history retained an unresolved admission");
        sink ^= journal.latest(actor).orElseThrow().sequence();
        sink ^= journal.latestTask(target).orElseThrow().sequence();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private DeliveryStorageAudit() { }
}
