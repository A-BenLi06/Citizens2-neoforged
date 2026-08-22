package net.citizensnpcs.api.event;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.Trait;

public class NPCAddTraitEvent extends NPCTraitEvent {
    public NPCAddTraitEvent(NPC npc, Trait trait) {
        super(npc, trait);
    }
}
