package net.citizensnpcs.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Single-writer durable evidence, not a replacement for FTB's quest state or Minecraft inventory. */
public final class QuestEvidenceJournal implements AutoCloseable {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private final Path directory;
    private final Thread owner = Thread.currentThread();
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Map<UUID, Generation> generations = new LinkedHashMap<>();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean closed;
    private boolean failed;

    public enum State { PREPARED, CONFIRMED, REVIEW, REJECTED, APPLIED }
    public enum Outcome { RETURNED, INTERRUPTED, UNCONFIRMED, MISSING }

    /** Epoch is an explicitly owned binding/migration identity, never inferred from a title. */
    public record Scope(UUID epoch, UUID teamId, String questId) {
        public Scope { Objects.requireNonNull(epoch); Objects.requireNonNull(teamId); questId = objectId(questId); }
    }
    public record Generation(UUID id, Scope scope, int nativeCycle, long createdAt) {
        public Generation { Objects.requireNonNull(id); Objects.requireNonNull(scope); if (nativeCycle < 0 || createdAt < 0) throw new IllegalArgumentException("Invalid generation"); }
    }
    public record Admission(UUID observationId, UUID generationId, String taskId, UUID actorId,
            String recipeId, String recipeRevision, long units, String evidence) {
        public Admission {
            Objects.requireNonNull(observationId); Objects.requireNonNull(generationId); Objects.requireNonNull(actorId);
            taskId = objectId(taskId);
            if (recipeId == null || !recipeId.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")
                    || recipeRevision == null || !recipeRevision.matches("[0-9a-f]{64}") || units <= 0
                    || evidence == null || !JsonParser.parseString(evidence).isJsonObject()) throw new IllegalArgumentException("Invalid evidence admission");
        }
    }
    public record Resolution(UUID operatorId, String explanation, boolean accepted, long at) {
        public Resolution { Objects.requireNonNull(operatorId); if (explanation == null || explanation.isBlank() || at < 0) throw new IllegalArgumentException("Resolution needs an operator and explanation"); }
    }
    public record Entry(String id, int revision, Admission admission, State state, Outcome outcome, Resolution resolution, long createdAt, long updatedAt) {
        public Entry {
            Objects.requireNonNull(admission); Objects.requireNonNull(state);
            if (!key(admission).equals(id) || revision < 0 || createdAt < 0 || updatedAt < 0) throw new IllegalArgumentException("Invalid evidence entry");
            boolean valid = switch (state) {
                case PREPARED -> outcome == null && resolution == null;
                case REVIEW -> outcome != null && outcome != Outcome.RETURNED && resolution == null;
                case CONFIRMED, APPLIED -> outcome != null && (resolution == null ? outcome == Outcome.RETURNED : resolution.accepted());
                case REJECTED -> outcome != null && outcome != Outcome.RETURNED && resolution != null && !resolution.accepted();
            };
            if (!valid) throw new IllegalArgumentException("Invalid evidence transition data");
        }
    }

    public static QuestEvidenceJournal open(Path directory) throws IOException { return new QuestEvidenceJournal(directory); }

    private QuestEvidenceJournal(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        lockChannel = FileChannel.open(this.directory.resolve(".journal.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            try { acquired = lockChannel.tryLock(); }
            catch (java.nio.channels.OverlappingFileLockException busy) { throw new IOException("Evidence journal is already open", busy); }
            if (acquired == null) throw new IOException("Evidence journal is already open");
            lock = acquired;
            load();
            // No old process can deliver its native return after this lock is acquired.
            for (Entry entry : List.copyOf(entries.values())) if (entry.state() == State.PREPARED)
                replace(new Entry(entry.id(), entry.revision()+1, entry.admission(), State.REVIEW, Outcome.MISSING, null, entry.createdAt(), now()));
        } catch (IOException | RuntimeException | Error failure) {
            try { if (acquired != null) acquired.release(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            try { lockChannel.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** A lower native cycle is a reconciliation boundary, not permission to invent another generation. */
    public Generation generation(Scope scope, int nativeCycle) throws IOException {
        active(); Objects.requireNonNull(scope);
        if (nativeCycle < 0) throw new IllegalArgumentException("Invalid native cycle");
        Generation found = null;
        for (Generation generation : generations.values()) if (generation.scope().equals(scope)) {
            if (generation.nativeCycle() > nativeCycle) throw new IllegalStateException("Native cycle regressed; evidence reconciliation is required");
            if (generation.nativeCycle() == nativeCycle) found = generation;
        }
        if (found != null) return found;
        var generation = new Generation(UUID.randomUUID(), scope, nativeCycle, now());
        write("generation-" + generation.id() + ".json", "generation", generation);
        generations.put(generation.id(), generation);
        return generation;
    }

    public Entry admit(Admission admission) throws IOException {
        active(); Objects.requireNonNull(admission);
        if (!generations.containsKey(admission.generationId())) throw new IllegalArgumentException("Unknown admitted generation");
        String key = key(admission);
        Entry previous = entries.get(key);
        if (previous != null) {
            if (!previous.admission().equals(admission)) throw new IllegalArgumentException("Observation identity has a different admitted payload");
            return previous;
        }
        long at = now();
        var entry = new Entry(key, 0, admission, State.PREPARED, null, null, at, at);
        replace(entry); return entry;
    }

    public Entry outcome(String id, Outcome outcome) throws IOException {
        active(); Objects.requireNonNull(outcome);
        if (outcome == Outcome.MISSING) throw new IllegalArgumentException("Missing outcomes are assigned only during restart recovery");
        Entry entry = require(id);
        if (entry.state() != State.PREPARED) {
            if (entry.outcome() == outcome) return entry;
            throw new IllegalStateException("Evidence already has an outcome; operator review is required");
        }
        var updated = new Entry(id, entry.revision()+1, entry.admission(), outcome == Outcome.RETURNED ? State.CONFIRMED : State.REVIEW,
                outcome, null, entry.createdAt(), now());
        replace(updated); return updated;
    }

    public Entry resolve(String id, UUID operator, String explanation, boolean accepted) throws IOException {
        active(); Entry entry = require(id);
        if (entry.state() != State.REVIEW) throw new IllegalStateException("Only review entries can be resolved");
        var resolution = new Resolution(operator, explanation, accepted, now());
        var updated = new Entry(id, entry.revision()+1, entry.admission(), accepted ? State.CONFIRMED : State.REJECTED,
                entry.outcome(), resolution, entry.createdAt(), now());
        replace(updated); return updated;
    }

    /** Caller must first apply the absolute confirmed total through the native task owner. */
    public Entry applied(String id) throws IOException {
        active(); Entry entry = require(id);
        if (entry.state() == State.APPLIED) return entry;
        if (entry.state() != State.CONFIRMED) throw new IllegalStateException("Only confirmed evidence can be applied");
        var updated = new Entry(id, entry.revision()+1, entry.admission(), State.APPLIED, entry.outcome(), entry.resolution(), entry.createdAt(), now());
        replace(updated); return updated;
    }

    public long confirmedTotal(UUID generation, String taskId) {
        active(); String task = objectId(taskId); long total = 0;
        for (Entry entry : entries.values()) if (entry.admission().generationId().equals(generation) && entry.admission().taskId().equals(task)
                && (entry.state() == State.CONFIRMED || entry.state() == State.APPLIED)) total = Math.addExact(total, entry.admission().units());
        return total;
    }

    public long appliedTotal(UUID generation, String taskId) {
        active(); String task = objectId(taskId); long total = 0;
        for (Entry entry : entries.values()) if (entry.admission().generationId().equals(generation)
                && entry.admission().taskId().equals(task) && entry.state() == State.APPLIED)
            total = Math.addExact(total, entry.admission().units());
        return total;
    }

    public List<Entry> entries() { active(); return entries.values().stream().sorted(java.util.Comparator.comparingLong(Entry::createdAt).thenComparing(Entry::id)).toList(); }
    public List<Generation> generations() { active(); return generations.values().stream().sorted(java.util.Comparator.comparingLong(Generation::createdAt).thenComparing(Generation::id)).toList(); }

    @Override public void close() throws IOException {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Evidence journal requires its owning thread");
        if (closed) return;
        closed = true;
        try { lock.release(); } finally { lockChannel.close(); }
    }

    private void active() {
        if (closed || failed || Thread.currentThread() != owner) throw new IllegalStateException("Evidence journal is unavailable or accessed outside its owning thread");
    }
    private Entry require(String id) {
        Entry entry = entries.get(id);
        if (entry == null) throw new IllegalArgumentException("Unknown evidence entry");
        return entry;
    }
    private void replace(Entry entry) throws IOException {
        follows(entries.get(entry.id()),entry);
        write("entry-" + entry.id() + "-" + entry.revision() + ".json", "entry", entry);
        entries.put(entry.id(), entry);
    }
    private void write(String filename, String kind, Object payload) throws IOException {
        var document = new JsonObject(); document.addProperty("schema", 1); document.addProperty("kind", kind); document.add("payload", JSON.toJsonTree(payload));
        Path target = directory.resolve(filename), temporary = directory.resolve(filename + ".tmp-" + UUID.randomUUID());
        try {
            if (Files.exists(target)) throw new IOException("Immutable evidence record already exists: " + filename);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(JSON.toJson(document) + "\n");
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException | Error failure) {
            failed = true;
            // A failed/stopped write is evidence too. Keep its temporary file for explicit reconciliation.
            throw failure;
        }
    }
    private void load() throws IOException {
        var history = new HashMap<String, java.util.NavigableMap<Integer, Entry>>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.sorted().toList()) {
                String name = path.getFileName().toString();
                if (name.contains(".json.tmp-")) throw new IOException("Incomplete evidence write requires review: " + name);
                if (!name.endsWith(".json")) continue;
                try {
                    JsonObject document = JournalJson.parse(Files.readString(path, StandardCharsets.UTF_8));
                    if (!document.keySet().equals(Set.of("schema", "kind", "payload")) || JournalJson.integer(document.get("schema")) != 1)
                        throw new IllegalArgumentException("Unsupported evidence schema");
                    String kind = JournalJson.string(document.get("kind"));
                    JsonObject payload = document.getAsJsonObject("payload");
                    if (kind.equals("generation")) {
                        Generation generation = JournalJson.decode(JSON, payload, Generation.class);
                        if (!name.equals("generation-" + generation.id() + ".json") || generations.putIfAbsent(generation.id(), generation) != null)
                            throw new IllegalArgumentException("Generation identity mismatch");
                    } else if (kind.equals("entry")) {
                        Entry entry = JournalJson.decode(JSON, payload, Entry.class);
                        if (!name.equals("entry-" + entry.id() + "-" + entry.revision() + ".json")
                                || history.computeIfAbsent(entry.id(),ignored->new java.util.TreeMap<>()).putIfAbsent(entry.revision(),entry) != null)
                            throw new IllegalArgumentException("Entry identity mismatch");
                    } else throw new IllegalArgumentException("Unknown evidence record kind");
                } catch (RuntimeException failure) { throw new IOException("Invalid evidence file: " + name, failure); }
            }
        }
        var scopes = new HashMap<Scope, Set<Integer>>();
        for (Generation generation : generations.values()) if (!scopes.computeIfAbsent(generation.scope(), ignored -> new java.util.HashSet<>()).add(generation.nativeCycle()))
            throw new IOException("Duplicate generation for scope/cycle");
        for (var chain : history.values()) {
            Entry previous = null;
            for (Entry entry : chain.values()) {
                try { follows(previous,entry); }
                catch (RuntimeException invalid) { throw new IOException("Invalid evidence transition history: " + entry.id(),invalid); }
                if (!generations.containsKey(entry.admission().generationId())) throw new IOException("Entry references an unavailable generation");
                previous = entry;
            }
            entries.put(previous.id(),previous);
        }
    }
    private static void follows(Entry previous, Entry next) {
        if (previous == null) {
            if (next.revision() != 0 || next.state() != State.PREPARED) throw new IllegalArgumentException("Evidence history must start with admission");
            return;
        }
        if (next.revision() != previous.revision()+1 || !next.id().equals(previous.id())
                || !next.admission().equals(previous.admission()) || next.createdAt() != previous.createdAt())
            throw new IllegalArgumentException("Evidence history has a gap or changed admission");
        boolean valid = switch (previous.state()) {
            case PREPARED -> (next.state() == State.CONFIRMED || next.state() == State.REVIEW) && next.resolution() == null;
            case REVIEW -> (next.state() == State.CONFIRMED || next.state() == State.REJECTED)
                    && next.resolution() != null && next.outcome() == previous.outcome();
            case CONFIRMED -> next.state() == State.APPLIED && next.outcome() == previous.outcome()
                    && Objects.equals(next.resolution(),previous.resolution());
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("Evidence state transition is not allowed");
    }

    private static String objectId(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{1,16}") || Long.parseUnsignedLong(value, 16) == 0) throw new IllegalArgumentException("Invalid native quest object ID");
        return String.format(java.util.Locale.ROOT, "%016X", Long.parseUnsignedLong(value, 16));
    }
    private static String key(Admission admission) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("v1\0" + admission.observationId() + "\0"
                + admission.generationId() + "\0" + admission.taskId()).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static long now() { return System.currentTimeMillis(); }
}
