package net.citizensnpcs.audit;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.GsonBuilder;
import com.sun.management.ThreadMXBean;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.npc.SimpleNPCDataStore;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.util.Storage;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class SnapshotCostRuntimeAudit {
    private static final List<NPCRegistry> registries = new ArrayList<>();
    private static boolean done;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        done = true;
        try {
            if (!Files.isRegularFile(Path.of("save-integrity-audit-fixture.txt"))
                    || CitizensAPI.getNPCRegistry().iterator().hasNext()) throw new AssertionError("isolated empty fixture required");
            ThreadMXBean memory = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            if (!memory.isThreadAllocatedMemorySupported()) throw new AssertionError("thread allocation measurement unavailable");
            memory.setThreadAllocatedMemoryEnabled(true);
            List<Map<String, Object>> results = new ArrayList<>();
            for (int count : new int[] { 64, 128, 256, 512 }) {
                MemoryStorage storage = new MemoryStorage();
                SimpleNPCDataStore store = new SimpleNPCDataStore(storage);
                NPCRegistry registry = CitizensAPI.createNamedNPCRegistry("snapshot-cost-" + count, store);
                registries.add(registry);
                storage.key.setString("global.retained.value", "unchanged");
                for (int id = 0; id < count; id++) {
                    NPC npc = registry.createNPC(EntityType.PIG, new UUID(count, id), id, "Snapshot " + id);
                    npc.getOrAddTrait(net.citizensnpcs.api.trait.trait.Spawned.class).setSpawned(false);
                    List<Object> unknown = new ArrayList<>();
                    for (int entry = 0; entry < 8; entry++) unknown.add(new LinkedHashMap<>(Map.of(
                            "literal.key", "retained", "coordinates", new ArrayList<>(List.of(1, 2, 3)),
                            "nested", new LinkedHashMap<>(Map.of("value", entry)))));
                    storage.key.setRaw("npc." + id + ".unavailable-provider", unknown);
                }
                store.storeAll(registry);
                NPC first = registry.getById(0);
                for (String mode : List.of("single", "batch")) {
                    Runnable operation = mode.equals("single") ? () -> first.save(storage.getKey("npc.0")) : () -> store.storeAll(registry);
                    for (int warmup = 0; warmup < 2; warmup++) operation.run();
                    List<Long> nanos = new ArrayList<>(), allocated = new ArrayList<>();
                    for (int sample = 0; sample < 3; sample++) {
                        long bytes = memory.getThreadAllocatedBytes(Thread.currentThread().threadId());
                        long started = System.nanoTime();
                        operation.run();
                        nanos.add(System.nanoTime() - started);
                        allocated.add(memory.getThreadAllocatedBytes(Thread.currentThread().threadId()) - bytes);
                    }
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("npcs", count); result.put("operation", mode); result.put("nanoseconds", nanos); result.put("allocated_bytes", allocated);
                    results.add(result);
                    LoggerFactory.getLogger("citizens").info("[SNAPSHOTCOSTAUDIT] RESULT {}", result);
                }
                if (!storage.key.getString("npc." + (count - 1) + ".name").equals("Snapshot " + (count - 1))
                        || !storage.key.getString("global.retained.value").equals("unchanged")
                        || ((List<?>) storage.key.getRaw("npc.0.unavailable-provider")).size() != 8)
                    throw new AssertionError("snapshot contents changed");
            }
            Files.writeString(Path.of("snapshot-cost-results.json"), new GsonBuilder().setPrettyPrinting().create().toJson(results));
            LoggerFactory.getLogger("citizens").info("[SNAPSHOTCOSTAUDIT] COMPLETE {} measurements", results.size());
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[SNAPSHOTCOSTAUDIT] FAILED", failure);
        } finally {
            try {
                for (NPCRegistry registry : registries) {
                    for (NPC npc : registry.sorted()) npc.destroy();
                    CitizensAPI.removeNamedNPCRegistry(registry.getName());
                }
            } catch (Throwable failure) {
                LoggerFactory.getLogger("citizens").error("[SNAPSHOTCOSTAUDIT] FAILED cleanup", failure);
            }
            event.getServer().halt(false);
        }
    }

    private static final class MemoryStorage implements Storage {
        final MemoryDataKey key = new MemoryDataKey();
        @Override public DataKey getKey(String path) { return key.getRelative(path); }
        @Override public boolean load() { return true; }
        @Override public void save() { }
        @Override public void saveAsync() { }
    }
}
