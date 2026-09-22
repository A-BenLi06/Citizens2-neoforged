package net.citizensnpcs.api.util;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.item.ItemStack;

/** A mutable API list that retains an unavailable entry's definition at its position until it is edited or removed. */
public final class StoredItemList extends AbstractList<ItemStack> {
    private final List<Entry> entries = new ArrayList<>();

    private static final class Entry {
        StoredItems<Integer> stored = new StoredItems<>();
        ItemStack item;
        Entry(ItemStack item) { this.item = item; }
    }

    public static StoredItemList load(DataKey root) {
        StoredItemList result = new StoredItemList();
        Object raw = root.getRaw("");
        if (raw instanceof List<?> list) {
            for (Object child : list) {
                if (child instanceof ItemStack stack) { result.add(stack); continue; }
                DataKey key = new MemoryDataKey().getRelative("item"); key.setRaw("", child); result.loadEntry(key);
            }
        } else {
            for (DataKey child : root.getSubKeys()) result.loadEntry(child);
        }
        return result;
    }

    private void loadEntry(DataKey key) {
        Entry entry = new Entry(null);
        entry.item = entry.stored.load(0, key);
        if (entry.item != null || entry.stored.contains(0)) entries.add(entry);
    }

    public static boolean hasUnavailable(List<ItemStack> items) {
        return items instanceof StoredItemList stored && stored.entries.stream().anyMatch(entry -> entry.stored.contains(0));
    }

    /** Encodes the entire replacement before changing the saved list, including original unavailable entries. */
    public static void save(List<ItemStack> items, DataKey root) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            DataKey key = new MemoryDataKey().getRelative(Integer.toString(i));
            if (items instanceof StoredItemList stored) {
                Entry entry = stored.entries.get(i); entry.stored.save(0, key, entry.item);
            } else ItemStorage.saveItem(key, items.get(i));
            if (key.keyExists("")) result.put(Integer.toString(i), key.getRaw(""));
        }
        if (result.isEmpty()) root.removeKey(""); else root.setRaw("", result);
    }

    @Override public ItemStack get(int index) { return entries.get(index).item; }
    @Override public int size() { return entries.size(); }
    @Override public ItemStack set(int index, ItemStack item) { return entries.set(index, new Entry(item)).item; }
    @Override public void add(int index, ItemStack item) { entries.add(index, new Entry(item)); modCount++; }
    @Override public ItemStack remove(int index) { modCount++; return entries.remove(index).item; }

    public StoredItemList copy() {
        StoredItemList result = new StoredItemList();
        for (Entry entry : entries) {
            Entry copy = new Entry(entry.item == null ? null : entry.item.copy());
            copy.stored = entry.stored.copy(); result.entries.add(copy);
        }
        return result;
    }
}
