package net.citizensnpcs.commands;

import java.util.Iterator;
import java.util.List;

import net.citizensnpcs.api.command.Command;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.command.Flag;
import net.citizensnpcs.api.command.Requirements;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.command.exception.CommandUsageException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.editor.Editor;
import net.citizensnpcs.trait.waypoint.LinearWaypointProvider;
import net.citizensnpcs.trait.waypoint.Waypoint;
import net.citizensnpcs.trait.waypoint.WaypointProvider;
import net.citizensnpcs.trait.waypoint.Waypoints;
import net.citizensnpcs.util.Messages;
import net.citizensnpcs.util.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /waypoints}, for editing a route without the in-world editor.
 * <p>
 * Upstream also has an op-only {@code waypoints hpa} that prints a hierarchical-pathfinding graph to stdout. It depends on
 * {@code api/hpastar}, which nothing else uses and which is not ported; the command is a debugging aid rather than a
 * feature, so it is absent rather than stubbed.
 */
@Requirements(ownership = true, selected = true)
public class WaypointCommands {
    @Command(
            aliases = { "waypoints", "wp" },
            usage = "add [x] [y] [z] (world) (--index idx)",
            desc = "",
            modifiers = { "add" },
            min = 4,
            max = 5,
            permission = "citizens.waypoints.add")
    public void add(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("index") Integer index)
            throws CommandException {
        List<Waypoint> waypoints = linearWaypoints(npc);
        ServerLevel level = args.argsLength() > 4 ? Util.getLevel(sender.getServer(), args.getString(4))
                : npc.getStoredLocation().getWorld();
        if (level == null)
            throw new CommandException(Messages.WORLD_NOT_FOUND);
        Location at = new Location(level, args.getInteger(1), args.getInteger(2), args.getInteger(3));
        int insertAt = index == null ? waypoints.size() : index;
        if (insertAt > waypoints.size() || insertAt < 0)
            throw new CommandException(Messages.WAYPOINT_INDEX_OUT_OF_RANGE, waypoints.size());
        waypoints.add(insertAt, new Waypoint(at));
        Messaging.sendTr(sender, Messages.WAYPOINT_ADDED, Util.prettyPrintLocation(at), insertAt);
    }

    @Command(
            aliases = { "waypoints", "wp" },
            usage = "provider [provider name]",
            desc = "",
            modifiers = { "provider" },
            min = 1,
            max = 2,
            permission = "citizens.waypoints.provider")
    public void provider(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        Waypoints waypoints = npc.getOrAddTrait(Waypoints.class);
        if (args.argsLength() == 1) {
            Messaging.sendTr(sender, Messages.CURRENT_WAYPOINT_PROVIDER, waypoints.getCurrentProviderName());
            waypoints.describeProviders(sender);
            return;
        }
        ServerPlayer player = sender.getPlayer();
        if (player != null && Editor.hasEditor(player)) {
            Editor.leave(player);
        }
        if (!waypoints.setWaypointProvider(args.getString(1)))
            throw new CommandException(Messages.WAYPOINT_PROVIDER_NOT_FOUND, args.getString(1));
        Messaging.sendTr(sender, Messages.WAYPOINT_PROVIDER_SET, args.getString(1));
    }

    @Command(
            aliases = { "waypoints", "wp" },
            usage = "remove (x y z world) (--index idx)",
            desc = "",
            modifiers = { "remove" },
            min = 1,
            max = 5,
            permission = "citizens.waypoints.remove")
    public void remove(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("index") Integer index)
            throws CommandException {
        List<Waypoint> waypoints = linearWaypoints(npc);
        if (index != null && index >= 0 && index < waypoints.size()) {
            waypoints.remove(index.intValue());
            Messaging.sendTr(sender, Messages.WAYPOINT_REMOVED, index);
            return;
        }
        if (args.argsLength() < 4)
            throw new CommandUsageException();
        ServerLevel level = args.argsLength() > 4 ? Util.getLevel(sender.getServer(), args.getString(4))
                : npc.getStoredLocation().getWorld();
        if (level == null)
            throw new CommandException(Messages.WORLD_NOT_FOUND);
        Location at = new Location(level, args.getInteger(1), args.getInteger(2), args.getInteger(3));
        for (Iterator<Waypoint> itr = waypoints.iterator(); itr.hasNext();) {
            if (itr.next().getLocation().equals(at)) {
                itr.remove();
            }
        }
        Messaging.sendTr(sender, Messages.WAYPOINT_REMOVED, Util.prettyPrintLocation(at));
    }

    /**
     * These commands edit a list, so they only apply to the linear provider — the wander and guided providers have no
     * ordered route to index into.
     */
    @SuppressWarnings("unchecked")
    private static List<Waypoint> linearWaypoints(NPC npc) throws CommandException {
        WaypointProvider provider = npc.getOrAddTrait(Waypoints.class).getCurrentProvider();
        if (!(provider instanceof LinearWaypointProvider linear))
            throw new CommandException(Messages.WAYPOINT_PROVIDER_NOT_LINEAR);
        return (List<Waypoint>) linear.waypoints();
    }
}
