package net.yuuniverse.interactions;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.server.network.Filterable;

/**
 * The saved-item library the dialogues hand out, read from the {@code ItemEdit} database the old server used.
 * <p>
 * {@code si give <id> <amount> <player> silent} appears 1899 times across the conversations, referencing 114 distinct
 * ids - the UDT banknotes, vouchers and shop goods the whole economy of those dialogues is denominated in. The database
 * is plain YAML rather than the Base64 blob such plugins often write, so the definitions convert directly:
 * {@code type} is a Bukkit material name, and {@code display-name}/{@code lore} are already component JSON.
 * <p>
 * Two things in the real data need care. Lore entries are sometimes written with a trailing comma inside the string
 * ({@code '{"text":"10 UDT"},'}), which is not valid JSON and has to be tolerated. And 13 of the 140 entries carry an
 * {@code internal} Base64 blob of Bukkit's own serialisation, which cannot be read outside Bukkit - those yield the item
 * without whatever that blob added, and say so once at load.
 */
public final class ItemLibrary {
    private static final Logger LOGGER = LoggerFactory.getLogger("interactions");

    private final Map<String, ItemStack> items = new HashMap<>();
    private int unreadable;

    @SuppressWarnings("unchecked")
    public void load(File file, RegistryAccess registries) {
        items.clear();
        unreadable = 0;
        if (!file.isFile()) {
            LOGGER.warn("No saved-item database at {} - \"si give\" actions will do nothing.", file);
            return;
        }
        Map<String, Object> root;
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(32 * 1024 * 1024);
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            Object loaded = new Yaml(options).load(reader);
            if (!(loaded instanceof Map)) {
                LOGGER.error("Saved-item database {} is not a map; no items loaded.", file.getName());
                return;
            }
            root = (Map<String, Object>) loaded;
        } catch (Exception ex) {
            LOGGER.error("Could not read the saved-item database {}: {}", file.getName(), ex.toString());
            return;
        }
        for (Map.Entry<String, Object> entry : root.entrySet()) {
            if (!(entry.getValue() instanceof Map))
                continue;
            Object item = ((Map<String, Object>) entry.getValue()).get("item");
            if (!(item instanceof Map))
                continue;
            ItemStack stack = build((Map<String, Object>) item, registries, entry.getKey());
            if (stack != null && !stack.isEmpty()) {
                items.put(String.valueOf(entry.getKey()), stack);
            }
        }
        LOGGER.info("Loaded {} saved item(s){}.", items.size(),
                unreadable == 0 ? "" : ", " + unreadable + " carrying Bukkit-serialised data that cannot be read here");
    }

    /** @return a fresh copy of the saved item, or null when that id is not in the database */
    public ItemStack get(String id, int amount) {
        ItemStack stack = items.get(id);
        if (stack == null)
            return null;
        ItemStack copy = stack.copy();
        copy.setCount(Math.max(1, amount));
        return copy;
    }

    public int size() {
        return items.size();
    }

    @SuppressWarnings("unchecked")
    private ItemStack build(Map<String, Object> item, RegistryAccess registries, String id) {
        String type = item.get("type") == null ? null : String.valueOf(item.get("type"));
        if (type == null)
            return null;
        ResourceLocation key = ResourceLocation.tryParse(CheckItem.normaliseId(type));
        Item resolved = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
        if (resolved == null) {
            LOGGER.warn("Saved item {} is a {}, which does not exist on this server; skipped.", id, type);
            return null;
        }
        ItemStack stack = new ItemStack(resolved);
        Object metaRaw = item.get("meta");
        if (!(metaRaw instanceof Map))
            return stack;
        Map<String, Object> meta = (Map<String, Object>) metaRaw;
        if (meta.containsKey("internal")) {
            unreadable++;
        }
        Component name = json(meta.get("display-name"), registries);
        if (name != null) {
            stack.set(DataComponents.CUSTOM_NAME, name);
        }
        Object loreRaw = meta.get("lore");
        if (loreRaw instanceof List) {
            List<Component> lines = new ArrayList<>();
            for (Object line : (List<Object>) loreRaw) {
                Component parsed = json(line, registries);
                lines.add(parsed == null ? Component.literal(String.valueOf(line)) : parsed);
            }
            if (!lines.isEmpty()) {
                stack.set(DataComponents.LORE, new ItemLore(lines));
            }
        }
        // the six written books in the database
        if (meta.get("pages") instanceof List<?> pages && !pages.isEmpty()) {
            List<Filterable<Component>> content = new ArrayList<>();
            for (Object page : pages) {
                Component parsed = json(page, registries);
                content.add(Filterable.passThrough(parsed == null ? Component.literal(String.valueOf(page)) : parsed));
            }
            stack.set(DataComponents.WRITTEN_BOOK_CONTENT,
                    new WrittenBookContent(Filterable.passThrough(str(meta.get("title"))), str(meta.get("author")),
                            0, content, true));
        }
        return stack;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * Parses one stored component, tolerating the trailing comma the real database contains and falling back to plain
     * text rather than dropping the line.
     */
    private static Component json(Object raw, RegistryAccess registries) {
        if (raw == null)
            return null;
        String value = String.valueOf(raw).trim();
        while (value.endsWith(",")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        if (value.isEmpty())
            return null;
        if (!value.startsWith("{") && !value.startsWith("[") && !value.startsWith("\"")) {
            return Text.legacy(value);
        }
        try {
            return Component.Serializer.fromJson(value, registries);
        } catch (Exception ex) {
            return Text.legacy(value);
        }
    }
}
