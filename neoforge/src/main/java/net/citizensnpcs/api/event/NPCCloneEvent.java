package net.citizensnpcs.api.event;

import net.citizensnpcs.api.npc.NPC;

public class NPCCloneEvent extends NPCEvent {
    private final NPC clone;

    public NPCCloneEvent(NPC npc, NPC clone) {
        super(npc);
        this.clone = clone;
    }

    public NPC getClone() {
        return clone;
    }
}
