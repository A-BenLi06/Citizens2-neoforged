package net.citizensnpcs.api.npc;

public class MemoryNPCDataStore implements NPCDataStore {
    private int lastID = -1;

    @Override
    public void clearData(NPC npc) {
    }

    @Override
    public void clearTraitData(Iterable<String> traitNames) {
    }

    @Override
    public int createUniqueNPCId(NPCRegistry registry) {
        return lastID = NPCIdAllocator.next(registry, lastID, java.util.Set.of());
    }

    @Override
    public void loadInto(NPCRegistry registry) {
    }

    @Override
    public void reloadFromSource() {
    }

    @Override
    public void saveToDisk() {
    }

    @Override
    public void saveToDiskImmediate() {
    }

    @Override
    public void store(NPC npc) {
    }

    @Override
    public void storeAll(NPCRegistry registry) {
    }
}
