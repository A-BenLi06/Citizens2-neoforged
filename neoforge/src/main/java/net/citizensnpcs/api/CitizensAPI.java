package net.citizensnpcs.api;

import java.io.File;

import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCDataStore;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.TraitFactory;
import net.citizensnpcs.api.util.schedulers.SchedulerAdapter;
import net.citizensnpcs.api.util.schedulers.Schedulers;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Static entry point to the Citizens API.
 * <p>
 * Upstream additionally exposes {@code getPlugin()} and a {@code registerEvents(Listener)} helper, both of which are
 * Bukkit-shaped: there is no plugin object here, and NeoForge listeners register themselves against
 * {@link NeoForge#EVENT_BUS} directly. Accessors for subsystems that are not ported yet (commands, templates,
 * behaviours, expressions, chunk cache, NPC selector) are added back as those packages land.
 */
public final class CitizensAPI {
    private CitizensAPI() {
    }

    /**
     * Creates a new <em>anonymous</em> {@link NPCRegistry} with its own set of {@link NPC}s. This is not stored by the
     * Citizens mod.
     *
     * @param store
     *            The {@link NPCDataStore} to use with the registry
     * @return A new anonymous NPCRegistry that is not accessible via {@link #getNamedNPCRegistry(String)}
     */
    public static NPCRegistry createAnonymousNPCRegistry(NPCDataStore store) {
        return getImplementation().createAnonymousNPCRegistry(store);
    }

    /**
     * Creates a new {@link NPCRegistry} with its own set of {@link NPC}s that does not save to disk.
     */
    public static NPCRegistry createInMemoryNPCRegistry(String name) {
        return getImplementation().createNamedNPCRegistry(name, new MemoryNPCDataStore());
    }

    /**
     * Creates a new {@link NPCRegistry} with its own set of {@link NPC}s, retrievable via
     * {@link #getNamedNPCRegistry(String)}.
     */
    public static NPCRegistry createNamedNPCRegistry(String name, NPCDataStore store) {
        return getImplementation().createNamedNPCRegistry(name, store);
    }

    private static CitizensPlugin getImplementation() {
        CitizensPlugin implementation = instance;
        if (implementation == null)
            throw new IllegalStateException("Citizens is not enabled");
        return implementation;
    }

    /**
     * @return the {@code config/citizens} directory, where saves and templates live
     */
    public static File getDataFolder() {
        return getImplementation().getDataFolder();
    }

    public static NPCRegistry getNamedNPCRegistry(String name) {
        return getImplementation().getNamedNPCRegistry(name);
    }

    public static Iterable<NPCRegistry> getNPCRegistries() {
        return getImplementation().getNPCRegistries();
    }

    /**
     * @return the selector tracking which NPC each command sender has chosen
     */
    public static net.citizensnpcs.api.npc.NPCSelector getDefaultNPCSelector() {
        return getImplementation().getDefaultNPCSelector();
    }

    /**
     * @return The default {@link NPCRegistry}
     */
    public static NPCRegistry getNPCRegistry() {
        return getImplementation().getNPCRegistry();
    }

    public static ClassLoader getOwningClassLoader() {
        return getImplementation().getOwningClassLoader();
    }

    /**
     * @return The task scheduler. Unlike the other accessors this does not require the implementation to be set, only
     *         a running server, so that low-level utilities can schedule work during startup.
     */
    public static SchedulerAdapter getScheduler() {
        return Schedulers.get();
    }

    public static NPCRegistry getTemporaryNPCRegistry() {
        return getImplementation().getTemporaryNPCRegistry();
    }

    /**
     * @return the registry of expression engines
     */
    public static net.citizensnpcs.api.expr.ExpressionRegistry getExpressionRegistry() {
        return getImplementation().getExpressionRegistry();
    }

    /**
     * @return the registry of behaviours a behaviour tree can be built out of
     */
    public static net.citizensnpcs.api.ai.tree.BehaviorRegistry getBehaviorRegistry() {
        return getImplementation().getBehaviorRegistry();
    }

    public static net.citizensnpcs.api.npc.templates.TemplateRegistry getTemplateRegistry() {
        return getImplementation().getTemplateRegistry();
    }

    public static TraitFactory getTraitFactory() {
        return getImplementation().getTraitFactory();
    }

    /**
     * @return whether the implementation has been set, i.e. whether Citizens is enabled
     */
    public static boolean hasImplementation() {
        return instance != null;
    }

    public static void removeNamedNPCRegistry(String name) {
        getImplementation().removeNamedNPCRegistry(name);
    }

    public static void setDefaultNPCDataStore(NPCDataStore store) {
        getImplementation().setDefaultNPCDataStore(store);
    }

    /**
     * Sets the active implementation. Called by the mod on server start, and with null on shutdown.
     */
    public static void setImplementation(CitizensPlugin implementation) {
        // null means "clearing on shutdown", not "another implementation took over" - firing the changed hook there
        // makes a normal stop look like a hostile replacement, which tells Citizens to skip saving
        if (implementation != null && instance != null && instance != implementation) {
            instance.onImplementationChanged();
        }
        instance = implementation;
    }

    private static volatile CitizensPlugin instance = null;
}
