package net.citizensnpcs.commands;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.citizensnpcs.Citizens;
import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.command.Command;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.command.Requirements;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.Messages;
import net.citizensnpcs.util.StringHelper;
import net.minecraft.commands.CommandSourceStack;

/**
 * The {@code /citizens} root: version info, {@code save} and {@code reload}.
 * <p>
 * The port had no equivalent of this class, which meant there was no in-game way to force a save or to re-read
 * {@code saves.yml} and {@code config.yml} — the only save was the one on server stop.
 * <p>
 * The reload confirmation is keyed by sender identity rather than by the {@code CommandSender} object upstream uses as a
 * weak map key: a {@link CommandSourceStack} is rebuilt for every command, so it would never match itself twice and the
 * warning could never be confirmed. Console and command blocks share one entry under a nil UUID, which is the same
 * grouping upstream ends up with for non-players.
 */
@Requirements
public class AdminCommands {
    private final Citizens plugin;
    private final Map<UUID, Long> reloadTimeouts = new HashMap<>();

    public AdminCommands(Citizens plugin) {
        this.plugin = plugin;
    }

    @Command(aliases = { "citizens" }, desc = "", max = 0, permission = "citizens.admin")
    public void citizens(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        Messaging.send(sender, StringHelper.wrapHeader("<green>Citizens v" + plugin.getVersion()));
        Messaging.send(sender, "     <yellow>-- <green>Author: fullwall");
        Messaging.send(sender,
                "     <yellow>-- <green><click:open_url:https://wiki.citizensnpcs.co><hover:show_text:Citizens website including wiki><u>Website</hover></click>"
                        + " <click:open_url:https://discord.gg/Q6pZGSR><hover:show_text:Citizens Support Discord><u>Support</hover></click>");
    }

    @Command(
            aliases = { "citizens" },
            usage = "reload",
            desc = "",
            modifiers = { "reload", "load" },
            min = 1,
            max = 1,
            permission = "citizens.admin")
    public void reload(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        if (Setting.WARN_ON_RELOAD.asBoolean()) {
            UUID key = senderKey(sender);
            Long timeout = reloadTimeouts.get(key);
            if (timeout == null || System.currentTimeMillis() > timeout) {
                Messaging.sendErrorTr(sender, Messages.CITIZENS_RELOAD_WARNING);
                reloadTimeouts.put(key, System.currentTimeMillis() + 5000);
                return;
            }
            reloadTimeouts.remove(key);
        }
        Messaging.sendTr(sender, Messages.CITIZENS_RELOADING);
        try {
            plugin.reload();
            Messaging.sendTr(sender, Messages.CITIZENS_RELOADED);
        } catch (NPCLoadException ex) {
            ex.printStackTrace();
            throw new CommandException(Messages.CITIZENS_RELOAD_ERROR);
        }
    }

    @Command(
            aliases = { "citizens" },
            usage = "save",
            desc = "",
            modifiers = { "save" },
            min = 1,
            max = 1,
            permission = "citizens.admin")
    public void save(CommandContext args, CommandSourceStack sender, NPC npc) {
        Messaging.sendTr(sender, Messages.CITIZENS_SAVING);
        plugin.storeNPCs();
        Messaging.sendTr(sender, Messages.CITIZENS_SAVED);
    }

    private static UUID senderKey(CommandSourceStack sender) {
        return sender.getPlayer() == null ? new UUID(0, 0) : sender.getPlayer().getUUID();
    }
}
