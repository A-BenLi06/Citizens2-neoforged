package net.yuuniverse.interactions;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;

/** Converts original item NBT with Mojang's versioned fixes and the installed providers' component codecs. */
final class LegacyItemData {
    private static final int MAX_NBT_BYTES = 16 * 1024 * 1024;
    private static final ResourceLocation MUSIC_CD = ResourceLocation.parse("netmusic:music_cd");
    private static final ResourceLocation PACKAGE = ResourceLocation.parse("refurbished_furniture:package");

    private LegacyItemData() { }

    static CompoundTag decode(String encoded) {
        try (var input = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded))))) {
            CompoundTag tag = NbtIo.read(input, NbtAccounter.create(MAX_NBT_BYTES));
            if (input.read() != -1) throw new IllegalArgumentException("Trailing data in saved item NBT");
            return tag;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Malformed compressed saved item NBT", failure);
        }
    }

    static ItemStack convert(ResourceLocation item, CompoundTag tag, int version, RegistryAccess registries) {
        CompoundTag legacy = new CompoundTag();
        legacy.putString("id", item.toString()); legacy.putByte("Count", (byte) 1); legacy.put("tag", tag.copy());
        return convert(legacy, version, registries, 0, false);
    }

    private static ItemStack convert(CompoundTag legacy, int version, RegistryAccess registries, int depth, boolean resolveAlias) {
        if (depth > 64) throw new IllegalArgumentException("Saved item containers are nested too deeply");
        int current = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        if (version <= 0 || version > current) throw new IllegalArgumentException("Unsupported saved item data version: " + version);
        if (!legacy.contains("id", Tag.TAG_STRING) || !legacy.contains("Count", Tag.TAG_ANY_NUMERIC)
                || legacy.getInt("Count") <= 0)
            throw new IllegalArgumentException("Saved item must contain an id and positive Count");
        CompoundTag source = legacy.copy();
        String id = source.getString("id");
        // Nested item NBT uses native IDs; reuse the explicit legacy material aliases for renamed providers.
        String alias = resolveAlias ? ItemAliases.lookup(id) : null;
        ResourceLocation originalId = ResourceLocation.tryParse(id);
        if (resolveAlias && alias == null && originalId != null)
            alias = ItemAliases.lookup(CheckItem.legacyMaterialName(originalId));
        ResourceLocation resolved = ResourceLocation.tryParse(alias == null ? id : alias);
        if (resolved == null || BuiltInRegistries.ITEM.getOptional(resolved).isEmpty())
            throw new IllegalArgumentException("Unavailable saved item: " + id);
        source.putString("id", resolved.toString());
        CompoundTag originalTag = source.getCompound("tag");
        Tag fixed = DataFixers.getDataFixer().update(References.ITEM_STACK,
                new Dynamic<Tag>(NbtOps.INSTANCE, source), version, current).getValue();
        if (!(fixed instanceof CompoundTag converted) || converted.contains("tag"))
            throw new IllegalArgumentException("Saved item version did not migrate its legacy tag");
        ItemStack stack = ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), converted)
                .getOrThrow(message -> new IllegalArgumentException("Cannot decode saved item " + id + ": " + message));
        if (resolved.equals(MUSIC_CD) && originalTag.contains("NetMusicSongInfo")) {
            CompoundTag song = compound(originalTag, "NetMusicSongInfo");
            CompoundTag component = song.copy();
            require(song, "url", Tag.TAG_STRING); require(song, "name", Tag.TAG_STRING); require(song, "time", Tag.TAG_INT);
            component.putInt("time_second", song.getInt("time")); component.remove("time");
            apply(stack, ResourceLocation.parse("netmusic:song_info"), component, registries);
            removeCustomKeys(stack, Set.of("NetMusicSongInfo"));
        }
        if (resolved.equals(PACKAGE)) {
            CompoundTag info = new CompoundTag();
            for (String field : List.of("Sender", "Message")) {
                if (originalTag.contains(field)) {
                    require(originalTag, field, Tag.TAG_STRING);
                    info.putString(field.equals("Sender") ? "sender" : "message", originalTag.getString(field));
                }
            }
            if (!info.isEmpty()) apply(stack, ResourceLocation.parse("refurbished_furniture:package_info"), info, registries);
            if (originalTag.contains("Items")) {
                require(originalTag, "Items", Tag.TAG_LIST);
                var entries = originalTag.getList("Items", Tag.TAG_COMPOUND);
                if (entries.size() != ((net.minecraft.nbt.ListTag) originalTag.get("Items")).size())
                    throw new IllegalArgumentException("Saved package Items must contain compounds");
                List<ItemStack> items = new ArrayList<>(); Set<Integer> occupied = new HashSet<>();
                for (Tag entry : entries) {
                    CompoundTag child = (CompoundTag) entry;
                    require(child, "Slot", Tag.TAG_BYTE);
                    int slot = Byte.toUnsignedInt(child.getByte("Slot"));
                    if (!occupied.add(slot)) throw new IllegalArgumentException("Duplicate saved package slot: " + slot);
                    while (items.size() <= slot) items.add(ItemStack.EMPTY);
                    items.set(slot, convert(child, version, registries, depth + 1, true));
                }
                stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
            }
            removeCustomKeys(stack, Set.of("Sender", "Message", "Items"));
        }
        return stack;
    }

    private static CompoundTag compound(CompoundTag tag, String key) {
        require(tag, key, Tag.TAG_COMPOUND); return tag.getCompound(key);
    }

    private static void require(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) throw new IllegalArgumentException("Invalid saved item field: " + key);
    }

    private static void apply(ItemStack stack, ResourceLocation id, Tag value, RegistryAccess registries) {
        DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.getOptional(id)
                .orElseThrow(() -> new IllegalArgumentException("Unavailable saved item component: " + id));
        apply(stack, type, value, registries);
    }

    private static <T> void apply(ItemStack stack, DataComponentType<T> type, Tag value, RegistryAccess registries) {
        T decoded = type.codecOrThrow().parse(registries.createSerializationContext(NbtOps.INSTANCE), value)
                .getOrThrow(message -> new IllegalArgumentException("Invalid saved item component: " + message));
        stack.set(type, decoded);
    }

    private static void removeCustomKeys(ItemStack stack, Set<String> keys) {
        CompoundTag remaining = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        keys.forEach(remaining::remove);
        if (remaining.isEmpty()) stack.remove(DataComponents.CUSTOM_DATA);
        else stack.set(DataComponents.CUSTOM_DATA, CustomData.of(remaining));
    }
}
