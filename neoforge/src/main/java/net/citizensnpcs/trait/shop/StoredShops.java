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
    private boolean publishing;
    private boolean closed;

    private java.util.Set<NPCShop> currentShops() {
        var result = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<NPCShop, Boolean>());
        result.addAll(globalShops.values());
        result.addAll(npcShops.values());
        return result;
    }

    public StoredShops(YamlStorage storage) {
        this((Storage) storage);
    }

    private StoredShops(Storage storage) {
        this.storage = storage;
    }

    public NPCShop addNamedShop(String name) {
        requireOpen();
        return globalShops.computeIfAbsent(name, NPCShop::new);
    }

    public NPCShop addNPCShop(String name) {
        requireOpen();
        return npcShops.computeIfAbsent(name, NPCShop::new);
    }

    private void requireOpen() {
        if (closed || publishing) throw new IllegalStateException("Shop sessions are unavailable");
    }

    public void deleteShop(NPCShop shop) {
        Messaging.idebug(() -> "Deleting shop " + shop.getName());
        shop.requireIdle();
        if (npcShops.remove(shop.getName(), shop) || globalShops.remove(shop.getName(), shop)) shop.retire();
    }

    /** Remove only the expected per-NPC binding; another binding may still own the same definition. */
    public void removeNPCShop(String id, NPCShop expected) {
        expected.requireIdle();
        npcShops.remove(id, expected);
        if (npcShops.values().stream().noneMatch(shop -> shop == expected)
                && globalShops.values().stream().noneMatch(shop -> shop == expected)) expected.retire();
    }

    /** Release views and editors before shutdown saving so their final edits are included. */
    public void closeSessions() {
        if (closed) return;
        var current = currentShops();
        current.forEach(NPCShop::requireIdle);
        closed = true;
        Throwable failure = null;
        for (NPCShop shop : current) {
            try { shop.retire(); }
            catch (RuntimeException | Error ex) {
                if (failure == null) failure = ex;
                else if (failure != ex) failure.addSuppressed(ex);
            }
        }
        if (failure instanceof RuntimeException ex) throw ex;
        if (failure instanceof Error ex) throw ex;
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
        requireOpen();
        requireSource();
        NPCShopAction.bootstrap();
        currentShops().forEach(NPCShop::requireIdle);
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
            requireOpen();
            requireSource();
            if (generation != sourceGeneration) throw new IllegalStateException("Shop source changed after preparation");
            var previous = currentShops();
            previous.forEach(NPCShop::requireIdle);
            publishing = true;
            Throwable failure = null;
            try {
                for (NPCShop shop : previous) {
                    try { shop.retire(); }
                    catch (RuntimeException | Error ex) {
                        if (failure == null) failure = ex;
                        else if (failure != ex) failure.addSuppressed(ex);
                    }
                }
                globalShops = candidate.globalShops;
                npcShops = candidate.npcShops;
                sourceGeneration++;
            } finally { publishing = false; }
            if (failure != null) throw new IllegalStateException("Shops restored, but previous sessions could not all close", failure);
        }
    }

    public boolean loadFromDisk() {
        requireOpen();
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
        if (publishing) throw new IllegalStateException("Shop publication is already in progress");
        if (!sourceAvailable) throw new IllegalStateException("Shop storage has not been loaded successfully");
    }
}
