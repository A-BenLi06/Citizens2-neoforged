package net.citizensnpcs.commands;

import net.citizensnpcs.api.command.Command;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.command.Requirements;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.ChatPrompts;
import net.citizensnpcs.editor.CopierEditor;
import net.citizensnpcs.editor.Editor;
import net.citizensnpcs.editor.EquipmentEditor;
import net.citizensnpcs.trait.waypoint.Waypoints;
import net.citizensnpcs.util.Messages;
import net.minecraft.server.level.ServerPlayer;

/**
 * The commands that put a player into an {@link Editor}.
 * <p>
 * Upstream also has {@code /npc text} here, a chat conversation for editing an NPC's dialogue; it is not ported yet and
 * is absent rather than stubbed, so the dispatcher reports an unknown command instead of opening something inert.
 */
@Requirements(selected = true, ownership = true)
public class EditorCommands {
    @Command(
            aliases = { "npc" },
            usage = "copier",
            desc = "",
            modifiers = { "copier" },
            min = 1,
            max = 1,
            permission = "citizens.npc.edit.copier")
    public void copier(CommandContext args, ServerPlayer player, NPC npc) {
        Editor.enterOrLeave(player, new CopierEditor(player, npc));
    }

    @Command(
            aliases = { "npc" },
            usage = "path",
            desc = "",
            modifiers = { "path" },
            min = 1,
            flags = "*",
            permission = "citizens.npc.edit.path")
    public void path(CommandContext args, ServerPlayer player, NPC npc) {
        // the trigger editor runs as a chat conversation; while one is open, the words the player types are its input
        if (ChatPrompts.isActive(player) && Editor.hasEditor(player) && args.argsLength() > 1)
            return;
        Editor.enterOrLeave(player, npc.getOrAddTrait(Waypoints.class).getEditor(player.createCommandSourceStack(), args));
    }

    @Command(
            aliases = { "npc" },
            usage = "equip",
            desc = "",
            modifiers = { "equip" },
            min = 1,
            max = 1,
            permission = "citizens.npc.edit.equip")
    public void equip(CommandContext args, ServerPlayer player, NPC npc) throws CommandException {
        if (!npc.isSpawned())
            throw new CommandException(Messages.EQUIP_MUST_BE_SPAWNED, npc.getName());
        Editor.enterOrLeave(player, new EquipmentEditor(player, npc));
    }
}
