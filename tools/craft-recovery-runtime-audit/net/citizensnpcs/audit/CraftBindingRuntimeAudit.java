package net.citizensnpcs.audit;

import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import com.google.gson.*;
import net.citizensnpcs.util.*;
import net.minecraft.server.MinecraftServer;

/** Actual native binding/history copies; damaged companion files must not publish recovery. */
final class CraftBindingRuntimeAudit {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static int checks;
    static int run(MinecraftServer server) throws Exception {
        Path artifacts = server.getServerDirectory().toAbsolutePath().normalize().getParent().toRealPath();
        Path source = Path.of(System.getenv("CITIZENS_CRAFT_BINDING_SOURCE")).toRealPath();
        require(source.startsWith(artifacts), "Source escaped artifacts");
        Path output = Files.createDirectory(server.getServerDirectory().toAbsolutePath().normalize().resolve("binding-decode-" + UUID.randomUUID()));
        Map<String, String> sourceImages;
        var cases = new JsonArray(); var result = new JsonObject(); result.add("cases", cases);
        try (var channel = FileChannel.open(source.resolve("journal/.journal.lock"), StandardOpenOption.READ, StandardOpenOption.WRITE);
                var guard = channel.tryLock()) {
            require(guard != null, "Historical source journal is in use");
            sourceImages = images(source);
            copy(source, output.resolve("original"));
        }
        var console = server.createCommandSourceStack();
        Path binding;
        try (var paths = Files.list(output.resolve("original/bindings"))) { binding = paths.filter(p -> p.toString().endsWith(".json")).sorted().findFirst().orElseThrow(); }
        Path bindingRelative = output.resolve("original").relativize(binding);
        var originalBinding = JsonParser.parseString(Files.readString(binding)).getAsJsonObject();
        String taskId = originalBinding.get("taskId").getAsString();
        for (String damage : List.of("FRACTIONAL_SCHEMA", "STRING_SCHEMA", "OVERFLOW_SCHEMA", "FRACTIONAL_MAXIMUM", "OVERFLOW_MAXIMUM",
                "STRING_BOOLEAN", "DUPLICATE_SCHEMA", "UNQUOTED_SCHEMA", "FRACTIONAL_TIMESTAMP", "INVALID_SCHEMA", "INCOMPLETE_BINDING")) {
            Path folder = Files.createDirectory(output.resolve(damage)), live = folder.resolve("live"); copy(output.resolve("original"), live);
            Path alteredBinding = live.resolve(bindingRelative); String originalText = Files.readString(alteredBinding);
            boolean boundary = damage.equals("INVALID_SCHEMA") || damage.equals("INCOMPLETE_BINDING");
            String prepared = null;
            if (boundary) {
                try (var paths = Files.list(live.resolve("journal"))) {
                    for (var path : paths.filter(p -> p.getFileName().toString().startsWith("entry-")).toList()) {
                        var payload = JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonObject("payload");
                        if (payload.getAsJsonObject("admission").get("taskId").getAsString().equals(taskId)) {
                            prepared = payload.get("id").getAsString();
                            if (payload.get("revision").getAsInt() != 0) Files.delete(path);
                        }
                    }
                }
                require(prepared != null, "No original native admission");
            }
            var damaged = originalBinding.deepCopy(); String text;
            switch (damage) {
                case "FRACTIONAL_SCHEMA" -> damaged.addProperty("schema", new BigDecimal("1.5"));
                case "STRING_SCHEMA" -> damaged.addProperty("schema", "1");
                case "OVERFLOW_SCHEMA" -> damaged.addProperty("schema", new BigDecimal("4294967297"));
                case "FRACTIONAL_MAXIMUM" -> damaged.addProperty("maximum", originalBinding.get("maximum").getAsBigDecimal().add(new BigDecimal("0.5")));
                case "OVERFLOW_MAXIMUM" -> damaged.addProperty("maximum", originalBinding.get("maximum").getAsBigDecimal().add(new BigDecimal("18446744073709551616")));
                case "STRING_BOOLEAN" -> damaged.addProperty("repeatable", "false");
                case "FRACTIONAL_TIMESTAMP" -> damaged.addProperty("createdAt", originalBinding.get("createdAt").getAsBigDecimal().add(new BigDecimal("0.5")));
                case "INVALID_SCHEMA" -> damaged.addProperty("schema", 2);
                default -> { }
            }
            if (damage.equals("DUPLICATE_SCHEMA")) text = originalText.replace("\"schema\":1", "\"schema\":2,\"schema\":1");
            else if (damage.equals("UNQUOTED_SCHEMA")) text = originalText.replace("\"schema\"", "schema");
            else text = damaged.toString();
            Files.writeString(alteredBinding, text);
            Path incomplete = alteredBinding.resolveSibling(alteredBinding.getFileName() + ".tmp-audit");
            if (damage.equals("INCOMPLETE_BINDING")) Files.writeString(incomplete, "retained incomplete binding");
            copy(live, folder.resolve("damaged")); var before = images(live);
            boolean rejected = false; JsonElement loaded = JsonNull.INSTANCE;
            try (var service = CraftQuestEvidence.open(server, live)) { loaded = JSON.toJsonTree(service.bindings(console)); }
            catch (java.io.IOException expected) { rejected = true; }
            var after = images(live); copy(live, folder.resolve("after-open"));
            var observation = new JsonObject(); observation.addProperty("damage", damage); observation.addProperty("rejected", rejected);
            observation.addProperty("imagesUnchanged", before.equals(after)); observation.add("loaded", loaded);
            observation.add("beforeImages", JSON.toJsonTree(before)); observation.add("afterImages", JSON.toJsonTree(after));
            cases.add(observation);
            Files.writeString(alteredBinding, originalText); Files.deleteIfExists(incomplete);
            try (var service = CraftQuestEvidence.open(server, live)) {
                require(JSON.toJsonTree(service.bindings(console)).getAsJsonArray().size() == 3, "Exact original bindings did not restore");
                if (boundary) {
                    String id = prepared;
                    var entry = service.entries(console).stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
                    require(entry.state() == QuestEvidenceJournal.State.REVIEW && entry.outcome() == QuestEvidenceJournal.Outcome.MISSING, "Valid restore did not recover the original missing outcome");
                }
            }
            checks++; log("PASS exact_restore_reopens " + damage);
            Files.writeString(output.resolve("result.json"), JSON.toJson(result));
        }
        Path positive = output.resolve("positive"); copy(output.resolve("original"), positive);
        try (var paths = Files.list(positive.resolve("bindings"))) {
            for (var path : paths.toList()) {
                var document = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                for (String key : List.copyOf(document.keySet())) if (document.get(key).isJsonPrimitive() && document.getAsJsonPrimitive(key).isNumber())
                    document.addProperty(key, document.get(key).getAsBigDecimal().setScale(1));
                Files.writeString(path, JSON.toJson(document));
            }
        }
        var positiveImages = images(positive);
        try (var service = CraftQuestEvidence.open(server, positive)) { require(service.bindings(console).size() == 3 && service.entries(console).size() == 3, "Valid notation lost records"); }
        require(positiveImages.equals(images(positive)), "Valid notation was rewritten"); checks++; log("PASS valid_integral_notation_and_whitespace");
        require(sourceImages.equals(images(source)), "Original native source changed");
        result.addProperty("sourceUnchanged", true); result.addProperty("positiveUnchanged", true);
        result.addProperty("allRejectedWithoutWrites", java.util.stream.StreamSupport.stream(cases.spliterator(), false)
                .allMatch(c -> c.getAsJsonObject().get("rejected").getAsBoolean() && c.getAsJsonObject().get("imagesUnchanged").getAsBoolean()));
        Files.writeString(output.resolve("result.json"), JSON.toJson(result));
        log("RESULT " + output);
        for (var item : cases) {
            var itemObject = item.getAsJsonObject();
            require(itemObject.get("rejected").getAsBoolean() && itemObject.get("imagesUnchanged").getAsBoolean(), "Binding damage was accepted or published recovery: " + itemObject.get("damage"));
            checks++; log("PASS rejected_without_publication " + itemObject.get("damage").getAsString());
        }
        return checks;
    }
    private static Map<String, String> images(Path root) throws Exception {
        var result = new TreeMap<String, String>();
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().equals(".journal.lock")).sorted().toList())
                result.put(root.relativize(path).toString().replace('\\', '/'), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        }
        return result;
    }
    private static void copy(Path source, Path target) throws Exception {
        Files.createDirectories(target);
        try (var paths = Files.walk(source)) {
            for (var path : paths.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().equals(".journal.lock")).toList()) {
                require(path.toRealPath().startsWith(source.toRealPath()), "Record escaped source");
                var to = target.resolve(source.relativize(path)); Files.createDirectories(to.getParent()); Files.copy(path, to);
            }
        }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void log(String value) { org.slf4j.LoggerFactory.getLogger("citizens").info("[CRAFTBINDING] {}", value); }
    private CraftBindingRuntimeAudit() { }
}
