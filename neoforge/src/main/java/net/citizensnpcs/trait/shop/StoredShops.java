package net.citizensnpcs.trait.shop;

import java.util.HashMap;
import java.util.Map;

import com.google.common.primitives.Ints;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.Storage;
import net.citizensnpcs.api.util.YamlStorage;

/**
 * Every shop on the server, in its own file: the ones attached to an NPC (keyed by the NPC's UUID) and the named global
 * ones that any NPC or command can open.
 */
public class StoredShops {
    @Persist(value = "global", reify = true)
    public Map<String, NPCShop> globalShops = new HashMap<>();
    @Persist(value = "npc", reify = true)
    public Map<String, NPCShop> npcShops = new HashMap<>();
    private final Storage storage;

    public StoredShops(YamlStorage storage) {
        this.storage = storage;
    }

    public NPCShop addNamedShop(String name) {
        return globalShops.computeIfAbsent(name, NPCShop::new);
    }

    public void deleteShop(NPCShop shop) {
        Messaging.idebug(() -> "Deleting shop " + shop.getName());
        if (npcShops.containsKey(shop.getName())) {
            npcShops.remove(shop.getName());
        } else {
            globalShops.remove(shop.getName());
        }
    }

    public NPCShop getGlobalShop(String name) {
        return globalShops.get(name);
    }

    /**
     * Finds a shop by name, by NPC id, or by NPC UUID — so {@code /npc shop open 5} and the NPC's own shop are the same
     * thing.
     */
    public NPCShop getShop(String name) {
        Integer id = Ints.tryParse(name);
        if (id != null && CitizensAPI.getNPCRegistry().getById(id) != null) {
            name = CitizensAPI.getNPCRegistry().getById(id).getUniqueId().toString();
        }
        NPCShop shop = npcShops.get(name);
        return shop == null ? getGlobalShop(name) : shop;
    }

    public void load() {
        PersistenceLoader.load(this, storage.getKey(""));
    }

    public boolean loadFromDisk() {
        return storage.load();
    }

    public void saveToDisk() {
        storage.saveAsync();
    }

    public void saveToDiskImmediate() {
        storage.save();
    }

    public void storeShops() {
        PersistenceLoader.save(this, storage.getKey(""));
    }
}
