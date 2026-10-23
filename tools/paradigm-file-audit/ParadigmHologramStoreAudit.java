import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.avalanche7.paradigm.modules.holograms.HologramDefinition;
import eu.avalanche7.paradigm.modules.holograms.HologramStore;

/** Exercises the real provider's durable load/reload and corruption safety. */
public final class ParadigmHologramStoreAudit {
    private static int passed;
    private static final Gson JSON = new Gson();
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        passed++;
        System.out.println("[HOLOGRAMSTOREAUDIT] PASS " + name);
    }
    private static HologramStore open(Path path) { return new HologramStore(() -> path, null); }
    private static byte[] bytes(Path path) throws Exception { return Files.readAllBytes(path); }
    private static Path file(Path root, String name) throws Exception {
        Path directory = root.resolve(name); Files.createDirectory(directory);
        return directory.resolve("holograms.json");
    }
    private static void corrupt(Path root, String name, byte[] value) throws Exception {
        Path path = file(root, name); Files.write(path, value);
        var store = open(path);
        check(!store.globallyEnabled() && store.snapshot().holograms.isEmpty(), name + "_disables_without_partial_definitions");
        check(Arrays.equals(value, bytes(path)), name + "_retains_exact_recoverable_input");
        check(!Files.exists(path.resolveSibling("holograms.json.tmp")), name + "_does_not_attempt_replacement");
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath(); Files.createDirectory(root);
        Path path = file(root, "valid");
        var first = open(path);
        check(first.globallyEnabled() && first.snapshot().holograms.isEmpty(), "fresh_defaults_enabled");
        var definition = new HologramDefinition();
        definition.dimension = "minecraft:overworld";
        definition.x = 3.125; definition.y = -59.5; definition.z = 3.875;
        definition.viewDistance = 37.5; definition.refreshIntervalSeconds = 3; definition.lineSpacing = 0.42;
        definition.lines.addAll(List.of("Literal $5 \\ path", "Unicode 雪 ♦", "{citizens_nearest_npc_id}"));
        first.put("retained", definition);
        first.updateSettings(true, 51, 7);
        byte[] original = bytes(path);
        var originalJson = JsonParser.parseString(new String(original, StandardCharsets.UTF_8));
        var existing = open(path);
        check(existing.globallyEnabled(), "existing_valid_file_stays_enabled");
        check(JSON.toJsonTree(existing.definition("retained")).equals(JSON.toJsonTree(first.definition("retained"))), "existing_preserves_all_definition_fields");
        check(Arrays.equals(original, bytes(path)), "existing_valid_file_is_byte_stable");
        check(existing.snapshot().defaultViewDistance == 51 && existing.snapshot().defaultRefreshIntervalSeconds == 7,
                "existing_preserves_global_settings");
        first.reload();
        check(first.globallyEnabled() && JSON.toJsonTree(first.snapshot()).equals(JSON.toJsonTree(existing.snapshot())), "same_store_reload_preserves_complete_state");
        check(originalJson.equals(JsonParser.parseString(Files.readString(path))), "reload_preserves_complete_saved_json");
        check(!Files.exists(path.resolveSibling("holograms.json.tmp")), "successful_reload_leaves_no_failed_temp_file");
        var copy = first.snapshot(); copy.holograms.get("retained").lines.clear();
        var copiedDefinition = first.definition("retained"); copiedDefinition.lines.clear();
        check(first.definition("retained").lines.size() == 3, "snapshot_and_definition_are_defensive");
        first.mutateLines("retained", lines -> lines.add("appended"));
        first.rename("retained", "renamed");
        var changed = open(path);
        check(changed.definition("retained") == null && changed.definition("renamed").lines.size() == 4, "edits_and_rename_survive_new_store");
        byte[] beforeDuplicate = bytes(path);
        try { changed.create("renamed", "minecraft:overworld", 0, 0, 0); throw new AssertionError("Duplicate accepted"); }
        catch (IllegalArgumentException expected) { check(Arrays.equals(beforeDuplicate, bytes(path)), "duplicate_id_rejection_keeps_saved_definition"); }
        changed.updateSettings(false, 51, 7);
        var disabled = open(path);
        check(!disabled.globallyEnabled() && disabled.definition("renamed").lines.size() == 4, "intentional_disabled_configuration_retains_definitions");
        disabled.updateSettings(true, 51, 7); disabled.reload();
        check(disabled.globallyEnabled(), "reenabled_configuration_survives_reload");
        byte[] valid = bytes(path);
        Files.writeString(path, "{broken"); disabled.reload();
        check(!disabled.globallyEnabled() && disabled.snapshot().holograms.isEmpty(), "corrupt_reload_does_not_reuse_stale_active_state");
        check(Files.readString(path).equals("{broken"), "corrupt_reload_retains_source");
        Files.write(path, valid); disabled.reload();
        check(disabled.globallyEnabled() && disabled.definition("renamed").lines.size() == 4, "restored_valid_input_recovers_same_store");
        corrupt(root, "syntax", "{invalid".getBytes(StandardCharsets.UTF_8));
        corrupt(root, "utf8", new byte[] {(byte) 0xc3, 0x28});
        corrupt(root, "dimension", "{\"holograms\":{\"bad\":{\"dimension\":\"invalid\"}}}".getBytes(StandardCharsets.UTF_8));
        corrupt(root, "duplicate", "{\"holograms\":{\"Mixed\":{},\"mixed\":{}}}".getBytes(StandardCharsets.UTF_8));
        var excessive = new JsonObject(); var definitions = new JsonObject(); var oversized = new JsonObject();
        oversized.add("lines", JSON.toJsonTree(java.util.Collections.nCopies(HologramStore.MAX_LINES + 1, "line")));
        definitions.add("oversized", oversized); excessive.add("holograms", definitions);
        corrupt(root, "line-limit", JSON.toJson(excessive).getBytes(StandardCharsets.UTF_8));
        Path nullPath = file(root, "null"); Files.writeString(nullPath, "null");
        check(open(nullPath).globallyEnabled() && JsonParser.parseString(Files.readString(nullPath)).isJsonObject(), "null_configuration_uses_and_persists_normal_defaults");
        Path normalized = file(root, "normalization");
        Files.writeString(normalized, "{\"defaultViewDistance\":999999,\"defaultRefreshIntervalSeconds\":0,\"holograms\":null}");
        var normalizedStore = open(normalized);
        check(normalizedStore.globallyEnabled() && normalizedStore.snapshot().defaultViewDistance == 512
                && normalizedStore.snapshot().defaultRefreshIntervalSeconds == 1 && normalizedStore.snapshot().holograms.isEmpty(), "existing_input_keeps_provider_normalization_contract");
        byte[] beforeLock = bytes(path);
        try (var blocker = Files.newBufferedReader(path)) {
            check(blocker.read() == '{', "replacement_failure_uses_real_open_file");
            var blocked = open(path);
            check(!blocked.globallyEnabled() && blocked.snapshot().holograms.isEmpty(), "genuine_replacement_failure_still_disables_safely");
            check(Arrays.equals(beforeLock, bytes(path)), "genuine_replacement_failure_retains_original_definitions");
        }
        var recovered = open(path);
        check(recovered.globallyEnabled() && recovered.definition("renamed").lines.size() == 4, "release_of_external_file_lock_allows_clean_recovery");
        check(!Files.exists(path.resolveSibling("holograms.json.tmp")), "lock_recovery_finishes_owned_replacement");
        var result = new JsonObject(); result.addProperty("checks", passed); result.addProperty("caseDirectory", root.toString());
        Files.writeString(root.resolve("result.json"), JSON.toJson(result));
        System.out.println("[HOLOGRAMSTOREAUDIT] COMPLETE " + passed + " checks");
    }
}
