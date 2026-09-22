package net.citizensnpcs.api.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.world.item.ItemStack;

/**
 * Owner-local definitions for slots with no currently loadable native stack. Empty native reads leave them intact;
 * explicit edits call {@link #clear(Object)}, and a nonempty replacement wins after a successful save. Nothing is
 * inserted into a Minecraft inventory to stand in for an unavailable item. Loading again retries the real providers.
 */
public final class StoredItems<K> {
    private final Map<K, Object> unresolved = new LinkedHashMap<>();

    public ItemStack load(K slot, DataKey root) {
        Object original = copy(root.getRaw(""));
        var result = ItemStorage.readItem(root);
        unresolved.remove(slot);
        if (result.unavailable()) {
            unresolved.put(slot, original);
            Messaging.warn("Retaining unavailable item at " + root.getPath());
        }
        return result.stack();
    }

    public void save(K slot, DataKey root, ItemStack item) {
        if ((item == null || item.isEmpty()) && unresolved.containsKey(slot)) {
            root.setRaw("", copy(unresolved.get(slot)));
        } else if (item == null || item.isEmpty()) {
            root.removeKey("");
        } else {
            ItemStorage.saveItem(root, item);
            unresolved.remove(slot);
        }
    }

    public void clear(K slot) { unresolved.remove(slot); }
    public void clear() { unresolved.clear(); }
    public boolean contains(K slot) { return unresolved.containsKey(slot); }
    public Set<K> keys() { return Set.copyOf(unresolved.keySet()); }

    public StoredItems<K> copy() {
        StoredItems<K> result = new StoredItems<>();
        unresolved.forEach((key, raw) -> result.unresolved.put(key, copy(raw)));
        return result;
    }

    private static Object copy(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<Object, Object> result = new LinkedHashMap<>();
            source.forEach((key, child) -> result.put(key, copy(child)));
            return result;
        }
        if (value instanceof List<?> source) {
            List<Object> result = new ArrayList<>(source.size());
            source.forEach(child -> result.add(copy(child)));
            return result;
        }
        return value;
    }
}
