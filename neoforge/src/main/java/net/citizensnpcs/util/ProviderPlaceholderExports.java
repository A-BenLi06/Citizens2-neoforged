package net.citizensnpcs.util;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import net.citizensnpcs.api.util.PermissionUtil;
import net.minecraft.server.level.ServerPlayer;

/** Configured, read-only values from their optional native owners, never a text evaluator. */
public final class ProviderPlaceholderExports {
    private ProviderPlaceholderExports() { }
    public enum Kind { ECONOMY_BALANCE, FTB_TASK_PROGRESS, FTB_TASK_MAXIMUM, FTB_TASK_COMPLETED }
    public record Export(String key, Kind kind, String currencyId, String taskId, String permission) {
        public Export {
            if (key == null || !key.matches("[a-z0-9_]+") || kind == null
                    || List.of("selected_npc_name", "selected_npc_id", "selected_npc_uuid", "nearest_npc_id").contains(key))
                throw new IllegalArgumentException("Invalid or reserved placeholder export key");
            if (kind == Kind.ECONOMY_BALANCE) {
                if (currencyId == null || currencyId.isBlank() || taskId != null) throw new IllegalArgumentException("Balance exports require an explicit currency");
            } else {
                if (currencyId != null) throw new IllegalArgumentException("Task exports cannot define a currency");
                taskId = String.format(java.util.Locale.ROOT, "%016X", FtbQuestTasks.parseId(taskId));
            }
            if (permission != null && permission.isBlank()) throw new IllegalArgumentException("An optional permission must not be blank");
        }
        public String externalKey() { return "citizens_" + key; }
    }

    public static List<Export> load(Path path) throws IOException {
        if (!Files.exists(path)) return List.of();
        try {
            var root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (!root.keySet().equals(Set.of("schema", "exports")) || root.get("schema").getAsInt() != 1)
                throw new IllegalArgumentException("Unsupported placeholder export schema");
            Map<String, Export> exports = new LinkedHashMap<>();
            for (var element : root.getAsJsonArray("exports")) {
                var value = element.getAsJsonObject();
                if (!value.has("key") || !value.has("kind") || !Set.of("key", "kind", "currencyId", "taskId", "permission").containsAll(value.keySet()))
                    throw new IllegalArgumentException("Unknown or missing placeholder export fields");
                var export = new Gson().fromJson(value, Export.class);
                if (exports.putIfAbsent(export.externalKey(), export) != null) throw new IllegalArgumentException("Duplicate placeholder export key");
            }
            return List.copyOf(exports.values());
        } catch (RuntimeException invalid) { throw new IOException("Invalid placeholder export configuration: " + path, invalid); }
    }

    /** Missing/offline/denied/hidden values are omitted, never the console's or another player's balance. */
    public static Map<String, String> snapshot(ServerPlayer player, List<Export> exports) {
        if (player == null) return Map.of();
        var server = player.getServer();
        if (server == null || !server.isSameThread()) throw new IllegalStateException("Placeholder capture requires the server thread");
        if (player.hasDisconnected() || server.getPlayerList().getPlayer(player.getUUID()) != player) return Map.of();
        Map<String, String> values = new LinkedHashMap<>();
        for (Export export : exports) {
            try {
                if (export.permission() != null && !PermissionUtil.hasPermission(player, export.permission())) continue;
                String value;
                if (export.kind() == Kind.ECONOMY_BALANCE) value = balance(player, export.currencyId());
                else {
                    var task = FtbQuestTasks.describe(player, export.taskId());
                    if (task.isEmpty()) continue;
                    value = switch (export.kind()) {
                        case FTB_TASK_PROGRESS -> Long.toString(task.get().state().progress());
                        case FTB_TASK_MAXIMUM -> Long.toString(task.get().state().maximum());
                        case FTB_TASK_COMPLETED -> Boolean.toString(task.get().state().completed());
                        default -> throw new IllegalStateException("Unexpected task export");
                    };
                }
                if (value != null) values.put(export.externalKey(), value);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                // Optional providers may retire independently. An unavailable value is never fabricated as zero.
            }
        }
        return Map.copyOf(values);
    }

    private static String balance(ServerPlayer player, String currencyId) throws ReflectiveOperationException {
        Class<?> accessor = Class.forName("net.yuuniverse.economy.api.YuuniverseEconomyApi");
        Object service = ((Optional<?>) accessor.getMethod("current").invoke(null)).orElse(null);
        if (service == null) return null;
        Class<?> api = Class.forName("net.yuuniverse.economy.api.EconomyApi");
        if (((Optional<?>) api.getMethod("playerAccount", java.util.UUID.class).invoke(service, player.getUUID())).isEmpty()) return null;
        Object currency = ((Optional<?>) api.getMethod("resolveCurrency", String.class).invoke(service, currencyId)).orElse(null);
        if (currency == null || !currencyId.equals(currency.getClass().getMethod("id").invoke(currency))) return null;
        int scale = ((Number) currency.getClass().getMethod("scale").invoke(currency)).intValue();
        long minor = ((Number) api.getMethod("balance", java.util.UUID.class, String.class).invoke(service, player.getUUID(), currencyId)).longValue();
        return BigDecimal.valueOf(minor, scale).toPlainString();
    }
}
