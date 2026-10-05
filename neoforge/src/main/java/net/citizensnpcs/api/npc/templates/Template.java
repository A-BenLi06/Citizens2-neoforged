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

    private Template(ResourceLocation key) {
        this.key = key;
    }

    private void addAction(Consumer<NPC> action) {
        actions.add(action);
    }

    public void apply(NPC npc) {
        boolean respawn = npc.isSpawned();
        var registry = npc.getOwningRegistry();
        Throwable failure = null;
        try {
            if (respawn && !npc.despawn(DespawnReason.PENDING_RESPAWN))
                throw new IllegalStateException("NPC despawn was rejected while applying template " + key);
            for (Consumer<NPC> action : actions) action.accept(npc);
        } catch (RuntimeException | Error ex) {
            failure = ex;
            throw ex;
        } finally {
            if (respawn) {
                try {
                    if (registry.getById(npc.getId()) == npc && registry.getByUniqueId(npc.getUniqueId()) == npc
                            && !npc.isSpawned() && !npc.spawn(npc.getStoredLocation()))
                        throw new IllegalStateException("NPC respawn failed after applying template " + key);
                } catch (RuntimeException | Error recovery) {
                    if (failure == null) throw recovery;
                    if (failure != recovery) failure.addSuppressed(recovery);
                }
            }
        }
    }

    public ResourceLocation getKey() {
        return key;
    }

    public static Template load(TemplateErrorReporter errors, TemplateWorkspace workspace, ResourceLocation identifier,
            DataKey key) {
        Template template = new Template(identifier);
        if (key.keyExists("yaml_replace")) {
            template.addAction(PersistenceLoader.loadStrict(YamlReplacementAction.class, key.getRelative("yaml_replace")));
        }
        if (key.keyExists("traits")) {
            template.addAction(new TraitLoaderAction(errors, workspace, key.getRelative("traits")));
        }
        if (key.keyExists("commands")) {
            loadCommands(template, key.getRelative("commands"));
        }
        return template;
    }

    private static void loadCommands(Template template, DataKey key) {
        for (DataKey sub : key.getSubKeys()) {
            List<String> commands = new ArrayList<>();
            for (DataKey line : sub.getIntegerSubKeys()) {
                commands.add(line.getString(""));
            }
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
                    break;
            }
        }
    }
}
