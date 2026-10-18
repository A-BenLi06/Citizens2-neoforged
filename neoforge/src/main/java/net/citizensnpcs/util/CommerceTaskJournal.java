package net.citizensnpcs.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;

/** Retained native trade attribution and committed receipt application; never a money or inventory owner. */
public final class CommerceTaskJournal implements AutoCloseable {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private final Path directory;
    private final Thread owner = Thread.currentThread();
    private final FileChannel channel;
    private final FileLock lock;
    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Cursor> cursors = new LinkedHashMap<>();
    private boolean failed, closed;
    public enum Shop { SYSTEM, COORDINATE }
    public enum Action { BUY_FROM_SHOP, SELL_TO_SHOP }
    public enum Measure { ITEMS, TRADES, MINOR_UNITS }
    public enum State { PREPARED, WAITING, CONFIRMED, APPLIED, REVIEW, REJECTED }

    public record Binding(UUID epoch, String taskId, String questId, String definitionRevision, long maximum, boolean repeatable,
            UUID journalId, long startingSequence, Shop shopType, String shopId, String itemKey, Action action,
            Measure measure, String currencyId, boolean requireDelivery, UUID operatorId, long createdAt) {
        public Binding {
            Objects.requireNonNull(epoch); Objects.requireNonNull(journalId); Objects.requireNonNull(operatorId);
            Objects.requireNonNull(shopType); Objects.requireNonNull(action); Objects.requireNonNull(measure);
            taskId = QuestRewardJournal.objectId(taskId); questId = QuestRewardJournal.objectId(questId);
            if (definitionRevision == null || !definitionRevision.matches("[0-9a-f]{64}") || maximum <= 0 || startingSequence < 0 || createdAt < 0 || shopId == null || shopId.isBlank()
                    || itemKey == null || (shopType == Shop.SYSTEM ? itemKey.isBlank() : !itemKey.isEmpty())
                    || (measure == Measure.MINOR_UNITS ? currencyId == null || currencyId.isBlank() : currencyId != null))
                throw new IllegalArgumentException("Invalid commerce task binding");
        }
    }
    public record Request(Shop shopType, String shopId, String itemKey, Action action, UUID actorId,
            Integer trades, String requestKey, String origin, UUID unkeyedOperationId) {
        public Request {
            Objects.requireNonNull(shopType); Objects.requireNonNull(action); Objects.requireNonNull(actorId);
            if (shopId == null || shopId.isBlank() || itemKey == null || (trades != null && trades <= 0)
                    || (requestKey == null ? unkeyedOperationId == null : requestKey.isBlank() || unkeyedOperationId != null))
                throw new IllegalArgumentException("Invalid native commerce request");
        }
    }
    public record Attribution(UUID teamId, int cycle, long observedSequence, long createdAt) {
        public Attribution { Objects.requireNonNull(teamId); if (cycle < 0 || observedSequence < 0 || createdAt < 0) throw new IllegalArgumentException("Invalid commerce attribution"); }
    }
    public record Receipt(UUID operationId, UUID transactionId, UUID deliveryClaimId, long sequence, String currencyId, long minorUnits,
            int items, Integer trades, String itemSnapshot, String legacyMaterial, long createdAt) {
        public Receipt {
            Objects.requireNonNull(operationId); Objects.requireNonNull(transactionId);
            if (sequence <= 0 || minorUnits < 0 || items <= 0 || (trades != null && trades <= 0)
                    || currencyId == null || currencyId.isBlank() || itemSnapshot == null || legacyMaterial == null || createdAt < 0)
                throw new IllegalArgumentException("Invalid committed commerce receipt");
        }
    }
    public record Resolution(UUID operatorId, String reason, boolean accepted, long at) {
        public Resolution { Objects.requireNonNull(operatorId); if (reason == null || reason.isBlank() || at < 0) throw new IllegalArgumentException("Review requires an authenticated explanation"); }
    }
    public record Entry(String id, int revision, Binding binding, Request request, Attribution attribution,
            Receipt receipt, State state, Resolution resolution, long createdAt, long updatedAt) {
        public Entry {
            Objects.requireNonNull(binding); Objects.requireNonNull(request); Objects.requireNonNull(state);
            if (!key(binding, request).equals(id) || revision < 0 || createdAt < 0 || updatedAt < createdAt || !matches(binding, request)
                    || (attribution != null && (attribution.observedSequence() < binding.startingSequence() || (!binding.repeatable() && attribution.cycle() != 0)))
                    || (receipt != null && receipt.sequence() <= binding.startingSequence())) throw new IllegalArgumentException("Invalid commerce evidence identity");
            boolean valid = switch (state) {
                case PREPARED -> attribution != null && receipt == null && resolution == null;
                case WAITING -> attribution != null && receipt != null && resolution == null;
                case CONFIRMED, APPLIED -> attribution != null && receipt != null && (resolution == null || resolution.accepted());
                case REVIEW -> receipt != null && resolution == null;
                case REJECTED -> receipt != null && resolution != null && !resolution.accepted();
            };
            if (!valid) throw new IllegalArgumentException("Invalid commerce evidence state");
            if (receipt != null && (!Objects.equals(receipt.trades(), request.trades())
                    || (request.unkeyedOperationId() != null && !request.unkeyedOperationId().equals(receipt.operationId()))))
                throw new IllegalArgumentException("Receipt differs from request metadata");
            if ((state == State.WAITING || state == State.CONFIRMED || state == State.APPLIED) && !measurable(binding, receipt))
                throw new IllegalArgumentException("Receipt lacks the bound measure");
            if (resolution == null && attribution != null && receipt != null && state != State.REVIEW
                    && receipt.sequence() <= attribution.observedSequence()) throw new IllegalArgumentException("Receipt predates native attribution");
        }
        public long units() {
            if (receipt == null) throw new IllegalStateException("No settlement evidence");
            return switch (binding.measure()) {
                case ITEMS -> receipt.items();
                case TRADES -> { if (receipt.trades() == null) throw new IllegalStateException("Legacy trade quantity was not recorded"); yield receipt.trades(); }
                case MINOR_UNITS -> { if (!binding.currencyId().equals(receipt.currencyId())) throw new IllegalStateException("Receipt currency differs"); yield receipt.minorUnits(); }
            };
        }
    }
    public record Cursor(UUID epoch, UUID actorId, UUID journalId, long sequence, long highWater, int revision, List<String> evidenceIds) {
        public Cursor {
            Objects.requireNonNull(epoch); Objects.requireNonNull(actorId); Objects.requireNonNull(journalId);
            evidenceIds = List.copyOf(evidenceIds);
            if (sequence < 0 || highWater < sequence || revision < 0 || !evidenceIds.equals(evidenceIds.stream().distinct().sorted().toList())
                    || evidenceIds.stream().anyMatch(id -> !id.matches("[0-9a-f]{64}"))) throw new IllegalArgumentException("Invalid receipt checkpoint");
        }
    }
    public static CommerceTaskJournal open(Path path) throws IOException { return new CommerceTaskJournal(path); }
    private CommerceTaskJournal(Path path) throws IOException {
        directory = path.toAbsolutePath().normalize(); Files.createDirectories(directory);
        channel = FileChannel.open(directory.resolve(".journal.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            try { acquired = channel.tryLock(); } catch (OverlappingFileLockException busy) { throw new IOException("Commerce task journal is already open", busy); }
            if (acquired == null) throw new IOException("Commerce task journal is already open"); lock = acquired; load();
        } catch (IOException | RuntimeException | Error failure) {
            try { if (acquired != null) acquired.release(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            try { channel.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    public Binding bind(Binding value) throws IOException {
        active(); if (bindings.containsKey(value.taskId()) || bindings.values().stream().anyMatch(old -> old.epoch().equals(value.epoch()))) throw new IllegalStateException("Task binding is immutable");
        write("binding-" + value.taskId() + ".json", "binding", value); bindings.put(value.taskId(), value); return value;
    }
    public List<Binding> bindings() { active(); return List.copyOf(bindings.values()); }
    public List<Entry> entries() { active(); return List.copyOf(entries.values()); }
    public Entry entry(String id) { active(); var value = entries.get(id); if (value == null) throw new IllegalArgumentException("Unknown receipt evidence"); return value; }
    public Entry prepare(Binding binding, Request request, Attribution attribution) throws IOException {
        active(); owned(binding);
        if (request.requestKey() == null || request.trades() == null) throw new IllegalArgumentException("Native attribution needs original request metadata");
        var previous = entries.get(key(binding, request));
        if (previous != null) { if (!previous.request().equals(request)) throw new IllegalArgumentException("Request key changed payload"); return previous; }
        for (Entry old : entries.values()) if (old.binding().epoch().equals(binding.epoch()) && old.attribution() != null
                && old.attribution().teamId().equals(attribution.teamId())) {
            if (old.attribution().cycle() > attribution.cycle()) throw new IllegalStateException("Native task cycle regressed");
            if (old.state() == State.REVIEW && old.attribution().cycle() == attribution.cycle()) throw new IllegalStateException("Uncertain commerce evidence requires review");
        }
        long at = System.currentTimeMillis(); var entry = new Entry(key(binding, request), 0, binding, request, attribution, null, State.PREPARED, null, at, at);
        replace(entry); return entry;
    }
    /** Unattributed or delivery-uncertain settlement is retained for review, never assigned to today's team. */
    public Entry receipt(Binding binding, Request request, Receipt receipt, boolean confirmed) throws IOException {
        active(); owned(binding); Entry previous = entries.get(key(binding, request));
        if (previous != null && !previous.request().equals(request)) throw new IllegalArgumentException("Committed request differs from its admission");
        if (previous != null && previous.receipt() != null) {
            if (!previous.receipt().equals(receipt)) throw new IllegalArgumentException("Receipt identity changed payload");
            return confirmed ? ready(previous.id()) : previous;
        }
        for (Entry old : entries.values()) if (old.binding().epoch().equals(binding.epoch()) && old.receipt() != null
                && (old.receipt().operationId().equals(receipt.operationId()) || old.receipt().sequence() == receipt.sequence()))
            throw new IllegalArgumentException("Operation or sequence was rebound to another request");
        if (previous == null) {
            long at = System.currentTimeMillis(); var value = new Entry(key(binding, request), 0, binding, request, null, receipt, State.REVIEW, null, at, at);
            replace(value); return value;
        }
        State state = receipt.sequence() > previous.attribution().observedSequence() && measurable(binding, receipt)
                ? confirmed ? State.CONFIRMED : State.WAITING : State.REVIEW;
        var value = new Entry(previous.id(), previous.revision() + 1, binding, request, previous.attribution(), receipt, state, null, previous.createdAt(), System.currentTimeMillis());
        if (state == State.CONFIRMED) value.units(); replace(value); return value;
    }
    /** Only a previously attributed, measurable receipt may recover automatically when its provider outcome settles. */
    public Entry ready(String id) throws IOException {
        var previous = entry(id); if (previous.state() != State.WAITING) return previous;
        var next = new Entry(id, previous.revision() + 1, previous.binding(), previous.request(), previous.attribution(), previous.receipt(),
                State.CONFIRMED, null, previous.createdAt(), System.currentTimeMillis()); replace(next); return next;
    }
    public Entry applied(String id) throws IOException {
        var value = entry(id); if (value.state() == State.APPLIED) return value;
        if (value.state() != State.CONFIRMED) throw new IllegalStateException("Receipt is not confirmed");
        var next = new Entry(id, value.revision() + 1, value.binding(), value.request(), value.attribution(), value.receipt(), State.APPLIED,
                value.resolution(), value.createdAt(), System.currentTimeMillis()); replace(next); return next;
    }
    public Entry resolve(String id, Attribution attribution, UUID operator, String reason, boolean accept) throws IOException {
        var previous = entry(id); if (previous.state() != State.REVIEW) throw new IllegalStateException("Only review evidence can be resolved");
        if (previous.attribution() != null && !previous.attribution().equals(attribution)) throw new IllegalArgumentException("Cannot redirect original native attribution");
        var resolution = new Resolution(operator, reason, accept, System.currentTimeMillis());
        var next = new Entry(id, previous.revision() + 1, previous.binding(), previous.request(), attribution, previous.receipt(),
                accept ? State.CONFIRMED : State.REJECTED, resolution, previous.createdAt(), System.currentTimeMillis());
        if (accept) next.units(); replace(next); return next;
    }
    public long total(Binding binding, UUID team, int cycle, boolean onlyApplied) {
        active(); long total = 0;
        for (Entry value : entries.values()) if (value.binding().equals(binding) && value.attribution() != null
                && value.attribution().teamId().equals(team) && value.attribution().cycle() == cycle
                && (value.state() == State.APPLIED || !onlyApplied && value.state() == State.CONFIRMED))
            total += Math.min(binding.maximum() - total, value.units());
        return total;
    }
    public void validateCycle(Binding binding, UUID team, int cycle) {
        active(); owned(binding);
        for (Entry value : entries.values()) if (value.binding().equals(binding) && value.attribution() != null
                && value.attribution().teamId().equals(team) && value.attribution().cycle() > cycle)
            throw new IllegalStateException("Native task cycle regressed behind retained commerce evidence");
    }
    public long cursor(Binding binding, UUID actor, UUID journalId, long highWater) {
        active(); position(binding, journalId, highWater); var value = cursors.get(cursorKey(binding.epoch(), actor));
        long sequence = value == null ? binding.startingSequence() : value.sequence();
        if (sequence > highWater) throw new IllegalStateException("Commerce history cursor regressed; backup reconciliation is required"); return sequence;
    }
    public void checkpoint(Binding binding, UUID actor, UUID journalId, long highWater, long sequence) throws IOException {
        long before = cursor(binding, actor, journalId, highWater);
        if (sequence < before || sequence > highWater) throw new IllegalArgumentException("Invalid receipt checkpoint progression");
        String key = cursorKey(binding.epoch(), actor); var previous = cursors.get(key);
        if (sequence == before && (previous == null ? highWater == binding.startingSequence() : highWater == previous.highWater())) return;
        var next = new Cursor(binding.epoch(), actor, journalId, sequence, highWater, previous == null ? 0 : previous.revision() + 1, evidenceIds(binding.epoch(), actor, sequence));
        write("cursor-" + key + "-" + next.revision() + ".json", "cursor", next); cursors.put(key, next);
    }
    private void position(Binding binding, UUID journalId, long highWater) {
        owned(binding); if (!binding.journalId().equals(journalId) || highWater < binding.startingSequence()) throw new IllegalStateException("Economy journal changed or regressed");
        for (Entry value : entries.values()) if (value.binding().journalId().equals(journalId)) {
            if ((value.receipt() != null && value.receipt().sequence() > highWater) || (value.attribution() != null && value.attribution().observedSequence() > highWater))
                throw new IllegalStateException("Economy journal regressed behind retained evidence");
        }
        for (Cursor value : cursors.values()) if (value.journalId().equals(journalId) && value.highWater() > highWater)
            throw new IllegalStateException("Economy journal regressed behind an observed high-water mark");
    }
    private void owned(Binding binding) { if (!binding.equals(bindings.get(binding.taskId()))) throw new IllegalArgumentException("Unowned commerce binding"); }
    static boolean matches(Binding binding, Request request) { return binding.shopType() == request.shopType() && binding.shopId().equals(request.shopId())
            && binding.itemKey().equals(request.itemKey()) && binding.action() == request.action(); }
    private static String key(Binding binding, Request request) { return QuestRewardJournal.digest(binding.epoch() + "\0" + binding.journalId() + "\0" + request.actorId() + "\0"
            + (request.requestKey() == null ? "operation\0" + request.unkeyedOperationId() : "request\0" + request.requestKey())); }
    private static boolean measurable(Binding binding, Receipt receipt) { return (binding.measure() != Measure.TRADES || receipt.trades() != null)
            && (binding.measure() != Measure.MINOR_UNITS || binding.currencyId().equals(receipt.currencyId())); }
    private static String cursorKey(UUID epoch, UUID actor) { return QuestRewardJournal.digest(epoch + "\0" + actor); }
    private List<String> evidenceIds(UUID epoch, UUID actor, long sequence) {
        return entries.values().stream().filter(value -> value.binding().epoch().equals(epoch) && value.request().actorId().equals(actor)
                && value.receipt() != null && value.receipt().sequence() <= sequence).map(Entry::id).sorted().toList();
    }
    private void replace(Entry next) throws IOException { follows(entries.get(next.id()), next); write("entry-" + next.id() + "-" + next.revision() + ".json", "entry", next); entries.put(next.id(), next); }
    private void write(String name, String kind, Object payload) throws IOException {
        active(); var document = new JsonObject(); document.addProperty("schema", 1); document.addProperty("kind", kind); document.add("payload", JSON.toJsonTree(payload));
        Path target = directory.resolve(name), temporary = directory.resolve(name + ".tmp-" + UUID.randomUUID());
        try {
            if (Files.exists(target)) throw new IOException("Immutable commerce evidence record already exists");
            try (var file = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(JSON.toJson(document) + "\n"); while (bytes.hasRemaining()) file.write(bytes); file.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException | Error failure) { failed = true; throw failure; }
    }
    private void load() throws IOException {
        var histories = new TreeMap<String, NavigableMap<Integer, Entry>>(); var checkpoints = new TreeMap<String, NavigableMap<Integer, Cursor>>();
        try (var paths = Files.list(directory)) { for (Path path : paths.sorted().toList()) {
            String name = path.getFileName().toString(); if (name.contains(".json.tmp-")) throw new IOException("Incomplete commerce evidence requires review"); if (!name.endsWith(".json")) continue;
            try {
                var document = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                if (!document.keySet().equals(Set.of("schema", "kind", "payload")) || document.get("schema").getAsInt() != 1) throw new IllegalArgumentException("Unknown commerce evidence schema");
                JsonObject payload = document.getAsJsonObject("payload");
                switch (document.get("kind").getAsString()) {
                    case "binding" -> { fields(payload, Binding.class); var binding = JSON.fromJson(payload, Binding.class);
                        if (!name.equals("binding-" + binding.taskId() + ".json") || bindings.putIfAbsent(binding.taskId(), binding) != null) throw new IllegalArgumentException("Binding identity mismatch"); }
                    case "entry" -> { fields(payload, Entry.class); fields(payload.getAsJsonObject("binding"), Binding.class); fields(payload.getAsJsonObject("request"), Request.class);
                        if (!payload.get("attribution").isJsonNull()) fields(payload.getAsJsonObject("attribution"), Attribution.class);
                        if (!payload.get("receipt").isJsonNull()) fields(payload.getAsJsonObject("receipt"), Receipt.class);
                        if (!payload.get("resolution").isJsonNull()) fields(payload.getAsJsonObject("resolution"), Resolution.class);
                        var entry = JSON.fromJson(payload, Entry.class);
                        if (!name.equals("entry-" + entry.id() + "-" + entry.revision() + ".json") || histories.computeIfAbsent(entry.id(), ignored -> new TreeMap<>()).putIfAbsent(entry.revision(), entry) != null) throw new IllegalArgumentException("Entry identity mismatch"); }
                    case "cursor" -> { fields(payload, Cursor.class); var cursor = JSON.fromJson(payload, Cursor.class); String key = cursorKey(cursor.epoch(), cursor.actorId());
                        if (!name.equals("cursor-" + key + "-" + cursor.revision() + ".json") || checkpoints.computeIfAbsent(key, ignored -> new TreeMap<>()).putIfAbsent(cursor.revision(), cursor) != null) throw new IllegalArgumentException("Checkpoint identity mismatch"); }
                    default -> throw new IllegalArgumentException("Unknown commerce evidence record");
                }
            } catch (RuntimeException failure) { throw new IOException("Invalid commerce evidence file " + name, failure); }
        } }
        for (var history : histories.values()) {
            Entry previous = null;
            for (Entry next : history.values()) { try { follows(previous, next); owned(next.binding()); } catch (RuntimeException failure) { throw new IOException("Invalid commerce evidence history", failure); } previous = next; }
            entries.put(previous.id(), previous);
        }
        var epochs = new HashSet<UUID>();
        for (Binding value : bindings.values()) if (!epochs.add(value.epoch())) throw new IOException("Duplicate binding epoch");
        var operations = new HashSet<String>(); var sequences = new HashSet<String>();
        for (Entry value : entries.values()) if (value.receipt() != null) {
            if (!operations.add(value.binding().epoch() + ":" + value.receipt().operationId())
                    || !sequences.add(value.binding().epoch() + ":" + value.receipt().sequence())) throw new IOException("Receipt was bound to multiple requests");
        }
        for (var history : checkpoints.values()) {
            Cursor previous = null;
            for (Cursor next : history.values()) {
                var binding = bindings.values().stream().filter(value -> value.epoch().equals(next.epoch())).findFirst().orElseThrow(() -> new IOException("Checkpoint references missing binding"));
                if (!binding.journalId().equals(next.journalId()) || next.sequence() < binding.startingSequence()
                        || !next.evidenceIds().equals(evidenceIds(next.epoch(), next.actorId(), next.sequence()))
                        || (previous == null ? next.revision() != 0 : next.revision() != previous.revision() + 1
                            || !next.epoch().equals(previous.epoch()) || !next.actorId().equals(previous.actorId()) || !next.journalId().equals(previous.journalId())
                            || next.sequence() < previous.sequence() || next.highWater() < previous.highWater()
                            || (next.sequence() == previous.sequence() && next.highWater() == previous.highWater()))) throw new IOException("Invalid checkpoint history"); previous = next;
            }
            cursors.put(cursorKey(previous.epoch(), previous.actorId()), previous);
        }
    }
    private static void fields(JsonObject payload, Class<?> type) { var expected = Arrays.stream(type.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet()); if (!payload.keySet().equals(expected)) throw new IllegalArgumentException("Invalid commerce evidence fields"); }
    private static void follows(Entry previous, Entry next) {
        if (previous == null) { if (next.revision() != 0 || next.state() != State.PREPARED && next.state() != State.REVIEW) throw new IllegalArgumentException("Commerce evidence must start with admission or review"); return; }
        if (next.revision() != previous.revision() + 1 || !next.binding().equals(previous.binding()) || !next.request().equals(previous.request())
                || next.createdAt() != previous.createdAt() || next.updatedAt() < previous.updatedAt() || (previous.attribution() != null && !previous.attribution().equals(next.attribution()))
                || (previous.receipt() != null && !previous.receipt().equals(next.receipt()))) throw new IllegalArgumentException("Commerce evidence changed identity or has a gap");
        boolean valid = switch (previous.state()) {
            case PREPARED -> (next.state() == State.CONFIRMED || next.state() == State.WAITING || next.state() == State.REVIEW) && next.resolution() == null;
            case WAITING -> next.state() == State.CONFIRMED && next.resolution() == null;
            case CONFIRMED -> next.state() == State.APPLIED && Objects.equals(previous.resolution(), next.resolution());
            case REVIEW -> (next.state() == State.CONFIRMED || next.state() == State.REJECTED) && next.resolution() != null;
            default -> false;
        }; if (!valid) throw new IllegalArgumentException("Invalid commerce evidence transition");
    }
    private void active() { if (failed || closed || owner != Thread.currentThread()) throw new IllegalStateException("Commerce journal requires its active owning thread"); }
    @Override public void close() throws IOException { if (owner != Thread.currentThread()) throw new IllegalStateException("Commerce journal requires its owning thread"); if (closed) return; closed = true; try { lock.release(); } finally { channel.close(); } }
}
