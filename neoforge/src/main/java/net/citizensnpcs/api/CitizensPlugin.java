package net.citizensnpcs.api;

import net.citizensnpcs.api.npc.NPCDataStore;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.TraitFactory;

/**
 * The contract the mod implementation fulfils for the API.
 * <p>
 * Upstream extends Bukkit's {@code Plugin}; there is no NeoForge equivalent, so this stands alone.
 * <p>
 * Members reaching into subsystems that are not ported yet — {@code getCommandManager},
 * {@code getAsyncChunkCache}, {@code getLocationLookup}, {@code getDefaultNPCSelector}, {@code getNMSHelper} — are
 * added back as their packages land, so that the interface never declares a type that does not exist.
 */
public interface CitizensPlugin {
    /**
     * @return the registry of templates loaded from the {@code templates/} folder
     */
    public net.citizensnpcs.api.npc.templates.TemplateRegistry getTemplateRegistry();

    /**
     * @param store
     *            The data store of the registry
     * @return A new anonymous NPCRegistry that is not accessible via {@link #getNamedNPCRegistry(String)}
     */
    public NPCRegistry createAnonymousNPCRegistry(NPCDataStore store);

    /**
     * @return the {@code config/citizens} directory
     */
    public java.io.File getDataFolder();

    /**
     * @return the registry of expression engines, used by shop conditions and behaviour trees
     */
    public net.citizensnpcs.api.expr.ExpressionRegistry getExpressionRegistry();

    /**
     * @return the registry of behaviours a behaviour tree can be built out of
     */
    public net.citizensnpcs.api.ai.tree.BehaviorRegistry getBehaviorRegistry();

    /**
     * @param name
     *            The registry name
     * @param store
     *            The data store for the registry
     * @return A new NPCRegistry, that can also be retrieved via {@link #getNamedNPCRegistry(String)}
     */
    public NPCRegistry createNamedNPCRegistry(String name, NPCDataStore store);

    /**
     * @param name
     *            The registry name
     * @return A NPCRegistry previously created via {@link #createNamedNPCRegistry(String, NPCDataStore)}, or null if
     *         not found
     */
    public NPCRegistry getNamedNPCRegistry(String name);

    /**
     * Get all registered {@link NPCRegistry}s.
     */
    public Iterable<NPCRegistry> getNPCRegistries();

    /**
     * Gets the <em>default</em> {@link NPCRegistry}.
     *
     * @return The NPC registry
     */
    public NPCRegistry getNPCRegistry();

    /**  the selector tracking which NPC each command sender has chosen */
    public net.citizensnpcs.api.npc.NPCSelector getDefaultNPCSelector();

    public ClassLoader getOwningClassLoader();

    public NPCRegistry getTemporaryNPCRegistry();

    /**
     * Gets the TraitFactory.
     *
     * @return Citizens trait factory
     */
    public TraitFactory getTraitFactory();

    /**
     * Called when the current Citizens implementation is changed
     */
    public void onImplementationChanged();

    /**
     * Removes the named NPCRegistry with the given name.
     */
    public void removeNamedNPCRegistry(String name);

    /**
     * Sets the default NPC data store. Should be set during startup.
     *
     * @param store
     *            The new default store
     */
    public void setDefaultNPCDataStore(NPCDataStore store);
}
