package net.citizensnpcs.api.event;

import net.citizensnpcs.api.npc.NPC;
import net.neoforged.bus.api.ICancellableEvent;

public class NPCCreateEvent extends NPCEvent implements ICancellableEvent {
    public NPCCreateEvent(NPC npc) {
        super(npc);
    }
}
