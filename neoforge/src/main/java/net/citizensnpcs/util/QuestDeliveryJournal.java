package net.citizensnpcs.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import com.google.gson.*;

/** Retained delivery outcomes; Minecraft inventories and FTB saves remain independently owned. */
public final class QuestDeliveryJournal implements AutoCloseable {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    public enum State { PREPARED, CONFIRMED, REVIEW, RESOLVED }
    public record Target(UUID teamId, String taskId, String questId, int cycle, long maximum,
            long progress, boolean completed, String definition) {
        public Target {
            Objects.requireNonNull(teamId);
            taskId = id(taskId); questId = id(questId);
            if (cycle < 0 || maximum < 1 || progress < 0 || progress > maximum
                    || definition == null || !definition.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid delivery target");
        }
        public boolean sameDefinition(Target other) {
            return teamId.equals(other.teamId) && taskId.equals(other.taskId) && questId.equals(other.questId)
                    && maximum == other.maximum && definition.equals(other.definition);
        }
        public boolean retains(Target previous) {
            return sameDefinition(previous) && (cycle > previous.cycle
                    || cycle == previous.cycle && progress >= previous.progress && (!previous.completed || completed));
        }
    }
    public record Resolution(UUID operatorId, String reason, boolean accepted, long at) {
        public Resolution { Objects.requireNonNull(operatorId); if (reason == null || reason.isBlank() || at < 0) throw new IllegalArgumentException("Resolution requires an operator and reason"); }
    }
    public record Entry(UUID id, long sequence, UUID previousActorEntry, int revision, UUID actorId, Target before,
            String inventoryBefore, State state, Target after, String inventoryAfter, String uncertainty,
            Resolution resolution) {
        public Entry {
            Objects.requireNonNull(id); Objects.requireNonNull(actorId); Objects.requireNonNull(before); Objects.requireNonNull(state);
            inventory(inventoryBefore);
            if (sequence < 1 || revision < 0 || id.equals(previousActorEntry)) throw new IllegalArgumentException("Invalid delivery identity");
            if ((after == null) != (inventoryAfter == null)) throw new IllegalArgumentException("Incomplete delivery outcome");
            if (after != null) {
                inventory(inventoryAfter);
                if (!after.retains(before) || after.cycle() <= before.cycle() && after.progress() <= before.progress()
                        && (!after.completed() || before.completed())) throw new IllegalArgumentException("Delivery changed definition, regressed or did not advance");
            }
            boolean valid = switch (state) {
                case PREPARED -> after == null && uncertainty == null && resolution == null;
                case CONFIRMED -> after != null && uncertainty == null && resolution == null;
                case REVIEW -> uncertainty != null && !uncertainty.isBlank() && resolution == null;
                case RESOLVED -> uncertainty != null && !uncertainty.isBlank() && resolution != null;
            };
            if (!valid) throw new IllegalArgumentException("Invalid delivery state");
        }
    }
    private final Path directory;
    private final Thread owner = Thread.currentThread();
    private final FileChannel channel;
    private final FileLock lock;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private long sequence;
    private boolean failed, closed;
    public static QuestDeliveryJournal open(Path path) throws IOException { return new QuestDeliveryJournal(path); }
    private QuestDeliveryJournal(Path path) throws IOException {
        directory = path.toAbsolutePath().normalize(); Files.createDirectories(directory);
        channel = FileChannel.open(directory.resolve(".journal.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            try { acquired = channel.tryLock(); }
            catch (java.nio.channels.OverlappingFileLockException busy) { throw new IOException("Delivery journal is already open", busy); }
            if (acquired == null) throw new IOException("Delivery journal is already open");
            lock = acquired; load();
            for (Entry entry : List.copyOf(entries.values())) if (entry.state() == State.PREPARED)
                review(entry.id(), "Native delivery outcome is missing after restart");
        } catch (IOException | RuntimeException | Error failure) {
            try { if (acquired != null) acquired.release(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            try { channel.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    public List<Entry> entries() { active(); return List.copyOf(entries.values()); }
    public Optional<Entry> latest(UUID actor) {
        active(); return entries.values().stream().filter(entry -> entry.actorId().equals(actor)).max(Comparator.comparingLong(Entry::sequence));
    }
    public Entry get(UUID id) {
        active(); Entry entry = entries.get(id); if (entry == null) throw new IllegalArgumentException("Unknown delivery"); return entry;
    }
    public Entry prepare(UUID actor, Target before, String inventory) throws IOException {
        active();
        if (entries.values().stream().anyMatch(entry -> pending(entry) && (entry.actorId().equals(actor)
                || entry.before().teamId().equals(before.teamId()) && entry.before().taskId().equals(before.taskId()))))
            throw new IllegalStateException("An unresolved delivery owns this inventory or team task");
        Entry entry = new Entry(UUID.randomUUID(), Math.addExact(sequence, 1), latest(actor).map(Entry::id).orElse(null),
                0, actor, before, inventory, State.PREPARED, null, null, null, null);
        replace(entry); sequence = entry.sequence(); return entry;
    }
    public Entry confirm(UUID id, Target after, String inventory) throws IOException {
        Entry entry = get(id);
        if (entry.state() != State.PREPARED) throw new IllegalStateException("Delivery is not awaiting its native outcome");
        if (after.cycle() <= entry.before().cycle() && after.progress() <= entry.before().progress()
                && (!after.completed() || entry.before().completed())) throw new IllegalArgumentException("Native delivery did not advance");
        return replace(new Entry(entry.id(), entry.sequence(), entry.previousActorEntry(), entry.revision() + 1, entry.actorId(),
                entry.before(), entry.inventoryBefore(), State.CONFIRMED, after, inventory, null, null));
    }
    public Entry review(UUID id, String reason) throws IOException {
        Entry entry = get(id);
        if (entry.state() == State.REVIEW) return entry;
        if (entry.state() != State.PREPARED && entry.state() != State.CONFIRMED) throw new IllegalStateException("Delivery cannot enter review");
        return replace(new Entry(entry.id(), entry.sequence(), entry.previousActorEntry(), entry.revision() + 1, entry.actorId(),
                entry.before(), entry.inventoryBefore(), State.REVIEW, entry.after(), entry.inventoryAfter(), reason, null));
    }
    /** Records an operator decision only; it does not fabricate native progress, rewards or inventory. */
    public Entry resolve(UUID id, UUID operator, boolean accepted, String reason) throws IOException {
        Entry entry = get(id);
        if (entry.state() != State.REVIEW) throw new IllegalStateException("Only uncertain deliveries can be resolved");
        return replace(new Entry(entry.id(), entry.sequence(), entry.previousActorEntry(), entry.revision() + 1, entry.actorId(),
                entry.before(), entry.inventoryBefore(), State.RESOLVED, entry.after(), entry.inventoryAfter(), entry.uncertainty(),
                new Resolution(operator, reason, accepted, System.currentTimeMillis())));
    }
    public static boolean pending(Entry entry) { return entry.state() == State.PREPARED || entry.state() == State.REVIEW; }
    private Entry replace(Entry entry) throws IOException {
        active(); follows(entries.get(entry.id()), entry);
        Path target = directory.resolve(filename(entry)), temporary = directory.resolve(filename(entry) + ".tmp-" + UUID.randomUUID());
        var document = new JsonObject(); document.addProperty("schema", 1); document.add("entry", JSON.toJsonTree(entry));
        try {
            if (Files.exists(target)) throw new IOException("Immutable delivery revision already exists");
            try (var output = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(JSON.toJson(document) + "\n");
                while (bytes.hasRemaining()) output.write(bytes); output.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException | Error failure) { failed = true; throw failure; }
        entries.put(entry.id(), entry); return entry;
    }
    private void load() throws IOException {
        var histories = new HashMap<UUID, NavigableMap<Integer, Entry>>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.sorted().toList()) {
                String name = path.getFileName().toString();
                if (name.contains(".json.tmp-")) throw new IOException("Incomplete delivery write requires review: " + name);
                if (!name.endsWith(".json")) continue;
                try {
                    var document = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                    if (!document.keySet().equals(Set.of("schema", "entry")) || document.get("schema").getAsInt() != 1)
                        throw new IllegalArgumentException("Unknown delivery schema");
                    JsonObject payload = document.getAsJsonObject("entry"); fields(payload, Entry.class);
                    fields(payload.getAsJsonObject("before"), Target.class);
                    if (!payload.get("after").isJsonNull()) fields(payload.getAsJsonObject("after"), Target.class);
                    if (!payload.get("resolution").isJsonNull()) fields(payload.getAsJsonObject("resolution"), Resolution.class);
                    Entry entry = JSON.fromJson(payload, Entry.class);
                    if (!name.equals(filename(entry)) || histories.computeIfAbsent(entry.id(), ignored -> new TreeMap<>())
                            .putIfAbsent(entry.revision(), entry) != null) throw new IllegalArgumentException("Delivery identity mismatch");
                } catch (RuntimeException invalid) { throw new IOException("Invalid delivery record: " + name, invalid); }
            }
        }
        var ordered = new TreeMap<Long, Entry>();
        for (var history : histories.values()) {
            Entry previous = null;
            for (Entry next : history.values()) {
                try { follows(previous, next); } catch (RuntimeException invalid) { throw new IOException("Invalid delivery history", invalid); }
                previous = next;
            }
            if (ordered.putIfAbsent(previous.sequence(), previous) != null) throw new IOException("Duplicate delivery sequence");
        }
        var actors = new HashMap<UUID, UUID>();
        for (Entry entry : ordered.values()) {
            if (entry.sequence() != ++sequence || !Objects.equals(entry.previousActorEntry(), actors.get(entry.actorId())))
                throw new IOException("Delivery history has a gap or changed actor chain");
            actors.put(entry.actorId(), entry.id()); entries.put(entry.id(), entry);
        }
    }
    private static void follows(Entry previous, Entry next) {
        if (previous == null) {
            if (next.revision() != 0 || next.state() != State.PREPARED) throw new IllegalArgumentException("Delivery must start with preparation");
            return;
        }
        if (next.revision() != previous.revision() + 1 || next.sequence() != previous.sequence()
                || !next.id().equals(previous.id()) || !next.actorId().equals(previous.actorId())
                || !Objects.equals(next.previousActorEntry(), previous.previousActorEntry()) || !next.before().equals(previous.before())
                || !next.inventoryBefore().equals(previous.inventoryBefore())) throw new IllegalArgumentException("Delivery admission changed");
        boolean valid = switch (previous.state()) {
            case PREPARED -> next.state() == State.CONFIRMED || next.state() == State.REVIEW;
            case CONFIRMED -> next.state() == State.REVIEW;
            case REVIEW -> next.state() == State.RESOLVED;
            case RESOLVED -> false;
        };
        if (!valid || previous.state() == State.PREPARED && next.state() == State.REVIEW && next.after() != null
                || previous.state() != State.PREPARED && (!Objects.equals(next.after(), previous.after())
                || !Objects.equals(next.inventoryAfter(), previous.inventoryAfter()))
                || previous.state() == State.REVIEW && !next.uncertainty().equals(previous.uncertainty()))
            throw new IllegalArgumentException("Invalid delivery transition");
    }
    private static void fields(JsonObject object, Class<?> type) {
        var names = new HashSet<String>(); for (var field : type.getRecordComponents()) names.add(field.getName());
        if (!object.keySet().equals(names)) throw new IllegalArgumentException("Unknown or missing delivery fields");
    }
    private static String id(String value) { return String.format(Locale.ROOT, "%016X", FtbQuestTasks.parseId(value)); }
    private static void inventory(String value) {
        if (value == null || !JsonParser.parseString(value).isJsonArray()) throw new IllegalArgumentException("Expected a retained inventory array");
    }
    private static String filename(Entry entry) { return "delivery-" + entry.sequence() + "-" + entry.id() + "-" + entry.revision() + ".json"; }
    private void active() { if (closed || failed || Thread.currentThread() != owner) throw new IllegalStateException("Delivery journal requires its active owning thread"); }
    @Override public void close() throws IOException {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Delivery journal requires its owning thread");
        if (closed) return; closed = true; try { lock.release(); } finally { channel.close(); }
    }
}
