package net.citizensnpcs.api.event;

import net.citizensnpcs.api.npc.NPC;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/** Player-specific command creation notification, retaining the actual command context. */
public class PlayerCreateNPCEvent extends CommandSenderCreateNPCEvent {
    public PlayerCreateNPCEvent(CommandSourceStack sender, NPC npc) { super(sender, npc); }
    public ServerPlayer getCreator() { return getSender().getPlayer(); }
}
