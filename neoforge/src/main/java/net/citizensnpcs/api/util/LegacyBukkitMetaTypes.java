package net.citizensnpcs.api.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.Filterable;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.KnowledgeBookItem;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.TippedArrowItem;
import net.minecraft.world.item.WritableBookItem;
import net.minecraft.world.item.WrittenBookItem;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;

/** Schema-specific Bukkit fields. Nested wrappers remain plain maps until their exact aliases and fields are checked. */
final class LegacyBukkitMetaTypes {
    private static final Map<String, Set<String>> FIELDS = Map.ofEntries(
            Map.entry("UNSPECIFIC", Set.of()), Map.entry("ENCHANTED", Set.of("stored-enchants")),
            Map.entry("ARMOR", Set.of("trim")), Map.entry("LEATHER_ARMOR", Set.of("color")),
            Map.entry("COLORABLE_ARMOR", Set.of("color", "trim")), Map.entry("BOOK", Set.of("pages")),
            Map.entry("BOOK_SIGNED", Set.of("pages", "title", "author", "generation", "resolved")),
            Map.entry("KNOWLEDGE_BOOK", Set.of("Recipes")),
            Map.entry("POTION", Set.of("potion-type", "custom-color", "custom-effects")));
    private static final Map<String, String> EFFECT_IDS = LegacyBukkitMeta.aliases("legacy-potion-effect-ids.properties");

    private LegacyBukkitMetaTypes() { }
    static Set<String> fields(String type) {
        Set<String> fields = FIELDS.get(type);
        if (fields == null) throw invalid("Unsupported metadata type: " + type);
        return fields;
    }

    static void apply(String type, Map<String, Object> meta, ItemStack stack, HolderLookup.Provider registries) {
        switch (type) {
            case "ARMOR", "COLORABLE_ARMOR", "LEATHER_ARMOR" -> armor(type, meta, stack, registries);
            case "BOOK" -> {
                require(stack.getItem() instanceof WritableBookItem, "Writable-book metadata requires a writable book");
                List<Filterable<String>> pages = new ArrayList<>();
                for (Object page : list(meta.getOrDefault("pages", List.of()))) pages.add(Filterable.passThrough(string(page)));
                stack.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(pages));
            }
            case "BOOK_SIGNED" -> {
                require(stack.getItem() instanceof WrittenBookItem, "Signed-book metadata requires a written book");
                List<Filterable<Component>> pages = new ArrayList<>();
                for (Object page : list(meta.getOrDefault("pages", List.of())))
                    pages.add(Filterable.passThrough(LegacyBukkitMeta.text(string(page), registries)));
                stack.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                        Filterable.passThrough(string(meta.getOrDefault("title", ""))), string(meta.getOrDefault("author", "")),
                        integer(meta.getOrDefault("generation", 0)), pages, bool(meta, "resolved", false)));
            }
            case "KNOWLEDGE_BOOK" -> {
                require(stack.getItem() instanceof KnowledgeBookItem, "Recipe metadata requires a knowledge book");
                List<ResourceLocation> recipes = new ArrayList<>();
                for (Object recipe : list(meta.getOrDefault("Recipes", List.of()))) recipes.add(key(recipe));
                // Names are data, as in vanilla knowledge books; a missing recipe may return in a later datapack.
                stack.set(DataComponents.RECIPES, recipes);
            }
            case "POTION" -> potion(meta, stack, registries);
            default -> { }
        }
    }

    private static void armor(String type, Map<String, Object> meta, ItemStack stack, HolderLookup.Provider registries) {
        if (!type.equals("LEATHER_ARMOR")) require(stack.getItem() instanceof ArmorItem, "Armor metadata requires armor");
        if (type.equals("LEATHER_ARMOR") || type.equals("COLORABLE_ARMOR")) {
            require(stack.is(ItemTags.DYEABLE), "Color metadata requires a dyeable item");
            if (meta.containsKey("color")) stack.set(DataComponents.DYED_COLOR, new DyedItemColor(color(meta.get("color")), true));
        }
        if (meta.containsKey("trim")) {
            require(stack.is(ItemTags.TRIMMABLE_ARMOR), "Trim metadata requires trimmable armor");
            Map<?, ?> trim = map(meta.get("trim"), Set.of("material", "pattern"));
            JsonObject data = new JsonObject();
            data.addProperty("material", key(trim.get("material")).toString());
            data.addProperty("pattern", key(trim.get("pattern")).toString());
            component(stack, DataComponents.TRIM, data, registries);
        }
    }

    private static void potion(Map<String, Object> meta, ItemStack stack, HolderLookup.Provider registries) {
        require(stack.getItem() instanceof PotionItem || stack.getItem() instanceof TippedArrowItem,
                "Potion metadata requires a potion or tipped arrow");
        JsonObject contents = new JsonObject();
        if (meta.containsKey("potion-type")) contents.addProperty("potion", key(meta.get("potion-type")).toString());
        if (meta.containsKey("custom-color")) contents.addProperty("custom_color", color(meta.get("custom-color")));
        if (meta.containsKey("custom-effects")) {
            JsonArray effects = new JsonArray();
            for (Object raw : list(meta.get("custom-effects"))) {
                Map<?, ?> effect = map(raw, Set.of("==", "effect", "duration", "amplifier", "ambient", "has-particles", "has-icon"));
                require("PotionEffect".equals(effect.get("==")), "Expected PotionEffect wrapper");
                int legacyId = integer(effect.get("effect"));
                String id = EFFECT_IDS.get(Integer.toString(legacyId));
                if (id == null) throw invalid("Unknown historical potion effect id: " + legacyId);
                int duration = integer(effect.get("duration"));
                require(duration >= -1, "Unsupported negative potion duration");
                int amplifier = integer(effect.get("amplifier"));
                require(amplifier >= 0 && amplifier <= 255, "Unsupported potion amplifier");
                JsonObject data = new JsonObject(); data.addProperty("id", id);
                data.addProperty("duration", duration); data.addProperty("amplifier", amplifier);
                data.addProperty("ambient", bool(effect, "ambient", false));
                boolean particles = bool(effect, "has-particles", true);
                data.addProperty("show_particles", particles); data.addProperty("show_icon", bool(effect, "has-icon", particles));
                effects.add(data);
            }
            contents.add("custom_effects", effects);
        }
        component(stack, DataComponents.POTION_CONTENTS, contents, registries);
    }

    private static int color(Object raw) {
        Map<?, ?> color = map(raw, Set.of("==", "ALPHA", "RED", "GREEN", "BLUE"));
        require("Color".equals(color.get("==")), "Expected Color wrapper");
        if (color.containsKey("ALPHA")) channel(color.get("ALPHA"));
        // CraftMetaLeatherArmor and CraftMetaPotion call Color.asRGB(); their native tags do not use alpha.
        return channel(color.get("RED")) << 16 | channel(color.get("GREEN")) << 8 | channel(color.get("BLUE"));
    }
    private static int channel(Object value) { int channel = integer(value); require(channel >= 0 && channel <= 255, "Invalid color channel"); return channel; }
    private static <T> void component(ItemStack stack, DataComponentType<T> type, JsonElement data, HolderLookup.Provider registries) {
        stack.set(type, type.codecOrThrow().parse(RegistryOps.create(JsonOps.INSTANCE, registries), data).getOrThrow(LegacyBukkitMetaTypes::invalid));
    }
    private static Map<?, ?> map(Object value, Set<String> fields) {
        if (!(value instanceof Map<?, ?> map)) throw invalid("Expected metadata map");
        for (Object key : map.keySet()) if (!fields.contains(key)) throw invalid("Unsupported nested metadata field: " + key);
        return map;
    }
    private static boolean bool(Map<?, ?> map, String key, boolean fallback) {
        if (!map.containsKey(key)) return fallback;
        if (!(map.get(key) instanceof Boolean flag)) throw invalid("Expected boolean: " + key); return flag;
    }
    private static ResourceLocation key(Object value) {
        String raw = string(value); ResourceLocation key = ResourceLocation.tryParse(raw);
        if (key == null || !key.toString().equals(raw)) throw invalid("Expected canonical registry key: " + raw); return key;
    }
    private static String string(Object value) { if (!(value instanceof String text)) throw invalid("Expected string metadata"); return text; }
    private static List<?> list(Object value) { if (!(value instanceof List<?> list)) throw invalid("Expected metadata list"); return list; }
    private static int integer(Object value) { if (!(value instanceof Integer number)) throw invalid("Expected integer metadata"); return number; }
    private static void require(boolean valid, String message) { if (!valid) throw invalid(message); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
