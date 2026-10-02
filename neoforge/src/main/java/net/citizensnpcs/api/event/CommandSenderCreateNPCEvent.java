package net.citizensnpcs.api.event;

import net.citizensnpcs.api.npc.NPC;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.bus.api.ICancellableEvent;

/** A command candidate is configured with its basic options but has not been spawned yet. */
public class CommandSenderCreateNPCEvent extends NPCEvent implements ICancellableEvent {
    private final CommandSourceStack sender;
    private String cancelReason = "";

    public CommandSenderCreateNPCEvent(CommandSourceStack sender, NPC npc) {
        super(npc);
        this.sender = sender;
    }

    public CommandSourceStack getSender() { return sender; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String reason) { cancelReason = reason == null ? "" : reason; }
}
