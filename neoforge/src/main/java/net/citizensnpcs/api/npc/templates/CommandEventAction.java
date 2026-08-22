package net.citizensnpcs.api.npc.templates;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import net.citizensnpcs.api.event.NPCEvent;
import net.citizensnpcs.api.npc.NPC;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Runs a template's command list every time one particular event fires for an NPC the template was applied to.
 * <p>
 * Upstream has to reach into Bukkit's internals for this — reflect out the event class's {@code HandlerList}, build a
 * synthetic {@code RegisteredListener}, and pull the NPC back off the event through a {@code MethodHandle}, because the
 * event class is only known at runtime. NeoForge's bus takes the class directly, so the whole construction collapses into
 * one {@code addListener} call and {@code getNPC()} is a plain virtual call.
 * <p>
 * The set of UUIDs is the same design as upstream: one listener is registered per template action, and it filters to the
 * NPCs the template has actually been applied to.
 */
public class CommandEventAction implements Consumer<NPC> {
    private final Set<UUID> uuids = new HashSet<>();

    public <T extends NPCEvent> CommandEventAction(Class<T> clazz, Consumer<NPC> commands) {
        NeoForge.EVENT_BUS.addListener(clazz, event -> {
            // exact class only, so a subclass event does not trigger its parent's template commands as well
            if (event.getClass() != clazz)
                return;
            NPC npc = event.getNPC();
            if (npc != null && uuids.contains(npc.getUniqueId())) {
                commands.accept(npc);
            }
        });
    }

    @Override
    public void accept(NPC npc) {
        uuids.add(npc.getUniqueId());
    }
}
