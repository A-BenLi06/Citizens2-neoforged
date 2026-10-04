package net.citizensnpcs.api.npc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;

import net.citizensnpcs.api.trait.trait.MobType;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.Storage;
import net.minecraft.world.entity.EntityType;

public class SimpleNPCDataStore implements NPCDataStore {
    private final Storage root;
    private final Set<Integer> restoringIds = new HashSet<>();
    private boolean restoring;
    private boolean saving;

    public SimpleNPCDataStore(Storage saves) {
        root = saves;
    }

    @Override
    public void clearData(NPC npc) {
        requireNotSaving();
        // Failed creation/load cleanup must retire the runtime object without deleting its recovery source.
        if (restoringIds.contains(npc.getId())) return;
        for (DataKey key : records().getOrDefault(npc.getId(), List.of())) key.removeKey("");
    }

    @Override
    public void clearTraitData(Iterable<String> traitNames) {
        requireNotSaving();
        for (DataKey key : root.getKey("npc").getSubKeys()) {
            Set<String> storedNames = Splitter.on(',').splitToStream(key.getString("traitnames"))
                    .collect(Collectors.toSet());
            DataKey traits = key.getRelative("traits");
            for (String trait : traitNames) {
                trait = trait.toLowerCase(Locale.ROOT);
                traits.removeKey(trait);
                storedNames.remove(trait);
            }
            key.setString("traitnames", Joiner.on(',').join(storedNames));
        }
    }

    @Override
    public int createUniqueNPCId(NPCRegistry registry) {
        requireNotSaving();
        DataKey key = root.getKey("");
        Integer previous = NPCIdAllocator.parse(key.getRaw("last-created-npc-id"));
        int newId = NPCIdAllocator.next(registry, previous == null ? -1 : previous, records().keySet());
        key.setInt("last-created-npc-id", newId);
        return newId;
    }

    @Override
    public void loadInto(NPCRegistry registry) {
        requireNotSaving();
        if (restoring) throw new IllegalStateException("NPC restoration is already in progress");
        restoring = true;
        try {
            for (var entry : records().entrySet()) {
                int id = entry.getKey();
                if (entry.getValue().size() != 1) {
                    Messaging.severeTr(LOAD_FAILED, id, "Multiple stored keys identify this NPC ID");
                    continue;
                }
                // Loaders may set defaults or normalize values. Publish only through a later explicit save.
                DataKey key = entry.getValue().getFirst().copyWithRoot();
                NPC npc = null;
                restoringIds.add(id);
                try {
                    if (!key.keyExists("name")) {
                        Messaging.logTr(LOAD_NAME_NOT_FOUND, id);
                        continue;
                    }
                    if (key.getString("traitnames").isBlank()) throw new IllegalArgumentException("Missing trait names");
                    String unparsedEntityType = key.getRelative("traits").getString("type", "PLAYER");
                    EntityType<?> type = MobType.match(unparsedEntityType);
                    if (type == null) {
                        Messaging.logTr(LOAD_UNKNOWN_NPC_TYPE, unparsedEntityType);
                        continue;
                    }
                    UUID uuid = !key.getString("uuid").isEmpty() ? UUID.fromString(key.getString("uuid")) : UUID.randomUUID();
                    if (registry.getById(id) != null || registry.getByUniqueId(uuid) != null)
                        throw new IllegalArgumentException("NPC ID or UUID is already registered");
                    key.getRelative("traits").setString("type", EntityType.getKey(type).toString());
                    npc = registry.createNPC(type, uuid, id, key.getString("name"));
                    npc.load(key);
                    if (registry.getById(id) != npc || registry.getByUniqueId(uuid) != npc)
                        throw new IllegalStateException("NPC restoration was superseded");
                } catch (RuntimeException failure) {
                    if (npc != null) {
                        try { npc.destroy(); }
                        catch (RuntimeException cleanup) {
                            if (cleanup != failure) failure.addSuppressed(cleanup);
                            Messaging.severe("Could not clean up failed NPC restoration", id, cleanup.getMessage());
                        }
                    }
                    Messaging.severeTr(LOAD_FAILED, id, failure.getMessage());
                } finally {
                    restoringIds.remove(id);
                }
            }
        } finally {
            restoring = false;
        }
    }

    @Override
    public void reloadFromSource() {
        requireNotSaving();
        if (restoring) throw new IllegalStateException("Cannot reload NPC storage during restoration");
        DataKey previous = root.getKey("").copy();
        try {
            if (!root.load()) throw new IllegalStateException("Could not reload NPC storage: " + root);
            records();
        } catch (RuntimeException failure) {
            root.getKey("").setRaw("", previous.getValuesDeep());
            throw failure;
        }
    }

    @Override
    public void saveToDisk() {
        requireNotSaving();
        root.saveAsync();
    }

    @Override
    public void saveToDiskImmediate() {
        requireNotSaving();
        root.save();
    }

    @Override
    public void store(NPC npc) {
        beginSave();
        try {
            NPCRegistry registry = npc.getOwningRegistry();
            requireRegistered(npc, registry);
            DataKey staged = root.getKey("").copy();
            store(npc, staged);
            requireRegistered(npc, registry);
            root.getKey("").setRaw("", staged.getValuesDeep());
        } finally {
            saving = false;
        }
    }

    @Override
    public void storeAll(NPCRegistry registry) {
        beginSave();
        try {
            List<NPC> snapshot = new ArrayList<>();
            registry.forEach(npc -> { requireRegistered(npc, registry); snapshot.add(npc); });
            DataKey staged = root.getKey("").copy();
            for (NPC npc : snapshot) {
                requireRegistered(npc, registry);
                store(npc, staged);
            }

            // Callbacks can replace an NPC with the same ID or UUID, or mutate the registry directly. Never publish
            // an obsolete snapshot; those external live mutations themselves are outside this store's transaction.
            Set<NPC> remaining = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            remaining.addAll(snapshot);
            for (NPC npc : registry) {
                requireRegistered(npc, registry);
                if (!remaining.remove(npc)) throw new IllegalStateException("NPC registry changed during serialization");
            }
            if (!remaining.isEmpty()) throw new IllegalStateException("NPC registry changed during serialization");
            root.getKey("").setRaw("", staged.getValuesDeep());
        } finally {
            saving = false;
        }
    }

    private void beginSave() {
        requireNotSaving();
        if (restoring) throw new IllegalStateException("Cannot save NPC storage during restoration");
        saving = true;
    }

    private void requireNotSaving() {
        if (saving) throw new IllegalStateException("NPC storage serialization is already in progress");
    }

    private static void requireRegistered(NPC npc, NPCRegistry registry) {
        if (registry.getById(npc.getId()) != npc || registry.getByUniqueId(npc.getUniqueId()) != npc)
            throw new IllegalStateException("NPC registration changed during serialization: " + npc.getId());
    }

    private static void store(NPC npc, DataKey staged) {
        List<DataKey> keys = records(staged.getRelative("npc")).getOrDefault(npc.getId(), List.of());
        if (keys.size() > 1) throw new IllegalStateException("Ambiguous stored NPC ID: " + npc.getId());
        npc.save(keys.isEmpty() ? staged.getRelative("npc." + npc.getId()) : keys.getFirst());
    }

    public static NPCDataStore create(Storage storage) {
        return new SimpleNPCDataStore(storage);
    }

    private Map<Integer, List<DataKey>> records() {
        return records(root.getKey("npc"));
    }

    private static Map<Integer, List<DataKey>> records(DataKey section) {
        if (section.getRaw("") != null && !(section.getRaw("") instanceof Map<?, ?>))
            throw new IllegalStateException("NPC storage must contain a mapping of IDs to definitions");
        Map<Integer, List<DataKey>> records = new TreeMap<>();
        for (DataKey key : section.getSubKeys()) {
            Integer id = NPCIdAllocator.parse(key.name());
            if (id != null) records.computeIfAbsent(id, unused -> new ArrayList<>()).add(key);
        }
        return records;
    }

    private static final String LOAD_FAILED = "citizens.notifications.npc-load-failed";

    private static final String LOAD_NAME_NOT_FOUND = "citizens.notifications.npc-name-not-found";

    private static final String LOAD_UNKNOWN_NPC_TYPE = "citizens.notifications.unknown-npc-type";
}
