package net.citizensnpcs.api.npc.templates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.citizensnpcs.api.event.NPCDamageEvent;
import net.citizensnpcs.api.event.NPCDeathEvent;
import net.citizensnpcs.api.event.NPCEvent;
import net.citizensnpcs.api.event.NPCLeftClickEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.event.NPCSpawnEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.DataKey;
import net.minecraft.resources.ResourceLocation;

/**
 * Saves the applied template command definitions with the NPC. Restoration only installs event subscriptions:
 * it never reapplies traits, YAML replacements or {@code on_template_apply} commands. Runtime-only extension
 * callbacks remain owned by their provider and are not serialized as executable Java objects.
 */
public final class TemplateEventBindings {
    static final Map<String, Class<? extends NPCEvent>> EVENT_TYPES = Map.of(
            "on_spawn", NPCSpawnEvent.class, "on_death", NPCDeathEvent.class,
            "on_damage", NPCDamageEvent.class, "on_left_click", NPCLeftClickEvent.class,
            "on_right_click", NPCRightClickEvent.class);

    private TemplateEventBindings() { }

    static String eventName(Class<? extends NPCEvent> eventClass) {
        return EVENT_TYPES.entrySet().stream().filter(entry -> entry.getValue() == eventClass)
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    public static void save(NPC npc, DataKey root) {
        var templates = new ArrayList<Map<String, Object>>();
        CommandEventAction.snapshotSubscriptions(npc).forEach((key, commands) ->
                templates.add(Map.of("key", key.toString(), "commands", commands)));
        root.setRaw("", Map.of("version", 1, "templates", templates));
    }

    /** Decode everything before changing the NPC or registering listeners. Missing legacy data leaves runtime bindings intact. */
    public static Consumer<NPC> prepareLoad(DataKey root) {
        if (!root.keyExists()) return npc -> { };
        if (!(root.getRaw("") instanceof Map<?, ?> record) || !Integer.valueOf(1).equals(record.get("version"))
                || !record.keySet().equals(java.util.Set.of("version", "templates")))
            throw new IllegalArgumentException("Unsupported template event snapshot");

        var prepared = new LinkedHashMap<ResourceLocation, List<CommandEventAction>>();
        for (Object raw : list(record.get("templates"))) {
            if (!(raw instanceof Map<?, ?> template) || !template.keySet().equals(java.util.Set.of("key", "commands"))
                    || !(template.get("key") instanceof String name)
                    || !(template.get("commands") instanceof Map<?, ?> commands))
                throw new IllegalArgumentException("Invalid template event record");
            ResourceLocation key = ResourceLocation.tryParse(name);
            if (key == null || !key.toString().equals(name) || prepared.containsKey(key))
                throw new IllegalArgumentException("Invalid or duplicate template event key: " + name);
            var actions = new ArrayList<CommandEventAction>();
            for (var entry : commands.entrySet()) {
                Class<? extends NPCEvent> type = entry.getKey() instanceof String event ? EVENT_TYPES.get(event) : null;
                if (type == null) throw new IllegalArgumentException("Unknown template event: " + entry.getKey());
                var lines = new ArrayList<String>();
                for (Object line : list(entry.getValue())) {
                    if (!(line instanceof String command))
                        throw new IllegalArgumentException("Template event commands must be strings");
                    lines.add(command);
                }
                var action = new CommandEventAction(type, new CommandListExecutor(lines));
                action.bindTemplate(key);
                actions.add(action);
            }
            prepared.put(key, List.copyOf(actions));
        }
        return npc -> {
            var replacements = new LinkedHashMap<>(prepared);
            CommandEventAction.snapshotSubscriptions(npc).keySet().forEach(key ->
                    replacements.putIfAbsent(key, List.of()));
            CommandEventAction.replaceSubscriptions(replacements, npc);
        };
    }

    // DataKey's YAML representation uses contiguous string-indexed maps for lists.
    private static List<?> list(Object raw) {
        if (raw instanceof List<?> list) return list;
        if (raw instanceof Map<?, ?> map) {
            var values = new ArrayList<Object>();
            for (int i = 0; i < map.size(); i++) {
                String index = Integer.toString(i);
                if (!map.containsKey(index)) throw new IllegalArgumentException("Expected indexed template event list");
                values.add(map.get(index));
            }
            return values;
        }
        throw new IllegalArgumentException("Expected template event list");
    }
}
