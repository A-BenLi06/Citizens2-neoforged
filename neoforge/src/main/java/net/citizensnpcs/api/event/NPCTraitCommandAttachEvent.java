package net.citizensnpcs.api.event;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.Trait;
import net.minecraft.commands.CommandSourceStack;

/** Called when a trait is attached to an NPC by the /trait command, so plugins can react to a deliberate change. */
public class NPCTraitCommandAttachEvent extends NPCEvent {
    private final CommandSourceStack sender;
    private final Class<? extends Trait> traitClass;

    public NPCTraitCommandAttachEvent(NPC npc, Class<? extends Trait> traitClass, CommandSourceStack sender) {
        super(npc);
        this.traitClass = traitClass;
        this.sender = sender;
    }

    public CommandSourceStack getSender() {
        return sender;
    }

    public Class<? extends Trait> getTraitClass() {
        return traitClass;
    }
}
