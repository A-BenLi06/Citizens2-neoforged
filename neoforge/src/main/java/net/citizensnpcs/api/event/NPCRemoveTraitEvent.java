package net.citizensnpcs.api.event;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.Trait;

public class NPCRemoveTraitEvent extends NPCTraitEvent {
    public NPCRemoveTraitEvent(NPC npc, Trait trait) {
        super(npc, trait);
    }
}
