package net.citizensnpcs.commands;

import net.citizensnpcs.api.command.Command;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.command.CommandMessages;
import net.citizensnpcs.api.command.Requirements;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.ChatPrompts;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.api.trait.trait.Owner;
import net.citizensnpcs.editor.CopierEditor;
import net.citizensnpcs.editor.Editor;
import net.citizensnpcs.editor.EquipmentEditor;
import net.citizensnpcs.trait.waypoint.Waypoints;
import net.citizensnpcs.trait.text.Text;
import net.citizensnpcs.trait.text.TextEditor;
import net.citizensnpcs.util.Messages;
import net.minecraft.server.level.ServerPlayer;

/**
 * The commands that put a player into an {@link Editor}.
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
        if (ChatPrompts.isActive(player) && Editor.getEditor(player) instanceof net.citizensnpcs.trait.waypoint.WaypointEditor
                && args.argsLength() > 1) {
            ChatPrompts.acceptInput(player, args.getJoinedStrings(1));
            return;
        }
        Editor.enterOrLeave(player, npc.getOrAddTrait(Waypoints.class).getEditor(player.createCommandSourceStack(), args));
    }

    @Command(aliases = "npc", modifiers = "text", usage = "text (add|edit|remove|page|delay|range|item|random|close|speech bubbles|realistic looking|send text to chat|exit)",
            desc = "", min = 1, strictArguments = true, permission = TextEditor.PERMISSION)
    @Requirements
    public void text(CommandContext args, ServerPlayer player, NPC npc) throws CommandException {
        if (Editor.getEditor(player) instanceof TextEditor editor) {
            if ((args.hasValueFlag("id") || args.hasValueFlag("uuid")) && npc != editor.getNPC())
                throw new CommandException("citizens.editors.text.target-mismatch");
            if (args.argsLength() == 1) editor.close();
            else editor.executeCommand(args.getJoinedStrings(1));
            return;
        }
        if (Editor.hasEditor(player) || ChatPrompts.isActive(player)) throw new CommandException(Messages.ALREADY_IN_EDITOR);
        if (npc == null) throw new CommandException(CommandMessages.MUST_HAVE_SELECTED);
        if (!PermissionUtil.hasPermission(player, "citizens.admin") && !npc.getOrAddTrait(Owner.class).isOwnedBy(player.createCommandSourceStack()))
            throw new CommandException(CommandMessages.MUST_BE_OWNER);
        TextEditor editor = npc.getOrAddTrait(Text.class).getEditor(player);
        Editor.enterOrLeave(player, editor);
        if (args.argsLength() > 1) editor.executeCommand(args.getJoinedStrings(1));
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
