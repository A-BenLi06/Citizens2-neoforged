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

    @Override
    public void load(DataKey key) throws NPCLoadException {
        providerName = key.getString("provider", "linear");
        provider = create(providerName);
        if (provider == null)
            return;
        PersistenceLoader.load(provider, key.getRelative(providerName));
        if (npc != null) {
            provider.onSpawn(npc);
        }
    }

    @Override
    public void onSpawn() {
        if (provider != null) {
            provider.onSpawn(npc);
        }
    }

    @Override
    public void save(DataKey key) {
        if (provider == null)
            return;
        PersistenceLoader.save(provider, key.getRelative(providerName));
        key.setString("provider", providerName);
    }

    /**
     * @param name
     *            a name registered with {@link #registerWaypointProvider}, or null/empty to remove the route entirely
     * @return whether the name was known
     */
    public boolean setWaypointProvider(String name) {
        if (provider != null) {
            provider.onRemove();
        }
        provider = null;
        providerName = null;
        if (name == null || name.isEmpty())
            return true;
        String key = name.toLowerCase(Locale.ROOT);
        provider = create(key);
        if (provider == null)
            return false;
        providerName = key;
        if (npc != null && npc.isSpawned()) {
            provider.onSpawn(npc);
        }
        return true;
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
