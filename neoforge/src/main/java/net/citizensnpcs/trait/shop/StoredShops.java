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
    private boolean sourceAvailable;
    private long sourceGeneration;

    public StoredShops(YamlStorage storage) {
        this((Storage) storage);
    }

    private StoredShops(Storage storage) {
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
        prepareLoad().publish();
    }

    /** Decode into fresh maps so a failed child never mutates current shops or drops a stored trade. */
    public PreparedLoad prepareLoad() {
        requireSource();
        try {
            StoredShops candidate = new StoredShops(storage);
            PersistenceLoader.loadStrict(candidate, storage.getKey("").copyWithRoot());
            return new PreparedLoad(candidate, sourceGeneration);
        } catch (RuntimeException | Error failure) {
            sourceAvailable = false;
            throw failure;
        }
    }

    public final class PreparedLoad {
        private final StoredShops candidate;
        private final long generation;

        private PreparedLoad(StoredShops candidate, long generation) {
            this.candidate = candidate;
            this.generation = generation;
        }

        public void publish() {
            requireSource();
            if (generation != sourceGeneration) throw new IllegalStateException("Shop source changed after preparation");
            globalShops = candidate.globalShops;
            npcShops = candidate.npcShops;
            sourceGeneration++;
        }
    }

    public boolean loadFromDisk() {
        sourceGeneration++;
        sourceAvailable = false;
        sourceAvailable = storage.load();
        return sourceAvailable;
    }

    public void saveToDisk() {
        requireSource();
        storage.saveAsync();
    }

    public void saveToDiskImmediate() {
        requireSource();
        storage.save();
    }

    public void storeShops() {
        requireSource();
        storage.getKey("").writeAtomically(key -> PersistenceLoader.save(this, key));
        sourceGeneration++;
    }

    private void requireSource() {
        if (!sourceAvailable) throw new IllegalStateException("Shop storage has not been loaded successfully");
    }
}
