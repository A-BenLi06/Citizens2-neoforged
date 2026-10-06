package net.citizensnpcs.api.npc.templates;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.event.NPCDamageEvent;
import net.citizensnpcs.api.event.NPCDeathEvent;
import net.citizensnpcs.api.event.NPCLeftClickEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.event.NPCSpawnEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.templates.TemplateRegistry.TemplateErrorReporter;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.util.DataKey;
import net.minecraft.resources.ResourceLocation;

/**
 * One named template: an ordered list of actions to run against an NPC.
 * <p>
 * Identified by a {@link ResourceLocation} rather than Bukkit's {@code NamespacedKey} — same {@code namespace:name} shape,
 * so the on-disk layout of {@code templates/<namespace>/<file>.yml} is unchanged.
 * <p>
 * Applying despawns and respawns a spawned NPC around the actions, because several of them (a type change through
 * {@code yaml_replace}, most trait additions) only take effect on spawn.
 */
public class Template {
    private final List<Consumer<NPC>> actions = new ArrayList<>();
    private final ResourceLocation key;
    private final java.util.Set<NPC> applying = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private Template(ResourceLocation key) {
        this(key, List.of());
    }

    Template(ResourceLocation key, List<Consumer<NPC>> actions) {
        this.key = key;
        this.actions.addAll(actions);
    }

    private void addAction(Consumer<NPC> action) {
        actions.add(action);
    }

    public void apply(NPC npc) {
        var registry = npc.getOwningRegistry();
        if (!owns(registry, npc)) return;
        boolean respawn = npc.isSpawned();
        if (!applying.add(npc)) throw new IllegalStateException("Template " + key + " is already being applied to NPC " + npc.getId());
        Throwable failure = null;
        try {
            if (respawn && !npc.despawn(DespawnReason.PENDING_RESPAWN))
                throw new IllegalStateException("NPC despawn was rejected while applying template " + key);
            for (Consumer<NPC> action : actions) {
                if (!owns(registry, npc)) return;
                action.accept(npc);
            }
        } catch (RuntimeException | Error ex) {
            failure = ex;
            throw ex;
        } finally {
            try {
                if (respawn) {
                    try {
                        if (owns(registry, npc)
                                && !npc.isSpawned() && !npc.spawn(npc.getStoredLocation()))
                            throw new IllegalStateException("NPC respawn failed after applying template " + key);
                    } catch (RuntimeException | Error recovery) {
                        if (failure == null) throw recovery;
                        if (failure != recovery) failure.addSuppressed(recovery);
                    }
                }
            } finally { applying.remove(npc); }
        }
    }

    private static boolean owns(net.citizensnpcs.api.npc.NPCRegistry registry, NPC npc) {
        return registry.getById(npc.getId()) == npc && registry.getByUniqueId(npc.getUniqueId()) == npc;
    }

    public ResourceLocation getKey() {
        return key;
    }

    public static Template load(TemplateErrorReporter errors, TemplateWorkspace workspace, ResourceLocation identifier,
            DataKey key) {
        Template template = new Template(identifier);
        if (!(key.getRaw("") instanceof java.util.Map)) {
            errors.addError(identifier + ": Expected template mapping");
            return template;
        }
        if (key.keyExists("yaml_replace")) {
            template.addAction(PersistenceLoader.loadStrict(YamlReplacementAction.class, key.getRelative("yaml_replace")));
        }
        if (key.keyExists("traits")) {
            template.addAction(new TraitLoaderAction(errors, workspace, key.getRelative("traits")));
        }
        if (key.keyExists("commands")) {
            loadCommands(template, key.getRelative("commands"), errors);
        }
        return template;
    }

    private static void loadCommands(Template template, DataKey key, TemplateErrorReporter errors) {
        if (!(key.getRaw("") instanceof java.util.Map)) {
            errors.addError(key.getPath() + ": Expected command event mapping");
            return;
        }
        for (DataKey sub : key.getSubKeys()) {
            Object raw = sub.getRaw("");
            List<?> values;
            if (raw instanceof List<?> list) {
                values = list;
            } else if (raw instanceof java.util.Map<?, ?> map) {
                List<Object> ordered = new ArrayList<>();
                for (DataKey line : sub.getIntegerSubKeys()) ordered.add(line.getRaw(""));
                if (ordered.size() != map.size()) {
                    errors.addError(sub.getPath() + ": Expected indexed command list");
                    continue;
                }
                values = ordered;
            } else {
                errors.addError(sub.getPath() + ": Expected command list");
                continue;
            }
            List<String> commands = new ArrayList<>();
            boolean valid = true;
            for (Object value : values) {
                if (!(value instanceof String command)) {
                    errors.addError(sub.getPath() + ": Command entries must be strings");
                    valid = false;
                } else commands.add(command);
            }
            if (!valid) continue;
            switch (sub.name()) {
                case "on_spawn":
                    template.addAction(new CommandEventAction(NPCSpawnEvent.class, new CommandListExecutor(commands)));
                    break;
                case "on_death":
                    template.addAction(new CommandEventAction(NPCDeathEvent.class, new CommandListExecutor(commands)));
                    break;
                case "on_damage":
                    template.addAction(new CommandEventAction(NPCDamageEvent.class, new CommandListExecutor(commands)));
                    break;
                case "on_left_click":
                    template.addAction(
                            new CommandEventAction(NPCLeftClickEvent.class, new CommandListExecutor(commands)));
                    break;
                case "on_right_click":
                    template.addAction(
                            new CommandEventAction(NPCRightClickEvent.class, new CommandListExecutor(commands)));
                    break;
                case "on_template_apply":
                    // the only one that is not an event hook: it runs once, at apply time
                    CommandListExecutor cle = new CommandListExecutor(commands);
                    template.addAction(cle::accept);
                    break;
                default:
                    errors.addError(sub.getPath() + ": Unknown template command event " + sub.name());
                    break;
            }
        }
    }
}
