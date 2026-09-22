package net.citizensnpcs.api.util;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.WrittenBookItem;

/** The older Citizens DataKey schema, distinct from Bukkit's serialized ItemMeta map. */
final class LegacyStructuredItems {
    private static final Set<String> FIELDS = Set.of("custommodel", "flags", "lore", "displayname", "repaircost",
            "unbreakable", "armor", "book", "potion", "enchantmentstorage");
    private static final Map<String, String> POTIONS = LegacyBukkitMeta.aliases("legacy-potion-data.properties");
    private static final Map<String, String> EFFECT_NAMES = LegacyBukkitMeta.aliases("legacy-potion-effect-names.properties");

    private LegacyStructuredItems() { }

    static ItemStack read(DataKey root, ItemStack original, HolderLookup.Provider registries) {
        Map<?, ?> source = root.keyExists("meta") ? map(root.getRaw("meta"), FIELDS) : Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("==", "ItemMeta"); meta.put("meta-type", "UNSPECIFIC");
        if (root.keyExists("enchantments")) meta.put("enchants", enchantments(root.getRaw("enchantments")));
        for (String field : List.of("custommodel", "repaircost")) if (source.containsKey(field))
            meta.put(field.equals("custommodel") ? "custom-model-data" : "repair-cost", integer(source.get(field)));
        if (source.containsKey("unbreakable")) meta.put("Unbreakable", bool(source, "unbreakable", false));
        if (source.containsKey("displayname")) meta.put("display-name", plain(source.get("displayname"), registries));
        if (source.containsKey("lore")) {
            List<String> lore = new ArrayList<>();
            for (Object line : indexed(source.get("lore"))) lore.add(plain(line, registries));
            meta.put("lore", lore);
        }
        if (source.containsKey("flags")) meta.put("ItemFlags", indexed(source.get("flags")));
        // Original subtype casts are mutually exclusive. Reject incompatible combinations instead of dropping one.
        int subtypes = 0;
        for (String field : List.of("armor", "book", "potion", "enchantmentstorage")) if (source.containsKey(field)) subtypes++;
        require(subtypes <= 1, "Incompatible structured metadata types");
        if (source.containsKey("enchantmentstorage")) {
            meta.put("meta-type", "ENCHANTED"); meta.put("stored-enchants", enchantments(source.get("enchantmentstorage")));
        }
        if (source.containsKey("armor")) {
            Map<?, ?> armor = map(source.get("armor"), Set.of("color"));
            int rgb = integer(armor.get("color")); require(rgb >= 0 && rgb <= 0xffffff, "Invalid armor RGB");
            meta.put("meta-type", "LEATHER_ARMOR");
            meta.put("color", Map.of("==", "Color", "RED", rgb >> 16, "GREEN", rgb >> 8 & 255, "BLUE", rgb & 255));
        }
        if (source.containsKey("book")) {
            Map<?, ?> book = map(source.get("book"), Set.of("pages", "title", "author"));
            boolean signed = original.getItem() instanceof WrittenBookItem;
            meta.put("meta-type", signed ? "BOOK_SIGNED" : "BOOK");
            List<String> pages = new ArrayList<>();
            for (Object page : indexed(book.containsKey("pages") ? book.get("pages") : Map.of()))
                pages.add(signed ? plain(page, registries) : string(page));
            meta.put("pages", pages);
            for (String field : List.of("title", "author")) {
                String value = book.containsKey(field) ? string(book.get(field)) : "";
                require(signed || value.isEmpty(), "Latent writable-book " + field);
                if (signed) meta.put(field, value);
            }
        }
        if (source.containsKey("potion")) potion(map(source.get("potion"), Set.of("data", "effects")), meta);
        return LegacyBukkitMeta.read(meta, original, registries);
    }

    private static void potion(Map<?, ?> potion, Map<String, Object> meta) {
        meta.put("meta-type", "POTION");
        if (potion.containsKey("data")) {
            Map<?, ?> data = map(potion.get("data"), Set.of("type", "extended", "upgraded"));
            String key = string(data.get("type")) + "." + bool(data, "extended", false) + "." + bool(data, "upgraded", false);
            String id = POTIONS.get(key); require(id != null, "Unknown or invalid legacy potion data: " + key);
            // Bukkit UNCRAFTABLE becomes native empty potion contents; there is no native empty potion registry entry.
            if (!id.equals("minecraft:empty")) meta.put("potion-type", id);
        }
        Map<String, Object> effects = new LinkedHashMap<>();
        for (Object raw : indexed(potion.containsKey("effects") ? potion.get("effects") : Map.of())) {
            Map<?, ?> source = map(raw, Set.of("type", "duration", "amplifier", "ambient"));
            String name = string(source.get("type")).toUpperCase(Locale.ROOT), id = EFFECT_NAMES.get(name);
            require(id != null, "Unknown legacy potion effect: " + name);
            Map<String, Object> effect = new LinkedHashMap<>();
            effect.put("==", "PotionEffect"); effect.put("effect", Integer.parseInt(id));
            effect.put("duration", integer(source.get("duration"))); effect.put("amplifier", integer(source.get("amplifier")));
            effect.put("ambient", bool(source, "ambient", false));
            // Original addCustomEffect(..., true) replaces a repeated type in its first position.
            effects.put(id, effect);
        }
        meta.put("custom-effects", new ArrayList<>(effects.values()));
    }

    private static Map<String, Object> enchantments(Object raw) {
        if (!(raw instanceof Map<?, ?> source)) throw invalid("Expected structured enchantment map");
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(string(key), integer(value)));
        return result;
    }
    private static List<?> indexed(Object raw) {
        if (raw instanceof List<?> list) return list;
        if (!(raw instanceof Map<?, ?> source)) throw invalid("Expected indexed metadata collection");
        TreeMap<Integer, Object> ordered = new TreeMap<>();
        source.forEach((key, value) -> {
            int index = integer(key);
            require(index >= 0 && !ordered.containsKey(index), "Invalid or duplicate metadata index"); ordered.put(index, value);
        });
        return new ArrayList<>(ordered.values());
    }
    private static Map<?, ?> map(Object raw, Set<String> fields) {
        if (!(raw instanceof Map<?, ?> source)) throw invalid("Expected structured metadata map");
        for (Object key : source.keySet()) require(fields.contains(key), "Unsupported structured metadata field: " + key);
        return source;
    }
    private static String plain(Object value, HolderLookup.Provider registries) {
        return Component.Serializer.toJson(LegacyBukkitMeta.legacyText(string(value)), registries);
    }
    private static String string(Object value) { if (!(value instanceof String text)) throw invalid("Expected metadata string"); return text; }
    private static int integer(Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) throw invalid("Expected metadata integer");
        try { return new BigDecimal(value.toString()).intValueExact(); }
        catch (NumberFormatException | ArithmeticException failure) { throw invalid("Invalid metadata integer: " + value); }
    }
    private static boolean bool(Map<?, ?> source, String key, boolean fallback) {
        if (!source.containsKey(key)) return fallback;
        Object value = source.get(key);
        if (value instanceof Boolean flag) return flag;
        if (value instanceof String text && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"))) return Boolean.parseBoolean(text);
        throw invalid("Expected metadata boolean: " + key);
    }
    private static void require(boolean condition, String message) { if (!condition) throw invalid(message); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
