package net.citizensnpcs.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

import com.google.gson.*;

/** Durable monetary intents. Native FTB claims and Economy transactions remain owned by their providers. */
public final class QuestRewardJournal implements AutoCloseable {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private final Path directory;
    private final Thread owner = Thread.currentThread();
    private final FileChannel channel;
    private final FileLock lock;
    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private final Map<String, Claim> claims = new LinkedHashMap<>();
    private boolean closed, failed;

    public enum State { PREPARED, ACCEPTED, PAID, REVIEW, VOID }

    public record Binding(UUID epoch, String rewardId, String questId, boolean teamReward, boolean repeatable,
            String definitionRevision, String currencyId, int currencyScale, long amount, UUID operatorId, long createdAt) {
        public Binding {
            Objects.requireNonNull(epoch); Objects.requireNonNull(operatorId);
            rewardId = objectId(rewardId); questId = objectId(questId);
            if (definitionRevision == null || !definitionRevision.matches("[0-9a-f]{64}") || currencyId == null
                    || currencyId.isBlank() || !currencyId.equals(currencyId.trim()) || currencyScale < 0
                    || amount <= 0 || createdAt < 0) throw new IllegalArgumentException("Invalid reward binding");
        }
    }
    /** Owner is the native claim owner; beneficiary is always the actual first accepted claimant. */
    public record Intent(Binding binding, UUID teamId, UUID ownerId, UUID beneficiaryId, int nativeCycle) {
        public Intent {
            Objects.requireNonNull(binding); Objects.requireNonNull(teamId); Objects.requireNonNull(ownerId); Objects.requireNonNull(beneficiaryId);
            if (nativeCycle < 0 || (!binding.repeatable() && nativeCycle != 0)
                    || !ownerId.equals(binding.teamReward() ? teamId : beneficiaryId)) throw new IllegalArgumentException("Invalid native claim ownership");
        }
        public String operationKey() { return "citizens:ftb-reward:v1:" + key(this) + ":" + beneficiaryId; }
        public String sourceReference() { return "ftb:" + binding.questId() + ":" + binding.rewardId() + ":" + binding.epoch() + ":" + teamId + ":" + nativeCycle; }
    }
    public record Resolution(UUID operatorId, String reason, boolean accepted, long at) {
        public Resolution { Objects.requireNonNull(operatorId); if (reason == null || reason.isBlank() || at < 0) throw new IllegalArgumentException("Review needs an operator and reason"); }
    }
    public record Claim(String id, int revision, Intent intent, State state, Integer afterCycle,
            UUID transactionId, Resolution resolution, long createdAt, long updatedAt) {
        public Claim {
            Objects.requireNonNull(intent); Objects.requireNonNull(state);
            if (!key(intent).equals(id) || revision < 0 || createdAt < 0 || updatedAt < createdAt
                    || (afterCycle != null && (afterCycle < intent.nativeCycle() || (long) afterCycle > (long) intent.nativeCycle() + 1)))
                throw new IllegalArgumentException("Invalid claim identity/outcome");
            boolean valid = switch (state) {
                case PREPARED -> afterCycle == null && transactionId == null && resolution == null;
                case ACCEPTED -> transactionId == null && (resolution == null ? afterCycle != null : resolution.accepted());
                case PAID -> transactionId != null && (resolution == null ? afterCycle != null : resolution.accepted());
                case REVIEW -> transactionId == null && resolution == null;
                case VOID -> transactionId == null && resolution != null && !resolution.accepted();
            };
            if (!valid) throw new IllegalArgumentException("Invalid claim state data");
        }
    }

    public static QuestRewardJournal open(Path directory) throws IOException { return new QuestRewardJournal(directory); }
    private QuestRewardJournal(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize(); Files.createDirectories(this.directory);
        channel = FileChannel.open(this.directory.resolve(".journal.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            try { acquired = channel.tryLock(); }
            catch (java.nio.channels.OverlappingFileLockException busy) { throw new IOException("Reward journal is already open", busy); }
            if (acquired == null) throw new IOException("Reward journal is already open");
            lock = acquired; load();
            for (Claim claim : List.copyOf(claims.values())) if (claim.state() == State.PREPARED) review(claim.id());
        } catch (IOException | RuntimeException | Error failure) {
            try { if (acquired != null) acquired.release(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            try { channel.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    public Binding bind(Binding binding) throws IOException {
        active(); Objects.requireNonNull(binding);
        if (bindings.containsKey(binding.rewardId())) throw new IllegalStateException("Reward is already bound; migration requires explicit review");
        write("binding-" + binding.rewardId() + ".json", "binding", binding); bindings.put(binding.rewardId(), binding); return binding;
    }
    public List<Binding> bindings() { active(); return List.copyOf(bindings.values()); }
    public List<Claim> claims() { active(); return List.copyOf(claims.values()); }
    public Claim get(String id) { active(); Claim claim = claims.get(id); if (claim == null) throw new IllegalArgumentException("Unknown reward claim"); return claim; }

    public Claim prepare(Intent intent) throws IOException {
        active(); Objects.requireNonNull(intent);
        if (!intent.binding().equals(bindings.get(intent.binding().rewardId()))) throw new IllegalArgumentException("Unowned reward binding");
        for (Claim previous : claims.values()) {
            var old = previous.intent();
            if (!old.teamId().equals(intent.teamId()) || !old.binding().questId().equals(intent.binding().questId())) continue;
            if (previous.state() == State.REVIEW || previous.state() == State.PREPARED)
                throw new IllegalStateException("Uncertain native quest claim requires review before another admission");
            int observed = previous.afterCycle() == null ? old.nativeCycle() : previous.afterCycle();
            if (observed > intent.nativeCycle()) throw new IllegalStateException("Native quest cycle regressed; reconcile the FTB backup first");
        }
        if (claims.containsKey(key(intent))) throw new IllegalStateException("This native reward generation already has an immutable beneficiary and intent");
        long at = System.currentTimeMillis();
        Claim claim = new Claim(key(intent), 0, intent, State.PREPARED, null, null, null, at, at); replace(claim); return claim;
    }
    public Claim accepted(String id, int afterCycle) throws IOException {
        Claim previous = get(id);
        if (previous.state() != State.PREPARED) throw new IllegalStateException("Native claim is no longer pending");
        return transition(previous, State.ACCEPTED, afterCycle, null, null);
    }
    public Claim review(String id) throws IOException {
        Claim previous = get(id);
        if (previous.state() == State.REVIEW) return previous;
        if (previous.state() != State.PREPARED) throw new IllegalStateException("Only an uncertain native claim needs review");
        return transition(previous, State.REVIEW, null, null, null);
    }
    public Claim resolve(String id, UUID operatorId, String reason, boolean accepted) throws IOException {
        Claim previous = get(id);
        if (previous.state() != State.REVIEW) throw new IllegalStateException("Only review claims can be resolved");
        return transition(previous, accepted ? State.ACCEPTED : State.VOID, previous.afterCycle(), null,
                new Resolution(operatorId, reason, accepted, System.currentTimeMillis()));
    }
    public Claim paid(String id, UUID transactionId) throws IOException {
        Claim previous = get(id);
        if (previous.state() == State.PAID && previous.transactionId().equals(transactionId)) return previous;
        if (previous.state() != State.ACCEPTED) throw new IllegalStateException("Only an accepted claim can be paid");
        return transition(previous, State.PAID, previous.afterCycle(), Objects.requireNonNull(transactionId), previous.resolution());
    }
    private Claim transition(Claim previous, State state, Integer afterCycle, UUID transaction, Resolution resolution) throws IOException {
        Claim next = new Claim(previous.id(), previous.revision() + 1, previous.intent(), state, afterCycle, transaction, resolution,
                previous.createdAt(), Math.max(previous.updatedAt(), System.currentTimeMillis())); replace(next); return next;
    }
    private void replace(Claim next) throws IOException {
        active(); follows(claims.get(next.id()), next);
        write("claim-" + next.id() + "-" + next.revision() + ".json", "claim", next); claims.put(next.id(), next);
    }
    private void write(String filename, String kind, Object payload) throws IOException {
        var document = new JsonObject(); document.addProperty("schema", 1); document.addProperty("kind", kind); document.add("payload", JSON.toJsonTree(payload));
        Path target = directory.resolve(filename), temporary = directory.resolve(filename + ".tmp-" + UUID.randomUUID());
        try {
            if (Files.exists(target)) throw new IOException("Immutable reward record already exists: " + filename);
            try (var output = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(JSON.toJson(document) + "\n"); while (bytes.hasRemaining()) output.write(bytes); output.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException | Error failure) { failed = true; throw failure; }
    }
    private void load() throws IOException {
        var histories = new TreeMap<String, NavigableMap<Integer, Claim>>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.sorted().toList()) {
                String name = path.getFileName().toString();
                if (name.contains(".json.tmp-")) throw new IOException("Incomplete reward write requires review: " + name);
                if (!name.endsWith(".json")) continue;
                try {
                    JsonObject document = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                    if (!document.keySet().equals(Set.of("schema", "kind", "payload")) || document.get("schema").getAsInt() != 1) throw new IllegalArgumentException("Unknown reward schema");
                    JsonObject payload = document.getAsJsonObject("payload");
                    if (document.get("kind").getAsString().equals("binding")) {
                        fields(payload, Binding.class); Binding binding = JSON.fromJson(payload, Binding.class);
                        if (!name.equals("binding-" + binding.rewardId() + ".json") || bindings.putIfAbsent(binding.rewardId(), binding) != null) throw new IllegalArgumentException("Binding identity mismatch");
                    } else if (document.get("kind").getAsString().equals("claim")) {
                        fields(payload, Claim.class); fields(payload.getAsJsonObject("intent"), Intent.class);
                        fields(payload.getAsJsonObject("intent").getAsJsonObject("binding"), Binding.class);
                        if (!payload.get("resolution").isJsonNull()) fields(payload.getAsJsonObject("resolution"), Resolution.class);
                        Claim claim = JSON.fromJson(payload, Claim.class);
                        if (!name.equals("claim-" + claim.id() + "-" + claim.revision() + ".json")
                                || histories.computeIfAbsent(claim.id(), ignored -> new TreeMap<>()).putIfAbsent(claim.revision(), claim) != null) throw new IllegalArgumentException("Claim identity mismatch");
                    } else throw new IllegalArgumentException("Unknown reward record kind");
                } catch (RuntimeException failure) { throw new IOException("Invalid reward file: " + name, failure); }
            }
        }
        for (var history : histories.values()) {
            Claim previous = null;
            for (Claim next : history.values()) {
                try { follows(previous, next); if (!next.intent().binding().equals(bindings.get(next.intent().binding().rewardId()))) throw new IllegalArgumentException("Missing/changed claim binding"); }
                catch (RuntimeException invalid) { throw new IOException("Invalid reward history: " + next.id(), invalid); }
                previous = next;
            }
            claims.put(previous.id(), previous);
        }
    }
    private static void follows(Claim previous, Claim next) {
        if (previous == null) { if (next.revision() != 0 || next.state() != State.PREPARED) throw new IllegalArgumentException("Reward history must start with preparation"); return; }
        if (next.revision() != previous.revision() + 1 || !next.intent().equals(previous.intent()) || next.createdAt() != previous.createdAt()
                || next.updatedAt() < previous.updatedAt()) throw new IllegalArgumentException("Reward history changed intent or has a gap");
        boolean valid = switch (previous.state()) {
            case PREPARED -> next.state() == State.ACCEPTED || next.state() == State.REVIEW;
            case REVIEW -> next.state() == State.ACCEPTED || next.state() == State.VOID;
            case ACCEPTED -> next.state() == State.PAID && Objects.equals(previous.afterCycle(), next.afterCycle()) && Objects.equals(previous.resolution(), next.resolution());
            default -> false;
        };
        if (!valid || (previous.state() == State.PREPARED && next.resolution() != null)
                || (previous.state() == State.REVIEW && (next.resolution() == null || !Objects.equals(previous.afterCycle(), next.afterCycle())))) throw new IllegalArgumentException("Invalid reward transition");
    }
    private static void fields(JsonObject payload, Class<?> record) {
        var expected = Arrays.stream(record.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet());
        if (!payload.keySet().equals(expected)) throw new IllegalArgumentException("Reward fields differ from the schema");
    }
    static String objectId(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{1,16}") || Long.parseUnsignedLong(value, 16) == 0) throw new IllegalArgumentException("Invalid FTB object ID");
        return String.format(Locale.ROOT, "%016X", Long.parseUnsignedLong(value, 16));
    }
    private static String key(Intent value) {
        var binding = value.binding(); return digest("reward-v1\0" + binding.epoch() + "\0" + value.teamId() + "\0" + binding.questId()
                + "\0" + binding.rewardId() + "\0" + value.ownerId() + "\0" + value.nativeCycle());
    }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private void active() { if (closed || failed || Thread.currentThread() != owner) throw new IllegalStateException("Reward journal requires its active owning thread"); }
    @Override public void close() throws IOException {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Reward journal requires its owning thread");
        if (closed) return; closed = true; try { lock.release(); } finally { channel.close(); }
    }
}
