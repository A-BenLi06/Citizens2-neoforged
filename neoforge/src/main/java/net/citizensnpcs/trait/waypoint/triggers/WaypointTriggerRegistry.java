package net.citizensnpcs.trait.waypoint.triggers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.function.Supplier;

import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.persistence.Persister;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.MemoryDataKey;

/**
 * The known {@link WaypointTrigger} types, and how each is persisted and configured.
 * <p>
 * Upstream keys the prompt side of this map by {@code Class<? extends Prompt>} and instantiates with
 * {@code newInstance()}. A supplier is used instead: it is checked at compile time, and it does not need the prompt class
 * to have a public no-argument constructor.
 */
public class WaypointTriggerRegistry implements Persister<WaypointTrigger> {
    @Override
    public WaypointTrigger create(DataKey root) {
        Class<? extends WaypointTrigger> clazz = TRIGGERS.get(root.getString("type"));
        return clazz == null ? null : PersistenceLoader.loadStrict(clazz, root);
    }

    @Override
    public void save(WaypointTrigger instance, DataKey root) {
        String type = null;
        for (Map.Entry<String, Class<? extends WaypointTrigger>> entry : TRIGGERS.entrySet()) {
            if (entry.getValue() == instance.getClass()) {
                type = entry.getKey();
                break;
            }
        }
        if (type == null) throw new IllegalArgumentException("Unregistered waypoint trigger: " + instance.getClass().getName());
        DataKey candidate = new MemoryDataKey();
        PersistenceLoader.save(instance, candidate);
        candidate.setString("type", type);
        root.setRaw("", candidate.getRaw(""));
    }

    public static void addTrigger(String name, Class<? extends WaypointTrigger> triggerClass,
            Supplier<WaypointTriggerPrompt> promptFactory) {
        TRIGGERS.put(name, triggerClass);
        TRIGGER_PROMPTS.put(name, promptFactory);
    }

    public static String describeValidTriggerNames() {
        return String.join(", ", TRIGGER_PROMPTS.keySet());
    }

    /** @return a fresh configuration prompt for that trigger name, or null when the name is unknown */
    public static WaypointTriggerPrompt getTriggerPromptFrom(String input) {
        Supplier<WaypointTriggerPrompt> factory = TRIGGER_PROMPTS.get(input == null ? "" : input.toLowerCase(Locale.ROOT));
        return factory == null ? null : factory.get();
    }

    private static final Map<String, Supplier<WaypointTriggerPrompt>> TRIGGER_PROMPTS = new LinkedHashMap<>();
    private static final Map<String, Class<? extends WaypointTrigger>> TRIGGERS = new LinkedHashMap<>();

    static {
        addTrigger("animation", AnimationTrigger.class, AnimationTriggerPrompt::new);
        addTrigger("command", CommandTrigger.class, CommandTriggerPrompt::new);
        addTrigger("chat", ChatTrigger.class, ChatTriggerPrompt::new);
        addTrigger("delay", DelayTrigger.class, DelayTriggerPrompt::new);
        addTrigger("teleport", TeleportTrigger.class, TeleportTriggerPrompt::new);
        addTrigger("speed", SpeedTrigger.class, SpeedTriggerPrompt::new);
    }
}
