package net.citizensnpcs.trait.waypoint;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.editor.Editor;
import net.citizensnpcs.util.Messages;
import net.citizensnpcs.util.StringHelper;
import net.minecraft.commands.CommandSourceStack;

/**
 * Holds the NPC's route and which kind of route it is.
 * <p>
 * Upstream stores each provider as a reflective {@code Constructor} and calls {@code newInstance()}. A
 * {@link Supplier} is used instead — checked at compile time, and no requirement that the provider have a public
 * no-argument constructor.
 */
@TraitName("waypoints")
public class Waypoints extends Trait {
    private WaypointProvider provider = new LinearWaypointProvider();
    private String providerName = "linear";
    private DataKey unavailable;
    private long revision;
    private boolean removed, despawning;
    private boolean providerStopped;
    private net.minecraft.world.entity.Entity runningEntity;

    public Waypoints() {
        super("waypoints");
    }

    public void describeProviders(CommandSourceStack sender) {
        Messaging.sendTr(sender, Messages.AVAILABLE_WAYPOINT_PROVIDERS);
        for (String name : PROVIDERS.keySet()) {
            Messaging.send(sender, "    - " + StringHelper.wrap(name));
        }
    }

    /** @return the current provider, which may be null part-way through loading */
    public WaypointProvider getCurrentProvider() {
        return provider;
    }

    public String getCurrentProviderName() {
        return providerName;
    }

    public Editor getEditor(CommandSourceStack sender, CommandContext args) {
        return provider == null ? null : provider.createEditor(sender, args);
    }

    @Override public void onAttach() { if (provider != null) provider.onAttach(npc); }

    public long getRevision() { return revision; }

    static boolean owns(net.citizensnpcs.api.npc.NPC npc, WaypointProvider provider) {
        if (npc == null) return false;
        Waypoints trait = npc.getTraitNullable(Waypoints.class);
        return trait != null && trait.isCurrent() && trait.provider == provider;
    }

    public boolean isCurrent() {
        return !removed && (npc == null || npc.getTraitNullable(Waypoints.class) == this
                && npc.getOwningRegistry().getByUniqueId(npc.getUniqueId()) == npc
                && npc.getOwningRegistry().getById(npc.getId()) == npc);
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        long before = revision;
        String name = key.getString("provider", "linear");
        WaypointProvider candidate = create(name);
        if (candidate != null) {
            try { PersistenceLoader.loadStrict(candidate, key.getRelative(name)); }
            catch (RuntimeException failure) {
                Messaging.severe("Unable to load waypoint provider", name, failure);
                candidate = null;
            }
        }
        install(candidate, name, candidate == null && !name.isEmpty() ? key.copy() : null, before);
    }

    @Override
    public void onSpawn() {
        if (isCurrent() && !despawning && provider != null && npc != null && npc.isSpawned()
                && (providerStopped || runningEntity != npc.getEntity())) {
            providerStopped = false;
            runningEntity = npc.getEntity();
            provider.onSpawn(npc);
        }
    }

    @Override public void onDespawn() {
        despawning = true;
        WaypointProvider previous = provider;
        boolean release = !providerStopped;
        providerStopped = true;
        runningEntity = null;
        try {
            WaypointExecution.cancelWaiting(npc, previous);
            if (release && previous != null) previous.onRemove();
        } finally { despawning = false; }
    }

    @Override public void onRemove() {
        if (removed) return;
        removed = true;
        revision++;
        WaypointProvider previous = provider;
        boolean release = !providerStopped;
        providerStopped = true;
        runningEntity = null;
        provider = null;
        WaypointExecution.cancel(npc, previous);
        if (release && previous != null) previous.onRemove();
    }

    @Override
    public void save(DataKey key) {
        if (unavailable != null) {
            key.setRaw("", unavailable.copy().getRaw(""));
            return;
        }
        if (provider != null) PersistenceLoader.save(provider, key.getRelative(providerName));
        key.setString("provider", providerName);
    }

    /**
     * @param name
     *            a name registered with {@link #registerWaypointProvider}, or null/empty to remove the route entirely
     * @return whether the name was known
     */
    public boolean setWaypointProvider(String name) {
        if (!isCurrent()) return false;
        long before = revision;
        String key = name == null ? "" : name.toLowerCase(Locale.ROOT);
        WaypointProvider candidate = key.isEmpty() ? null : create(key);
        if (!key.isEmpty() && candidate == null) return false;
        return install(candidate, key, null, before);
    }

    private boolean install(WaypointProvider candidate, String name, DataKey saved, long expected) {
        if (revision != expected || !isCurrent()) return false;
        WaypointProvider previous = provider;
        boolean release = !providerStopped;
        provider = candidate;
        providerStopped = false;
        runningEntity = null;
        providerName = name;
        unavailable = saved;
        long installed = ++revision;
        try {
            WaypointExecution.cancel(npc, previous);
            if (release && previous != null && previous != candidate) previous.onRemove();
        } finally {
            if (isCurrent() && revision == installed && candidate != null && npc != null) {
                candidate.onAttach(npc);
                if (isCurrent() && revision == installed) onSpawn();
            }
        }
        return isCurrent() && revision == installed && provider == candidate;
    }

    private static WaypointProvider create(String name) {
        Supplier<WaypointProvider> factory = PROVIDERS.get(name == null ? "" : name.toLowerCase(Locale.ROOT));
        return factory == null ? null : factory.get();
    }

    public static void registerWaypointProvider(Supplier<WaypointProvider> factory, String name) {
        PROVIDERS.put(name.toLowerCase(Locale.ROOT), factory);
    }

    private static final Map<String, Supplier<WaypointProvider>> PROVIDERS = new LinkedHashMap<>();

    static {
        registerWaypointProvider(LinearWaypointProvider::new, "linear");
        registerWaypointProvider(WanderWaypointProvider::new, "wander");
        registerWaypointProvider(GuidedWaypointProvider::new, "guided");
    }
}
