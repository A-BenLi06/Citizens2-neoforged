package net.citizensnpcs.api.npc;

public interface NPCDataStore {
    /**
     * Clears all data about the given {@link NPC} from storage. Called when the NPC is removed.
     *
     * @param npc
     *            The NPC to clear data from
     */
    void clearData(NPC npc);

    /**
     * Clears all trait data from all NPCs from the given list of names.
     */
    void clearTraitData(Iterable<String> traitNames);

    /**
     * @param registry
     *            The registry for the unique ID.
     * @return An ID for a new NPC to identify them uniquely
     */
    int createUniqueNPCId(NPCRegistry registry);

    /**
     * Loads NPCs from disk into the given {@link NPCRegistry}. The registry should be cleared before this is called.
     * During default-store replacement this registry is provisional: global lookups still refer to the previous
     * registry. Populate the supplied registry synchronously; throw on an unrecoverable load failure. Native NPC
     * loading schedules respawns for a later tick, after successful publication. Callers may retire all candidates
     * after a failure without clearing their source records. External callback/backend side effects are not rolled back.
     *
     * @param registry
     *            The NPCRegistry to load NPCs into
     */
    void loadInto(NPCRegistry registry);

    /**
     * Reloads the data store from source (such as a file on disk). A failed read must preserve the previous snapshot
     * and throw a runtime exception so the caller can keep its current NPCs alive.
     */
    void reloadFromSource();

    /**
     * Notifies the data store to save all stored data to disk. May be asynchronous.
     */
    void saveToDisk();

    /**
     * Notifies the data store to save all stored data to disk <em>immediately</em>. Must not be asynchronous.
     * Implementations must throw when persistence fails so a caller can reject a store replacement before retiring
     * the current NPCs. Backend writes already performed before failure cannot be rolled back by the caller.
     */
    void saveToDiskImmediate();

    /**
     * Stores the given {@link NPC} into memory or to a disk representation.
     *
     * @param npc
     *            The NPC to store
     */
    void store(NPC npc);

    /**
     * Stores all {@link NPC}s in the given {@link NPCRegistry} to disk.
     *
     * @param registry
     *            The registry to store NPCs from
     */
    void storeAll(NPCRegistry registry);
}
