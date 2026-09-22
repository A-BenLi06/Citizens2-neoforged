import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.SerializableAs;
import org.bukkit.util.io.BukkitObjectOutputStream;

/** Run only with the original Bukkit/Guava jars; emits synthetic maps through the actual Bukkit stream writer. */
public class GenerateFixtures {
    @SerializableAs("ItemMeta")
    public static class Meta implements ConfigurationSerializable {
        private final Map<String, Object> values;
        Meta(Map<String, Object> values) { this.values = values; }
        @Override public Map<String, Object> serialize() { return values; }
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]); Files.createDirectories(output);
        Map<String, Object> rich = new LinkedHashMap<>();
        rich.put("meta-type", "UNSPECIFIC");
        rich.put("display-name", "{\"text\":\"Archive &a<red> 中 \\u0000 😀\",\"color\":\"gold\",\"italic\":false}");
        rich.put("lore", ImmutableList.of("{\"text\":\"first\",\"bold\":true}", "{\"text\":\"second\"}"));
        rich.put("custom-model-data", 217); rich.put("Damage", 11); rich.put("repair-cost", 4);
        rich.put("Unbreakable", true);
        rich.put("enchants", ImmutableMap.of("DAMAGE_ALL", 5, "MENDING", 1));
        rich.put("ItemFlags", new ArrayList<>(List.of("HIDE_ENCHANTS", "HIDE_UNBREAKABLE", "HIDE_ATTRIBUTES")));
        write(output, "rich", rich);
        write(output, "plain", Map.of("meta-type", "UNSPECIFIC", "display-name", "§aLegacy &b<red>",
                "lore", ImmutableList.of("§lBold", "literal &a<red>")));
        write(output, "book", Map.of("meta-type", "ENCHANTED", "stored-enchants", ImmutableMap.of("DAMAGE_ALL", 3)));
        write(output, "unknown", Map.of("meta-type", "UNSPECIFIC", "custom-provider-field", "do not discard"));
        write(output, "empty", Map.of("meta-type", "UNSPECIFIC"));
        write(output, "hex", Map.of("meta-type", "UNSPECIFIC", "display-name", "§x§1§2§a§b§0§fHex §rReset"));
        write(output, "links", Map.of("meta-type", "UNSPECIFIC", "display-name", "§aVisit example.com!"));
        write(output, "invalid", Map.of("meta-type", "UNSPECIFIC", "Damage", -1));
        Map<String, Object> cycle = new LinkedHashMap<>(); cycle.put("self", cycle);
        write(output, "cycle", Map.of("meta-type", "UNSPECIFIC", "cycle", cycle));
        write(output, "long", Map.of("meta-type", "UNSPECIFIC", "display-name", "中\u0000😀".repeat(15000)));
        Map<String, Object> linked = new LinkedHashMap<>(); linked.put("a", 1); linked.put("b", List.of());
        // Avoid JDK-version-specific immutable collection proxies in this legacy collection fixture.
        linked.put("b", new ArrayList<>(List.of("same", "same")));
        write(output, "collections", Map.of("meta-type", "UNSPECIFIC", "payload", linked));
    }

    private static void write(Path dir, String name, Map<String, Object> map) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream stream = new BukkitObjectOutputStream(bytes)) { stream.writeObject(new Meta(map)); }
        Files.writeString(dir.resolve(name + ".base64"), Base64.getEncoder().encodeToString(bytes.toByteArray()));
        System.out.println(name + ": " + bytes.size() + " bytes");
    }
}
