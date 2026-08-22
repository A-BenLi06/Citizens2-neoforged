package net.citizensnpcs.api.ai.speech.event;

import net.citizensnpcs.api.ai.speech.SpeechContext;
import net.citizensnpcs.api.event.NPCEvent;
import net.citizensnpcs.api.npc.NPC;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Represents an event where an NPC speaks using /npc speak.
 */
public class NPCSpeechEvent extends NPCEvent implements ICancellableEvent {
    private final SpeechContext context;

    public NPCSpeechEvent(NPC npc, SpeechContext context) {
        super(npc);
        this.context = context;
    }

    public SpeechContext getContext() {
        return context;
    }
}
