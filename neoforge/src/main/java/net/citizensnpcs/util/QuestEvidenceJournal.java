package net.citizensnpcs.util;

import java.io.IOException;
import java.math.BigInteger;
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
import java.util.Optional;
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
    private static final java.util.Comparator<Entry> ENTRY_ORDER = java.util.Comparator.comparingLong(Entry::createdAt).thenComparing(Entry::id);
    private final Map<Scope, Generation> latestGeneration = new HashMap<>();
    private final Map<ObservationTask, Entry> firstObservation = new HashMap<>();
    private final Map<UUID, Map<String, Entry>> observations = new HashMap<>();
    private final Map<GenerationTask, TaskIndex> tasks = new HashMap<>();
    private boolean closed;
    private boolean failed;

    public enum State { PREPARED, CONFIRMED, REVIEW, REJECTED, APPLIED }
    public enum Outcome { RETURNED, INTERRUPTED, UNCONFIRMED, MISSING }

    private record ObservationTask(UUID observation, String task) { }
    private record GenerationTask(UUID generation, String task) { }
    private static final class TaskIndex {
        // Preserve query-time overflow: even an overflowing history remains admissible and recoverable.
        BigInteger confirmedTotal = BigInteger.ZERO, appliedTotal = BigInteger.ZERO;
        final java.util.NavigableSet<Entry> confirmed = new java.util.TreeSet<>(ENTRY_ORDER);
        final java.util.Set<String> review = new java.util.HashSet<>();
        void update(Entry entry, boolean add) {
            if (entry == null) return;
            BigInteger units = BigInteger.valueOf(entry.admission().units());
            if (!add) units = units.negate();
            if (entry.state() == State.CONFIRMED || entry.state() == State.APPLIED) confirmedTotal = confirmedTotal.add(units);
            if (entry.state() == State.APPLIED) appliedTotal = appliedTotal.add(units);
            if (entry.state() == State.CONFIRMED) { if (add) confirmed.add(entry); else confirmed.remove(entry); }
            if (entry.state() == State.REVIEW) { if (add) review.add(entry.id()); else review.remove(entry.id()); }
        }
    }

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

    @FunctionalInterface
    interface OpenValidation { void validate() throws IOException; }

    public static QuestEvidenceJournal open(Path directory) throws IOException { return open(directory, () -> { }); }

    /** Validate companion records under exclusive ownership before publishing restart recovery. */
    static QuestEvidenceJournal open(Path directory, OpenValidation validation) throws IOException {
        return new QuestEvidenceJournal(directory, Objects.requireNonNull(validation));
    }

    private QuestEvidenceJournal(Path directory, OpenValidation validation) throws IOException {
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
            validation.validate();
            for (Generation generation : generations.values()) index(generation);
            for (Entry entry : entries.values()) index(null, entry);
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
        Generation found = latestGeneration.get(scope);
        if (found != null) {
            if (found.nativeCycle() > nativeCycle) throw new IllegalStateException("Native cycle regressed; evidence reconciliation is required");
            if (found.nativeCycle() == nativeCycle) return found;
        }
        var generation = new Generation(UUID.randomUUID(), scope, nativeCycle, now());
        write("generation-" + generation.id() + ".json", "generation", generation);
        generations.put(generation.id(), generation);
        try { index(generation); } catch (RuntimeException | Error failure) { failed = true; throw failure; }
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
        active(); var task = tasks.get(new GenerationTask(generation, objectId(taskId)));
        return task == null ? 0 : task.confirmedTotal.longValueExact();
    }

    public long appliedTotal(UUID generation, String taskId) {
        active(); var task = tasks.get(new GenerationTask(generation, objectId(taskId)));
        return task == null ? 0 : task.appliedTotal.longValueExact();
    }

    /** Earliest retained admission across generations, using the administrative snapshot's stable ordering. */
    public Optional<Entry> previous(UUID observation, String taskId) {
        active(); return Optional.ofNullable(firstObservation.get(new ObservationTask(Objects.requireNonNull(observation), objectId(taskId))));
    }
    public Optional<Generation> generation(UUID id) { active(); return Optional.ofNullable(generations.get(Objects.requireNonNull(id))); }
    public List<Entry> observation(UUID id) {
        active(); var found = observations.get(Objects.requireNonNull(id));
        return found == null ? List.of() : found.values().stream().sorted(ENTRY_ORDER).toList();
    }
    public boolean hasReview(UUID generation, String taskId) {
        active(); var task = tasks.get(new GenerationTask(generation, objectId(taskId)));
        return task != null && !task.review.isEmpty();
    }
    /** Snapshot only work awaiting projection; APPLIED history does not grow this read. */
    public List<Entry> confirmed(UUID generation, String taskId) {
        active(); var task = tasks.get(new GenerationTask(generation, objectId(taskId)));
        return task == null ? List.of() : List.copyOf(task.confirmed);
    }
    public List<Entry> entries() { active(); return entries.values().stream().sorted(ENTRY_ORDER).toList(); }
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
        Entry previous = entries.get(entry.id());
        follows(previous,entry);
        write("entry-" + entry.id() + "-" + entry.revision() + ".json", "entry", entry);
        entries.put(entry.id(), entry);
        try { index(previous, entry); } catch (RuntimeException | Error failure) { failed = true; throw failure; }
    }
    private void index(Generation generation) {
        latestGeneration.merge(generation.scope(), generation, (left, right) -> left.nativeCycle() > right.nativeCycle() ? left : right);
    }
    private void index(Entry previous, Entry entry) {
        var admission = entry.admission();
        observations.computeIfAbsent(admission.observationId(), ignored -> new HashMap<>()).put(entry.id(), entry);
        firstObservation.merge(new ObservationTask(admission.observationId(), admission.taskId()), entry,
                (left, right) -> ENTRY_ORDER.compare(left, right) < 0 ? left : right);
        var task = tasks.computeIfAbsent(new GenerationTask(admission.generationId(), admission.taskId()), ignored -> new TaskIndex());
        task.update(previous, false);
        task.update(entry, true);
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
