package net.citizensnpcs.api.event;

import net.citizensnpcs.api.npc.NPC;
import net.minecraft.commands.CommandSourceStack;

/** Fired when an NPC is removed by a command rather than programmatically. */
public class NPCRemoveByCommandSenderEvent extends NPCRemoveEvent {
    private final CommandSourceStack source;

    public NPCRemoveByCommandSenderEvent(NPC npc, CommandSourceStack source) {
        super(npc);
        this.source = source;
    }

    public CommandSourceStack getSource() {
        return source;
    }
}
