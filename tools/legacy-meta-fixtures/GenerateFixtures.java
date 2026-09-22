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
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Run only with the original Bukkit/Guava jars; emits synthetic maps through the actual Bukkit stream writer. */
public class GenerateFixtures {
    @SerializableAs("ItemMeta")
    public static class Meta implements ConfigurationSerializable {
        private final Map<String, Object> values;
        Meta(Map<String, Object> values) { this.values = values; }
        @Override public Map<String, Object> serialize() { return values; }
    }

    @SerializableAs("PotionEffect")
    public static class EffectFields implements ConfigurationSerializable {
        private final Map<String, Object> fields;
        EffectFields(Map<String, Object> fields) { this.fields = fields; }
        @Override public Map<String, Object> serialize() { return fields; }
    }

    @SerializableAs("Firework")
    public static class FireworkFields implements ConfigurationSerializable {
        private final Map<String, Object> fields;
        FireworkFields(Map<String, Object> fields) { this.fields = fields; }
        @Override public Map<String, Object> serialize() { return fields; }
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
        write(output, "leather", Map.of("meta-type", "LEATHER_ARMOR", "color", Color.fromRGB(0x123456),
                "ItemFlags", new ArrayList<>(List.of("HIDE_DYE"))));
        Map<String, Object> trim = new LinkedHashMap<>(); trim.put("material", "minecraft:gold"); trim.put("pattern", "minecraft:sentry");
        write(output, "trimmed", Map.of("meta-type", "ARMOR", "trim", trim, "ItemFlags", new ArrayList<>(List.of("HIDE_ARMOR_TRIM"))));
        write(output, "colored-trimmed", Map.of("meta-type", "COLORABLE_ARMOR", "color", Color.fromRGB(0x654321), "trim", trim));
        write(output, "writable", Map.of("meta-type", "BOOK", "pages", ImmutableList.of("{\"text\":\"literal JSON\"}", "Line one\nLine two &a<red>")));
        write(output, "written", Map.of("meta-type", "BOOK_SIGNED", "title", "Migration notes", "author", "Narrator", "generation", 2,
                "resolved", false, "pages", ImmutableList.of("{\"text\":\"First page\",\"color\":\"gold\",\"clickEvent\":{\"action\":\"change_page\",\"value\":\"2\"}}", "§bSecond\nline")));
        write(output, "recipes", Map.of("meta-type", "KNOWLEDGE_BOOK", "Recipes", new ArrayList<>(List.of("minecraft:crafting_table", "audit:unavailable_recipe"))));
        write(output, "potion", Map.of("meta-type", "POTION", "potion-type", "minecraft:long_swiftness", "custom-color", Color.fromRGB(0x336699),
                "custom-effects", ImmutableList.of(new PotionEffect(PotionEffectType.SPEED, 123, 2, true, false, true),
                        new PotionEffect(PotionEffectType.NIGHT_VISION, -1, 0, false, true, false)),
                "ItemFlags", new ArrayList<>(List.of("HIDE_POTION_EFFECTS"))));
        write(output, "potion-defaults", Map.of("meta-type", "POTION", "custom-effects", ImmutableList.of(new EffectFields(Map.of("effect", 1, "duration", 40, "amplifier", 0)))));
        write(output, "unknown-effect", Map.of("meta-type", "POTION", "custom-effects", ImmutableList.of(new EffectFields(Map.of("effect", 9001, "duration", 40, "amplifier", 0)))));
        write(output, "bad-effect-level", Map.of("meta-type", "POTION", "custom-effects", ImmutableList.of(new EffectFields(Map.of("effect", 1, "duration", 40, "amplifier", 300)))));
        write(output, "missing-trim", Map.of("meta-type", "ARMOR", "trim", new LinkedHashMap<>(Map.of("material", "missing:material", "pattern", "minecraft:sentry"))));
        write(output, "oversize-book", Map.of("meta-type", "BOOK", "pages", ImmutableList.of("x".repeat(1025))));
        write(output, "latent-book-fields", Map.of("meta-type", "BOOK", "pages", ImmutableList.of("notes"), "author", "pending author"));
        List<FireworkEffect> fireworks = new ArrayList<>();
        for (FireworkEffect.Type type : FireworkEffect.Type.values()) fireworks.add(FireworkEffect.builder().with(type)
                .withColor(Color.fromRGB(0x123456), Color.fromRGB(0xfedcba)).withFade(Color.fromRGB(0x010203))
                .trail(type.ordinal() % 2 == 0).flicker(type.ordinal() % 2 != 0).build());
        write(output, "fireworks", Map.of("meta-type", "FIREWORK", "power", 2, "firework-effects", ImmutableList.copyOf(fireworks),
                "ItemFlags", new ArrayList<>(List.of("HIDE_POTION_EFFECTS"))));
        FireworkEffect star = FireworkEffect.builder().with(FireworkEffect.Type.STAR).withColor(Color.fromRGB(0xabcdef))
                .withFade(Color.fromRGB(0x010203)).trail(true).flicker(true).build();
        write(output, "firework-star", Map.of("meta-type", "FIREWORK_EFFECT", "firework-effect", star));
        write(output, "empty-firework", Map.of("meta-type", "FIREWORK"));
        write(output, "empty-firework-star", Map.of("meta-type", "FIREWORK_EFFECT"));
        write(output, "firework-bad-power", Map.of("meta-type", "FIREWORK", "power", 128));
        write(output, "firework-too-many", Map.of("meta-type", "FIREWORK", "firework-effects", ImmutableList.copyOf(java.util.Collections.nCopies(257, star))));
        write(output, "firework-empty-colors", Map.of("meta-type", "FIREWORK_EFFECT", "firework-effect", new FireworkFields(
                Map.of("type", "BALL", "colors", ImmutableList.of(), "fade-colors", ImmutableList.of(), "trail", false, "flicker", false))));
        write(output, "firework-unknown-shape", Map.of("meta-type", "FIREWORK_EFFECT", "firework-effect", new FireworkFields(
                Map.of("type", "UNKNOWN", "colors", ImmutableList.of(Color.RED), "fade-colors", ImmutableList.of(), "trail", false, "flicker", false))));
        write(output, "firework-bad-field", Map.of("meta-type", "FIREWORK_EFFECT", "firework-effect", new FireworkFields(
                Map.of("type", "BALL", "colors", ImmutableList.of(Color.RED), "fade-colors", ImmutableList.of(), "trail", false, "flicker", false, "provider-field", "retain"))));
    }

    private static void write(Path dir, String name, Map<String, Object> map) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream stream = new BukkitObjectOutputStream(bytes)) { stream.writeObject(new Meta(map)); }
        Files.writeString(dir.resolve(name + ".base64"), Base64.getEncoder().encodeToString(bytes.toByteArray()));
        System.out.println(name + ": " + bytes.size() + " bytes");
    }
}
