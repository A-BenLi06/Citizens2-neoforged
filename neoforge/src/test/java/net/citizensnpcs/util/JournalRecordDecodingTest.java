package net.citizensnpcs.util;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import com.google.gson.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

/** Journal-generated histories must not acquire new meanings through JSON coercion. */
class JournalRecordDecodingTest {
    @TempDir Path directory;
    enum Kind {
        DELIVERY("maximum", "completed"), CRAFT("units", "accepted"), REWARD("amount", "repeatable"), COMMERCE("minorUnits", "requireDelivery");
        final String quantity, flag;
        Kind(String quantity, String flag) { this.quantity = quantity; this.flag = flag; }
        AutoCloseable open(Path path) throws IOException {
            return switch (this) {
                case DELIVERY -> QuestDeliveryJournal.open(path);
                case CRAFT -> QuestEvidenceJournal.open(path);
                case REWARD -> QuestRewardJournal.open(path);
                case COMMERCE -> CommerceTaskJournal.open(path);
            };
        }
        long quantity(AutoCloseable journal) {
            return switch (this) {
                case DELIVERY -> ((QuestDeliveryJournal) journal).entries().getFirst().after().maximum();
                case CRAFT -> ((QuestEvidenceJournal) journal).entries().getFirst().admission().units();
                case REWARD -> ((QuestRewardJournal) journal).bindings().getFirst().amount();
                case COMMERCE -> ((CommerceTaskJournal) journal).entries().getFirst().receipt().minorUnits();
            };
        }
        void seed(Path path) throws Exception {
            UUID actor = UUID.randomUUID(), team = UUID.randomUUID();
            switch (this) {
                case DELIVERY -> {
                    try (var journal = QuestDeliveryJournal.open(path)) {
                        var before = new QuestDeliveryJournal.Target(team, "a1", "a2", 0, 3, 0, false, "a".repeat(64));
                        var after = new QuestDeliveryJournal.Target(team, "a1", "a2", 0, 3, 3, true, "a".repeat(64));
                        var entry = journal.prepare(actor, before, "[{\"count\":3}]"); journal.confirm(entry.id(), after, "[]");
                    }
                }
                case CRAFT -> {
                    try (var journal = QuestEvidenceJournal.open(path)) {
                        var generation = journal.generation(new QuestEvidenceJournal.Scope(UUID.randomUUID(), team, "a2"), 0);
                        var admission = new QuestEvidenceJournal.Admission(UUID.randomUUID(), generation.id(), "a1", actor,
                                "fixture:recipe", "a".repeat(64), 3, "{\"component\":\"retained\"}");
                        var entry = journal.admit(admission); journal.outcome(entry.id(), QuestEvidenceJournal.Outcome.INTERRUPTED);
                        journal.resolve(entry.id(), actor, "Inspected original native outcome", true); journal.applied(entry.id());
                    }
                }
                case REWARD -> {
                    try (var journal = QuestRewardJournal.open(path)) {
                        var binding = journal.bind(new QuestRewardJournal.Binding(UUID.randomUUID(), "a1", "a2", false,
                                false, "a".repeat(64), "currency", 2, 375, actor, 1));
                        var entry = journal.prepare(new QuestRewardJournal.Intent(binding, team, actor, actor, 0));
                        journal.accepted(entry.id(), 0); journal.paid(entry.id(), UUID.randomUUID());
                    }
                }
                case COMMERCE -> {
                    try (var journal = CommerceTaskJournal.open(path)) {
                        var binding = journal.bind(new CommerceTaskJournal.Binding(UUID.randomUUID(), "a1", "a2", "a".repeat(64),
                                100, false, UUID.randomUUID(), 0, CommerceTaskJournal.Shop.SYSTEM, "shop", "offer",
                                CommerceTaskJournal.Action.BUY_FROM_SHOP, CommerceTaskJournal.Measure.MINOR_UNITS, "currency", true, actor, 1));
                        var request = new CommerceTaskJournal.Request(CommerceTaskJournal.Shop.SYSTEM, "shop", "offer",
                                CommerceTaskJournal.Action.BUY_FROM_SHOP, actor, 1, "request", "fixture", null);
                        var entry = journal.prepare(binding, request, new CommerceTaskJournal.Attribution(team, 0, 0, 1));
                        journal.receipt(binding, request, new CommerceTaskJournal.Receipt(UUID.randomUUID(), UUID.randomUUID(),
                                UUID.randomUUID(), 1, "currency", 30, 1, 1, "components", "PAPER", 1), true);
                        journal.applied(entry.id());
                    }
                }
            }
        }
    }
    enum Damage { FRACTIONAL_SCHEMA, STRING_SCHEMA, ARRAY_SCHEMA, OVERFLOW_SCHEMA, FRACTIONAL_QUANTITY,
        OVERFLOW_QUANTITY, STRING_BOOLEAN, DUPLICATE_SCHEMA, UNQUOTED_SCHEMA }
    static Stream<Object[]> damagedHistories() {
        return Arrays.stream(Kind.values()).flatMap(kind -> Arrays.stream(Damage.values()).map(damage -> new Object[] {kind, damage}));
    }
    static Stream<Kind> validKinds() { return Arrays.stream(Kind.values()); }
    private static int change(JsonElement value, String key, java.util.function.UnaryOperator<JsonElement> replace) {
        int changes = 0;
        if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            for (String name : List.copyOf(object.keySet())) {
                if (name.equals(key)) { object.add(name, replace.apply(object.get(name))); changes++; }
                else changes += change(object.get(name), key, replace);
            }
        } else if (value.isJsonArray()) for (var child : value.getAsJsonArray()) changes += change(child, key, replace);
        return changes;
    }
    @ParameterizedTest(name = "{0}: {1}") @MethodSource("damagedHistories")
    void damagedTypedHistoryRejectsWithoutRewritingAndExactRestoreRecovers(Kind kind, Damage damage) throws Exception {
        kind.seed(directory);
        var originals = new LinkedHashMap<Path, String>();
        try (var paths = Files.list(directory)) { for (Path path : paths.filter(p -> p.toString().endsWith(".json")).sorted().toList()) originals.put(path, Files.readString(path)); }
        try (var valid = kind.open(directory)) { assertNotNull(valid); }
        var damaged = new LinkedHashMap<Path, String>(); int changed = 0, alteredFiles = 0;
        for (var file : originals.entrySet()) {
            var document = JsonParser.parseString(file.getValue()).getAsJsonObject();
            String altered;
            if (damage == Damage.DUPLICATE_SCHEMA) altered = file.getValue().replace("\"schema\":1", "\"schema\":2,\"schema\":1");
            else if (damage == Damage.UNQUOTED_SCHEMA) altered = file.getValue().replace("\"schema\"", "schema");
            else {
                switch (damage) {
                    case FRACTIONAL_SCHEMA -> document.addProperty("schema", new BigDecimal("1.5"));
                    case STRING_SCHEMA -> document.addProperty("schema", "1");
                    case ARRAY_SCHEMA -> { var values = new JsonArray(); values.add(1); document.add("schema", values); }
                    case OVERFLOW_SCHEMA -> document.addProperty("schema", new BigDecimal("4294967297"));
                    case FRACTIONAL_QUANTITY -> changed += change(document, kind.quantity, n -> new JsonPrimitive(n.getAsBigDecimal().add(new BigDecimal("0.5"))));
                    case OVERFLOW_QUANTITY -> changed += change(document, kind.quantity, n -> new JsonPrimitive(n.getAsBigDecimal().add(new BigDecimal("18446744073709551616"))));
                    case STRING_BOOLEAN -> changed += change(document, kind.flag, n -> new JsonPrimitive(n.getAsString()));
                    default -> throw new AssertionError(damage);
                }
                altered = document.toString() + "\n";
            }
            if (!file.getValue().equals(altered)) alteredFiles++;
            damaged.put(file.getKey(), altered); Files.writeString(file.getKey(), altered);
        }
        assertTrue(alteredFiles > 0);
        if (damage == Damage.FRACTIONAL_QUANTITY || damage == Damage.OVERFLOW_QUANTITY || damage == Damage.STRING_BOOLEAN) assertTrue(changed > 0);
        try {
            boolean rejected = false; Long quantity = null;
            try (var loaded = kind.open(directory)) { quantity = kind.quantity(loaded); }
            catch (IOException expected) { rejected = true; }
            String evidence = System.getProperty("citizens.journal.decode.evidence");
            if (evidence != null) {
                Path output = Path.of(evidence).resolve(kind.name() + "-" + damage.name());
                Files.createDirectories(output.resolve("original")); Files.createDirectories(output.resolve("damaged"));
                for (var file : originals.entrySet()) Files.writeString(output.resolve("original").resolve(file.getKey().getFileName()), file.getValue());
                for (var file : damaged.entrySet()) Files.writeString(output.resolve("damaged").resolve(file.getKey().getFileName()), Files.readString(file.getKey()));
                var observation = new JsonObject(); observation.addProperty("kind", kind.name()); observation.addProperty("damage", damage.name());
                observation.addProperty("rejected", rejected); observation.addProperty("loadedQuantity", quantity);
                Files.writeString(output.resolve("observation.json"), observation.toString() + "\n");
            }
            assertTrue(rejected, "Damaged journal must reject before publishing retained native/financial evidence");
            for (var file : damaged.entrySet()) assertEquals(file.getValue(), Files.readString(file.getKey()));
        } finally { for (var file : originals.entrySet()) Files.writeString(file.getKey(), file.getValue()); }
        try (var restored = kind.open(directory)) { assertNotNull(restored); }
    }
    private static void integralNotation(JsonElement value) {
        if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            for (String key : List.copyOf(object.keySet())) {
                var child = object.get(key);
                if (child.isJsonPrimitive() && child.getAsJsonPrimitive().isNumber())
                    object.addProperty(key, child.getAsBigDecimal().setScale(1));
                else integralNotation(child);
            }
        } else if (value.isJsonArray()) for (var child : value.getAsJsonArray()) integralNotation(child);
    }
    @ParameterizedTest(name = "{0}: valid numeric notation") @MethodSource("validKinds")
    void validIntegralNumbersWhitespaceAndNullFieldsRetainTheirMeaning(Kind kind) throws Exception {
        kind.seed(directory);
        long original;
        try (var journal = kind.open(directory)) { original = kind.quantity(journal); }
        var retained = new LinkedHashMap<Path, String>();
        try (var paths = Files.list(directory)) {
            for (var path : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                var document = JsonParser.parseString(Files.readString(path)); integralNotation(document);
                String text = new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(document) + "\n";
                retained.put(path, text); Files.writeString(path, text);
            }
        }
        try (var journal = kind.open(directory)) { assertEquals(original, kind.quantity(journal)); }
        for (var file : retained.entrySet()) assertEquals(file.getValue(), Files.readString(file.getKey()));
    }
    @Test void unknownOptionalOutcomeCannotDisappearIntoRestartRecovery() throws Exception {
        String id;
        try (var journal = QuestEvidenceJournal.open(directory)) {
            var generation = journal.generation(new QuestEvidenceJournal.Scope(UUID.randomUUID(), UUID.randomUUID(), "a2"), 0);
            id = journal.admit(new QuestEvidenceJournal.Admission(UUID.randomUUID(), generation.id(), "a1", UUID.randomUUID(),
                    "fixture:recipe", "a".repeat(64), 3, "{}")).id();
        }
        Path path = directory.resolve("entry-" + id + "-0.json"); String original = Files.readString(path);
        var document = JsonParser.parseString(original).getAsJsonObject();
        document.getAsJsonObject("payload").addProperty("outcome", "UNSUPPORTED_NATIVE_OUTCOME");
        String unsupported = document.toString(); Files.writeString(path, unsupported);
        assertThrows(IOException.class, () -> QuestEvidenceJournal.open(directory));
        assertEquals(unsupported, Files.readString(path));
        assertFalse(Files.exists(directory.resolve("entry-" + id + "-1.json")));
        Files.writeString(path, original);
        try (var journal = QuestEvidenceJournal.open(directory)) {
            assertEquals(QuestEvidenceJournal.State.REVIEW, journal.entries().getFirst().state());
            assertEquals(QuestEvidenceJournal.Outcome.MISSING, journal.entries().getFirst().outcome());
        }
    }
}
