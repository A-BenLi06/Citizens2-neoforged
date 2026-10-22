package net.citizensnpcs.audit;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import com.google.gson.*;
import net.citizensnpcs.util.*;

/** Reopen only fresh copies; original retained records remain read-only evidence. */
public final class JournalReloadAudit {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    public static void main(String[] args) throws Exception {
        Path artifacts = Path.of(args[0]).toRealPath(), plan = Path.of(args[1]).toRealPath();
        require(plan.startsWith(artifacts), "Plan escaped artifacts");
        Path output = Files.createDirectory(artifacts.resolve("journal-reload-" + UUID.randomUUID()));
        var result = new JsonObject();
        result.addProperty("createdAt", OffsetDateTime.now().toString());
        var samples = new JsonArray(); result.add("samples", samples);
        for (var input : JsonParser.parseString(Files.readString(plan)).getAsJsonArray()) {
            var item = input.getAsJsonObject();
            String kind = item.get("kind").getAsString();
            Path source = artifacts.resolve(item.get("source").getAsString()).toRealPath();
            require(source.startsWith(artifacts), "Source escaped artifacts");
            Path copy = Files.createDirectory(output.resolve("sample-" + samples.size()));
            var originals = new LinkedHashMap<Path, byte[]>();
            // Ownership checks are held while copying, not while opening the new journal.
            try (var channel = FileChannel.open(source.resolve(".journal.lock"), StandardOpenOption.READ, StandardOpenOption.WRITE);
                    var guard = channel.tryLock()) {
                require(guard != null, "Source journal is in use");
                try (var paths = Files.list(source)) {
                    for (Path path : paths.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                        require(path.toRealPath().startsWith(source), "Record escaped source");
                        byte[] bytes = Files.readAllBytes(path);
                        Path target = copy.resolve(path.getFileName());
                        Files.write(target, bytes, StandardOpenOption.CREATE_NEW); originals.put(target, bytes);
                    }
                }
            }
            require(!originals.isEmpty(), "Historical sample is empty");
            var expected = heads(originals.values());
            var first = open(kind, copy, expected);
            compare(expected, first, !kind.equals("commerce"));
            var second = open(kind, copy, heads(readImages(copy)));
            require(first.equals(second), "Second reopen changed exposed evidence");
            var records = new JsonArray();
            for (var record : originals.entrySet()) {
                require(Arrays.equals(record.getValue(), Files.readAllBytes(record.getKey())), "Original copy changed");
                require(Arrays.equals(record.getValue(), Files.readAllBytes(source.resolve(record.getKey().getFileName()))), "Source changed");
                var image = new JsonObject(); image.addProperty("file", record.getKey().getFileName().toString());
                image.addProperty("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(record.getValue())));
                records.add(image);
            }
            long prepared = expected.entrySet().stream().filter(e -> !e.getKey().startsWith("cursor:")
                    && e.getValue().has("state") && e.getValue().get("state").getAsString().equals("PREPARED")).count();
            long extra = readImages(copy).size() - originals.size();
            require(extra == (kind.equals("commerce") ? 0 : prepared), "Unexpected recovery revisions");
            var sample = new JsonObject(); sample.addProperty("kind", kind);
            sample.addProperty("source", source.toString()); sample.addProperty("copy", copy.toString());
            sample.addProperty("originalFiles", originals.size()); sample.addProperty("recoveryRevisions", extra);
            sample.addProperty("headRecords", expected.size()); sample.addProperty("stableSecondReopen", true);
            sample.add("originalImages", records); sample.add("loaded", JSON.toJsonTree(first)); samples.add(sample);
            Files.writeString(output.resolve("result.json"), JSON.toJson(result) + "\n");
            System.out.println("[JOURNALRELOAD] PASS " + kind + " files=" + originals.size() + " heads=" + expected.size());
        }
        result.addProperty("complete", true);
        Files.writeString(output.resolve("result.json"), JSON.toJson(result) + "\n");
        System.out.println("[JOURNALRELOAD] COMPLETE " + output);
    }
    private static Collection<byte[]> readImages(Path path) throws Exception {
        var images = new ArrayList<byte[]>();
        try (var files = Files.list(path)) {
            for (var file : files.filter(p -> p.toString().endsWith(".json")).toList()) images.add(Files.readAllBytes(file));
        }
        return images;
    }
    private static Map<String, JsonObject> heads(Collection<byte[]> images) {
        var heads = new TreeMap<String, JsonObject>();
        for (byte[] image : images) {
            var document = JsonParser.parseString(new String(image, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            String kind = document.has("kind") ? document.get("kind").getAsString() : "delivery";
            var payload = document.getAsJsonObject(kind.equals("delivery") ? "entry" : "payload");
            String key = key(kind, payload);
            var previous = heads.get(key);
            if (previous == null || revision(payload) > revision(previous)) heads.put(key, payload);
        }
        return heads;
    }
    private static String key(String kind, JsonObject record) {
        String id = record.has("id") ? record.get("id").getAsString()
                : record.has("rewardId") ? record.get("rewardId").getAsString()
                : record.has("taskId") ? record.get("taskId").getAsString()
                : record.get("epoch").getAsString() + ":" + record.get("actorId").getAsString() + ":" + record.get("journalId").getAsString();
        return kind + ":" + id;
    }
    private static int revision(JsonObject record) { return record.has("revision") ? record.get("revision").getAsInt() : 0; }
    private static void add(Map<String, JsonObject> values, String kind, Collection<?> records) {
        for (var record : records) { var value = JSON.toJsonTree(record).getAsJsonObject(); values.put(key(kind, value), value); }
    }
    private static Map<String, JsonObject> open(String kind, Path path, Map<String, JsonObject> expected) throws Exception {
        var values = new TreeMap<String, JsonObject>();
        switch (kind) {
            case "delivery" -> { try (var journal = QuestDeliveryJournal.open(path)) { add(values, "delivery", journal.entries()); } }
            case "craft" -> { try (var journal = QuestEvidenceJournal.open(path)) {
                add(values, "generation", journal.generations()); add(values, "entry", journal.entries());
            } }
            case "reward" -> { try (var journal = QuestRewardJournal.open(path)) {
                add(values, "binding", journal.bindings()); add(values, "claim", journal.claims());
            } }
            case "commerce" -> { try (var journal = CommerceTaskJournal.open(path)) {
                add(values, "binding", journal.bindings()); add(values, "entry", journal.entries());
                for (var entry : expected.entrySet()) if (entry.getKey().startsWith("cursor:")) {
                    var cursor = entry.getValue();
                    var binding = journal.bindings().stream().filter(b -> b.epoch().toString().equals(cursor.get("epoch").getAsString())).findFirst().orElseThrow();
                    require(journal.cursor(binding, UUID.fromString(cursor.get("actorId").getAsString()),
                            UUID.fromString(cursor.get("journalId").getAsString()), cursor.get("highWater").getAsLong()) == cursor.get("sequence").getAsLong(), "Cursor moved");
                    values.put(entry.getKey(), cursor);
                }
            } }
            default -> throw new IllegalArgumentException("Unknown journal kind: " + kind);
        }
        return values;
    }
    private static void compare(Map<String, JsonObject> expected, Map<String, JsonObject> actual, boolean recovery) {
        require(expected.keySet().equals(actual.keySet()), "Evidence identities changed");
        for (var entry : expected.entrySet()) {
            var before = entry.getValue().deepCopy(); var after = actual.get(entry.getKey()).deepCopy();
            if (recovery && before.has("state") && before.get("state").getAsString().equals("PREPARED")) {
                require(after.get("state").getAsString().equals("REVIEW") && revision(after) == revision(before) + 1, "Missing outcome was not reviewed");
                if (before.has("outcome")) require(after.get("outcome").getAsString().equals("MISSING"), "Wrong missing outcome");
                if (before.has("uncertainty")) require(!after.get("uncertainty").getAsString().isBlank(), "Missing uncertainty");
                for (String field : List.of("state", "revision", "updatedAt", "outcome", "uncertainty")) { before.remove(field); after.remove(field); }
            }
            require(before.equals(after), "Evidence payload changed: " + entry.getKey());
        }
    }
    private static void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    private JournalReloadAudit() { }
}
