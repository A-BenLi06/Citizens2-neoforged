package net.citizensnpcs.api.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.util.Unit;

/** Complete conversion of recognized Bukkit metadata fields; an unsupported field rejects the entire definition. */
final class LegacyBukkitMeta {
    private static final Map<String, String> ENCHANTMENT_NAMES = aliases("legacy-enchantment-names.properties");
    private static final Style RESET = Style.EMPTY.withBold(false).withItalic(false).withUnderlined(false)
            .withStrikethrough(false).withObfuscated(false);
    private static final Pattern LEGACY_URL = Pattern.compile("(?:(?:https?)://)?(?:[-\\w_.]{2,}\\.[a-z]{2,4}.*?(?=[.?!,;:]?(?:[§ \\n]|$)))", Pattern.CASE_INSENSITIVE);
    private static final Set<String> BASE_FIELDS = Set.of("==", "meta-type", "display-name", "lore",
            "custom-model-data", "Damage", "repair-cost", "Unbreakable", "enchants", "ItemFlags");

    private LegacyBukkitMeta() { }

    static ItemStack read(String encoded, ItemStack original, HolderLookup.Provider registries) {
        return read(LegacyBukkitData.read(encoded), original, registries);
    }

    static ItemStack read(Map<String, Object> meta, ItemStack original, HolderLookup.Provider registries) {
        if (!"ItemMeta".equals(meta.get("=="))) throw invalid("Expected ItemMeta serialization alias");
        String type = string(meta.get("meta-type"));
        Set<String> specialFields = LegacyBukkitMetaTypes.fields(type);
        if (type.equals("ENCHANTED") && !(original.getItem() instanceof EnchantedBookItem))
            throw invalid("Enchanted-book metadata requires an enchanted book");
        for (String key : meta.keySet()) if (!BASE_FIELDS.contains(key) && !specialFields.contains(key))
            throw invalid("Unsupported metadata field for " + type + ": " + key);
        ItemStack stack = original.copy();
        if (meta.containsKey("display-name")) stack.set(DataComponents.CUSTOM_NAME, text(string(meta.get("display-name")), registries));
        if (meta.containsKey("lore")) {
            List<Component> lines = new ArrayList<>();
            for (Object line : list(meta.get("lore"))) lines.add(text(string(line), registries));
            stack.set(DataComponents.LORE, new ItemLore(lines));
        }
        if (meta.containsKey("custom-model-data")) stack.set(DataComponents.CUSTOM_MODEL_DATA,
                new CustomModelData(integer(meta.get("custom-model-data"))));
        if (meta.containsKey("Damage")) stack.set(DataComponents.DAMAGE, nonnegative(meta.get("Damage")));
        if (meta.containsKey("repair-cost")) stack.set(DataComponents.REPAIR_COST, nonnegative(meta.get("repair-cost")));
        if (meta.containsKey("Unbreakable")) {
            if (!(meta.get("Unbreakable") instanceof Boolean flag)) throw invalid("Unbreakable must be boolean");
            if (flag) stack.set(DataComponents.UNBREAKABLE, new Unbreakable(true)); else stack.remove(DataComponents.UNBREAKABLE);
        }
        if (meta.containsKey("enchants")) enchantments(stack, DataComponents.ENCHANTMENTS, meta.get("enchants"), registries);
        if (meta.containsKey("stored-enchants")) enchantments(stack, DataComponents.STORED_ENCHANTMENTS, meta.get("stored-enchants"), registries);
        LegacyBukkitMetaTypes.apply(type, meta, stack, registries);
        if (meta.containsKey("ItemFlags")) {
            for (Object value : list(meta.get("ItemFlags"))) {
                switch (string(value)) {
                    case "HIDE_ENCHANTS" -> hide(stack, DataComponents.ENCHANTMENTS, registries);
                    case "HIDE_ATTRIBUTES" -> hide(stack, DataComponents.ATTRIBUTE_MODIFIERS, registries);
                    case "HIDE_UNBREAKABLE" -> hide(stack, DataComponents.UNBREAKABLE, registries);
                    case "HIDE_DESTROYS" -> hide(stack, DataComponents.CAN_BREAK, registries);
                    case "HIDE_PLACED_ON" -> hide(stack, DataComponents.CAN_PLACE_ON, registries);
                    case "HIDE_POTION_EFFECTS", "HIDE_ADDITIONAL_TOOLTIP" -> stack.set(DataComponents.HIDE_ADDITIONAL_TOOLTIP, Unit.INSTANCE);
                    case "HIDE_DYE" -> hide(stack, DataComponents.DYED_COLOR, registries);
                    case "HIDE_ARMOR_TRIM" -> hide(stack, DataComponents.TRIM, registries);
                    default -> throw invalid("Unsupported item flag: " + value);
                }
            }
        }
        var ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        Tag encodedStack = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow(LegacyBukkitMeta::invalid);
        return ItemStack.CODEC.parse(ops, encodedStack).getOrThrow(LegacyBukkitMeta::invalid);
    }

    private static <T> void enchantments(ItemStack stack, DataComponentType<T> type, Object raw, HolderLookup.Provider registries) {
        if (!(raw instanceof Map<?, ?> enchants)) throw invalid("Expected enchantment map");
        JsonObject levels = new JsonObject();
        for (var entry : enchants.entrySet()) {
            String name = string(entry.getKey());
            String target = name.indexOf(':') < 0 ? ENCHANTMENT_NAMES.getOrDefault(name.toUpperCase(Locale.ROOT),
                    "minecraft:" + name.toLowerCase(Locale.ROOT)) : name;
            // The vanilla sweeping key changed in 1.21; explicit source identity has the same migration as Bukkit's name.
            if (target.equals("minecraft:sweeping")) target = "minecraft:sweeping_edge";
            ResourceLocation id = ResourceLocation.tryParse(target);
            if (id == null || !id.toString().equals(target) || levels.has(target)) throw invalid("Invalid or duplicate enchantment: " + name);
            levels.addProperty(target, nonnegative(entry.getValue()));
        }
        JsonObject component = new JsonObject(); component.add("levels", levels);
        stack.set(type, type.codecOrThrow().parse(RegistryOps.create(JsonOps.INSTANCE, registries), component)
                .getOrThrow(LegacyBukkitMeta::invalid));
    }

    private static <T> void hide(ItemStack stack, DataComponentType<T> type, HolderLookup.Provider registries) {
        T value = stack.get(type);
        if (value == null) return;
        var ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        Tag encoded = type.codecOrThrow().encodeStart(ops, value).getOrThrow(LegacyBukkitMeta::invalid);
        if (!(encoded instanceof CompoundTag tag)) throw invalid("Expected tooltip component object");
        tag.putBoolean("show_in_tooltip", false);
        stack.set(type, type.codecOrThrow().parse(ops, tag).getOrThrow(LegacyBukkitMeta::invalid));
    }

    static Component text(String raw, HolderLookup.Provider registries) {
        try {
            Component json = Component.Serializer.fromJson(raw, registries);
            if (json != null) return json;
        } catch (RuntimeException ignored) { }
        return legacyText(raw);
    }

    static Component legacyText(String raw) {
        // Bukkit's older plain strings use section-sign formatting. Ampersands and Citizens markup stay literal.
        MutableComponent result = Component.empty(); StringBuilder run = new StringBuilder(); Style style = Style.EMPTY;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '§' && i + 1 < raw.length()) {
                if ((raw.charAt(i + 1) == 'x' || raw.charAt(i + 1) == 'X') && i + 13 < raw.length()) {
                    int color = 0; boolean valid = true;
                    for (int j = 0; j < 6; j++) {
                        int digit = Character.digit(raw.charAt(i + 3 + j * 2), 16);
                        if (raw.charAt(i + 2 + j * 2) != '§' || digit < 0) { valid = false; break; }
                        color = (color << 4) | digit;
                    }
                    if (valid) {
                        appendText(result, run, style);
                        style = RESET.withColor(color); i += 13; continue;
                    }
                }
                ChatFormatting format = ChatFormatting.getByCode(raw.charAt(i + 1));
                if (format != null) {
                    appendText(result, run, style);
                    style = format == ChatFormatting.RESET ? RESET
                            : format.isColor() ? RESET.withColor(format) : style.applyFormat(format);
                    i++; continue;
                }
            }
            run.append(c);
        }
        appendText(result, run, style);
        return result;
    }

    private static void appendText(MutableComponent result, StringBuilder run, Style style) {
        String text = run.toString(); run.setLength(0); int end = 0;
        var urls = LEGACY_URL.matcher(text);
        while (urls.find()) {
            if (urls.start() > end) result.append(Component.literal(text.substring(end, urls.start())).withStyle(style));
            String url = urls.group(); String target = url.startsWith("http://") || url.startsWith("https://") ? url : "http://" + url;
            result.append(Component.literal(url).withStyle(style.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, target))));
            end = urls.end();
        }
        if (end < text.length()) result.append(Component.literal(text.substring(end)).withStyle(style));
    }

    static Map<String, String> aliases(String resource) {
        Properties properties = new Properties();
        try (InputStream input = LegacyBukkitMeta.class.getResourceAsStream("/citizens/" + resource)) {
            if (input == null) throw new IllegalStateException("Missing legacy mapping: " + resource);
            properties.load(input);
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
        Map<String, String> result = new LinkedHashMap<>();
        properties.forEach((key, value) -> result.put((String) key, (String) value));
        return Map.copyOf(result);
    }
    private static String string(Object value) { if (!(value instanceof String text)) throw invalid("Expected string metadata"); return text; }
    private static List<?> list(Object value) { if (!(value instanceof List<?> list)) throw invalid("Expected metadata list"); return list; }
    private static int integer(Object value) { if (!(value instanceof Integer number)) throw invalid("Expected integer metadata"); return number; }
    private static int nonnegative(Object value) { int number = integer(value); if (number < 0) throw invalid("Negative metadata value"); return number; }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
