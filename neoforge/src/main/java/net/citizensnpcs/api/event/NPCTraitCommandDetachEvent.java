package net.citizensnpcs.api.event;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.Trait;
import net.minecraft.commands.CommandSourceStack;

/** Called when a trait is removed from an NPC by the /trait command, before the removal happens. */
public class NPCTraitCommandDetachEvent extends NPCEvent {
    private final CommandSourceStack sender;
    private final Class<? extends Trait> traitClass;

    public NPCTraitCommandDetachEvent(NPC npc, Class<? extends Trait> traitClass, CommandSourceStack sender) {
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
