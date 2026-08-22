package net.citizensnpcs.api.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

import com.google.common.base.Joiner;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.core.component.DataComponents;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Reads and writes {@link ItemStack}s to {@link DataKey}s.
 * <p>
 * <b>Current format.</b> The stack is stored as a single compact SNBT string under {@code nbt}, produced by
 * {@link ItemStack#CODEC}. This carries data components losslessly, including ones added by other mods.
 *
 * <pre>
 * nbt: '{id:"minecraft:diamond_sword",count:1,components:{"minecraft:damage":5}}'
 * editable_components:
 *   display_name: '&amp;aExcalibur'
 *   lore: 'line one&lt;br&gt;line two'
 *   edited: false
 * </pre>
 *
 * {@code editable_components} is written for the benefit of anyone hand-editing saves.yml, exactly as upstream does:
 * setting {@code edited: true} makes the name and lore there win over what is in {@code nbt} on the next load.
 * <p>
 * <b>Legacy format.</b> Saves written by the Bukkit plugin use {@code type}/{@code amount}/{@code durability} plus a
 * base64 {@code meta} blob. The blob is a Java-serialised CraftBukkit {@code ItemMeta} and cannot be read without
 * CraftBukkit on the classpath, so migration is partial: item id, count, damage, display name and lore are recovered;
 * enchantments, custom model data, skull textures, attribute modifiers and any other meta are not. A warning naming the
 * item is logged whenever a blob is dropped. Migrated entries are rewritten in the current format the next time the
 * owning object is saved.
 */
public class ItemStorage {
    private ItemStorage() {
    }

    /**
     * @return the stored stack, or null when the key holds nothing loadable — matching upstream, which returns null
     *         rather than an empty stack so that callers can distinguish "no item" from "air"
     */
    public static ItemStack loadItemStack(DataKey root) {
        ItemStack stack = root.keyExists("nbt") ? loadCurrent(root) : loadLegacy(root);
        if (stack == null || stack.isEmpty())
            return null;
        applyEditableComponents(root, stack);
        if (deserialiseHook != null) {
            deserialiseHook.accept(root, stack);
        }
        return stack;
    }

    public static void saveItem(DataKey key, ItemStack item) {
        // the legacy keys are cleared unconditionally so a migrated entry does not keep stale data around
        key.removeKey("type");
        key.removeKey("type_key");
        key.removeKey("type_namespace");
        key.removeKey("id");
        key.removeKey("durability");
        key.removeKey("data");
        key.removeKey("mdata");
        key.removeKey("meta");
        key.removeKey("amount");
        key.removeKey("enchantments");
        key.removeKey("displayname");
        key.removeKey("lore");

        if (item == null || item.isEmpty()) {
            key.removeKey("nbt");
            key.removeKey("editable_components");
            return;
        }
        RegistryOps<Tag> ops = ops();
        Tag encoded = ItemStack.CODEC.encodeStart(ops, item).result().orElse(null);
        if (encoded == null) {
            // strict: a partial encode would silently drop components, so write nothing and say so
            Messaging.severe("Could not serialise item " + item + " at " + key.getPath()
                    + "; it was not saved. This usually means a data component could not be resolved.");
            return;
        }
        key.setString("nbt", encoded.toString());

        Component name = item.get(DataComponents.CUSTOM_NAME);
        if (name != null) {
            key.setString("editable_components.display_name", name.getString());
            key.setBoolean("editable_components.edited", false);
        } else {
            key.removeKey("editable_components.display_name");
        }
        ItemLore lore = item.get(DataComponents.LORE);
        if (lore != null && !lore.lines().isEmpty()) {
            List<String> lines = new ArrayList<>(lore.lines().size());
            for (Component line : lore.lines()) {
                lines.add(line.getString());
            }
            key.setString("editable_components.lore", Joiner.on("<br>").join(lines));
            key.setBoolean("editable_components.edited", false);
        } else {
            key.removeKey("editable_components.lore");
        }
        if (serialiseHook != null) {
            serialiseHook.accept(key, item);
        }
    }

    /** Hand-edited name and lore win over the stored NBT, but only when the editor sets {@code edited: true}. */
    private static void applyEditableComponents(DataKey root, ItemStack stack) {
        if (!root.keyExists("editable_components") || !root.getBoolean("editable_components.edited", false))
            return;

        if (root.keyExists("editable_components.display_name")) {
            stack.set(DataComponents.CUSTOM_NAME,
                    Messaging.minecraftComponentFromRawMessage(root.getString("editable_components.display_name")));
        }
        if (root.keyExists("editable_components.lore")) {
            stack.set(DataComponents.LORE, new ItemLore(parseLore(root.getString("editable_components.lore"))));
        }
        root.setBoolean("editable_components.edited", false);
    }

    private static ItemStack loadCurrent(DataKey root) {
        String snbt = root.getString("nbt");
        if (snbt == null || snbt.isEmpty())
            return null;
        try {
            CompoundTag tag = TagParser.parseTag(snbt);
            return ItemStack.CODEC.parse(ops(), tag).resultOrPartial(Messaging::severe).orElse(null);
        } catch (CommandSyntaxException e) {
            Messaging.severe("Malformed item NBT at " + root.getPath() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * One-way migration read of the Bukkit plugin's format. Recovers what survives without CraftBukkit; see the class
     * javadoc for what does not.
     */
    private static ItemStack loadLegacy(DataKey root) {
        String raw;
        if (root.keyExists("type_key")) {
            raw = root.getString("type_namespace", "minecraft") + ":" + root.getString("type_key");
        } else {
            raw = root.getString("type", root.getString("id"));
        }
        if (raw == null || raw.isEmpty())
            return null;

        ResourceLocation id = RegistryUtil.parseKey(raw.toLowerCase(Locale.ROOT));
        if (id == null)
            return null;
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            Messaging.warn("Dropping unknown legacy item type '" + raw + "' at " + root.getPath());
            return null;
        }
        ItemStack stack = new ItemStack(item, Math.max(1, root.getInt("amount", 1)));

        int damage = root.getInt("durability", root.getInt("data", 0));
        if (damage > 0 && stack.isDamageableItem()) {
            stack.set(DataComponents.DAMAGE, damage);
        }
        // editable_components is plain text, so unlike the base64 meta blob it does survive the migration
        if (root.keyExists("editable_components.display_name")) {
            stack.set(DataComponents.CUSTOM_NAME,
                    Messaging.minecraftComponentFromRawMessage(root.getString("editable_components.display_name")));
        }
        if (root.keyExists("editable_components.lore")) {
            stack.set(DataComponents.LORE, new ItemLore(parseLore(root.getString("editable_components.lore"))));
        }
        if (root.keyExists("meta")) {
            LegacyItemMetaReader reader = legacyItemMetaReader;
            boolean handled = false;
            if (reader != null) {
                try {
                    handled = reader.apply(root.getString("meta"), stack);
                } catch (Throwable t) {
                    Messaging.severe("Legacy item meta reader failed at " + root.getPath() + ": " + t);
                }
            }
            if (!handled) {
                Messaging.warn("Migrated legacy item '" + raw + "' at " + root.getPath()
                        + " but could not read its Bukkit item meta: enchantments, custom model data, skull textures"
                        + " and attribute modifiers were dropped. Re-set the item to restore them, or install a"
                        + " LegacyItemMetaReader (see ItemStorage#setLegacyItemMetaReader).");
            }
        }
        return stack;
    }

    /**
     * Parses an item written the way a player writes it for {@code /give} — {@code minecraft:diamond_sword} or
     * {@code stone[custom_name='{"text":"Rock"}']} — using vanilla's own parser, so every component and every modded
     * item works and the syntax is one players already know.
     * <p>
     * Upstream parses Bukkit material names with an optional NBT tail of its own invention.
     *
     * @return the stack, or {@link ItemStack#EMPTY} if the string does not parse
     */
    public static ItemStack parseItemStack(String raw, int count) {
        if (raw == null || raw.trim().isEmpty())
            return ItemStack.EMPTY;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        HolderLookup.Provider registries = server == null ? RegistryAccess.EMPTY : server.registryAccess();
        try {
            ItemParser.ItemResult result = new ItemParser(registries).parse(new StringReader(raw.trim()));
            ItemStack stack = new ItemStack(result.item(), Math.max(1, count));
            stack.applyComponents(result.components());
            return stack;
        } catch (CommandSyntaxException | RuntimeException ex) {
            Messaging.severe("Could not parse item", raw, "-", ex.getMessage());
            return ItemStack.EMPTY;
        }
    }

    /**
     * A nested map view of a stack's data components, keyed by component id with the {@code minecraft:} prefix stripped —
     * so {@code custom_name}, {@code enchantments}, {@code custom_data.mykey}. Used by shop item filters to compare only
     * the parts of an item that matter.
     * <p>
     * Upstream builds this out of Bukkit's {@code ItemMeta.serialize()}, whose keys are Bukkit's own invention
     * ({@code display-name}, {@code custom-model-data}). Components are used here instead: the ids are the ones players
     * already see in {@code /give} and in item NBT, and components added by other mods appear too, which the Bukkit map
     * could never show.
     */
    public static Map<String, Object> componentMap(ItemStack item) {
        if (item == null || item.isEmpty())
            return Collections.emptyMap();
        Tag encoded = ItemStack.CODEC.encodeStart(ops(), item).result().orElse(null);
        if (!(encoded instanceof CompoundTag tag) || !tag.contains("components"))
            return Collections.emptyMap();
        Object unwrapped = unwrapTag(tag.getCompound("components"));
        return unwrapped instanceof Map ? (Map<String, Object>) unwrapped : Collections.emptyMap();
    }

    /** Turns NBT into plain maps, lists and values so callers can walk it without knowing the tag types. */
    private static Object unwrapTag(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (String key : compound.getAllKeys()) {
                String stripped = key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
                map.put(stripped, unwrapTag(compound.get(key)));
            }
            return map;
        }
        if (tag instanceof CollectionTag<?> list) {
            List<Object> values = new ArrayList<>();
            for (Tag element : list) {
                values.add(unwrapTag(element));
            }
            return values;
        }
        if (tag instanceof NumericTag number)
            return number.getAsNumber();
        if (tag instanceof StringTag string)
            return string.getAsString();
        return tag == null ? null : tag.toString();
    }

    /**
     * Registry-aware NBT ops. Components can reference datapack registries (enchantments most of all), so the running
     * server's registry access is used when there is one. Without a server — tooling, tests — an empty registry access
     * is used instead: plain items still round-trip because {@link ItemStack#CODEC} resolves the item itself from
     * {@link BuiltInRegistries}, while anything genuinely needing a datapack registry fails in the codec and is
     * reported rather than silently mangled.
     */
    private static RegistryOps<Tag> ops() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return RegistryOps.create(NbtOps.INSTANCE,
                server == null ? RegistryAccess.EMPTY : server.registryAccess());
    }

    private static List<Component> parseLore(String joined) {
        List<Component> lines = new ArrayList<>();
        if (joined == null || joined.isEmpty())
            return lines;
        for (String line : joined.split("<br>")) {
            lines.add(Messaging.minecraftComponentFromRawMessage(line));
        }
        return lines;
    }

    /**
     * Installs a decoder for the base64 {@code meta} blob in legacy Bukkit saves.
     * <p>
     * The blob is a Java-serialised CraftBukkit {@code ItemMeta} and cannot be read without CraftBukkit on the
     * classpath, so the built-in migration drops it and warns. A reader registered here — one that substitutes a
     * stand-in for {@code org.bukkit.util.io.Wrapper} to recover the underlying {@code meta.serialize()} map, then maps
     * it onto data components — restores enchantments and the rest without any change to this class.
     * <p>
     * Register before any NPC data is loaded, i.e. before {@code ServerStartingEvent} finishes.
     */
    public static void setLegacyItemMetaReader(LegacyItemMetaReader reader) {
        legacyItemMetaReader = reader;
    }

    public static LegacyItemMetaReader getLegacyItemMetaReader() {
        return legacyItemMetaReader;
    }

    /**
     * Called after an item has been written, and after one has been read.
     * <p>
     * These are the seams upstream exposes as {@code CitizensSerialiseMetaEvent} and
     * {@code CitizensDeserialiseMetaEvent}, which addons use to attach their own data alongside an item. They are kept
     * as plain hooks for now; P3 wires the corresponding NeoForge events into them so the public event API returns.
     */
    public static void setHooks(BiConsumer<DataKey, ItemStack> onSerialise, BiConsumer<DataKey, ItemStack> onDeserialise) {
        serialiseHook = onSerialise;
        deserialiseHook = onDeserialise;
    }

    /** Decodes a legacy Bukkit {@code ItemMeta} blob onto a migrated stack. */
    @FunctionalInterface
    public interface LegacyItemMetaReader {
        /**
         * @param base64
         *            the raw {@code meta} value as stored by the Bukkit plugin
         * @param stack
         *            the partially migrated stack, to be mutated in place
         * @return true if the blob was understood; false to fall back to the "meta dropped" warning
         */
        boolean apply(String base64, ItemStack stack);
    }

    private static BiConsumer<DataKey, ItemStack> deserialiseHook;
    private static volatile LegacyItemMetaReader legacyItemMetaReader;
    private static BiConsumer<DataKey, ItemStack> serialiseHook;
}
