package net.citizensnpcs.commands;

import java.util.Iterator;
import java.util.List;

import net.citizensnpcs.api.command.Command;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.command.CommandMessages;
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

/** Waypoint editing and the independent hierarchical pathfinding diagnostic. */
@Requirements(ownership = true, selected = true)
public class WaypointCommands {
    @Command(
            aliases = { "waypoints", "wp" },
            usage = "add [x] [y] [z] (world) (--index idx)",
            desc = "",
            modifiers = { "add" },
            strictArguments = true,
            min = 4,
            max = 5,
            permission = "citizens.waypoints.add")
    public void add(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("index") Integer index)
            throws CommandException {
        Location at = commandLocation(args, sender, npc);
        Waypoints trait = npc.getTraitNullable(Waypoints.class);
        List<Waypoint> waypoints = trait == null ? java.util.Collections.emptyList() : linearWaypoints(trait);
        int insertAt = index == null ? waypoints.size() : index;
        if (insertAt > waypoints.size() || insertAt < 0)
            throw new CommandException(Messages.WAYPOINT_INDEX_OUT_OF_RANGE, waypoints.size());
        if (trait == null) trait = npc.getOrAddTrait(Waypoints.class);
        if (!trait.isCurrent()) return;
        waypoints = linearWaypoints(trait);
        if (insertAt > waypoints.size()) throw new CommandException(Messages.WAYPOINT_INDEX_OUT_OF_RANGE, waypoints.size());
        waypoints.add(insertAt, new Waypoint(at));
        Messaging.sendTr(sender, Messages.WAYPOINT_ADDED, Util.prettyPrintLocation(at), insertAt);
    }

    @Command(
            aliases = { "waypoints", "wp" },
            usage = "hpa",
            desc = "",
            modifiers = { "hpa" },
            strictArguments = true,
            min = 1,
            max = 1,
            permission = "citizens.admin")
    public void hpa(CommandContext args, ServerPlayer sender, NPC npc) throws CommandException {
        HPADebugService.start(sender, npc);
    }

    @Command(
            aliases = { "waypoints", "wp" },
            usage = "provider [provider name]",
            desc = "",
            modifiers = { "provider" },
            strictArguments = true,
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
        if (!waypoints.setWaypointProvider(args.getString(1)))
            throw new CommandException(Messages.WAYPOINT_PROVIDER_NOT_FOUND, args.getString(1));
        ServerPlayer player = sender.getPlayer();
        if (player != null && Editor.hasEditor(player)) Editor.leave(player);
        if (!waypoints.isCurrent()) return;
        Messaging.sendTr(sender, Messages.WAYPOINT_PROVIDER_SET, args.getString(1));
    }

    @Command(
            aliases = { "waypoints", "wp" },
            usage = "remove (x y z world) (--index idx)",
            desc = "",
            modifiers = { "remove" },
            strictArguments = true,
            min = 1,
            max = 5,
            permission = "citizens.waypoints.remove")
    public void remove(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("index") Integer index)
            throws CommandException {
        Waypoints trait = npc.getTraitNullable(Waypoints.class);
        if (trait == null) throw new CommandException(Messages.WAYPOINT_PROVIDER_NOT_LINEAR);
        List<Waypoint> waypoints = linearWaypoints(trait);
        if (index != null) {
            if (index < 0 || index >= waypoints.size()) throw new CommandException(Messages.WAYPOINT_INDEX_OUT_OF_RANGE, waypoints.size());
            waypoints.remove(index.intValue());
            Messaging.sendTr(sender, Messages.WAYPOINT_REMOVED, index);
            return;
        }
        if (args.argsLength() < 4)
            throw new CommandUsageException();
        Location at = commandLocation(args, sender, npc);
        for (Iterator<Waypoint> itr = waypoints.iterator(); itr.hasNext();) {
            Location candidate = itr.next().getLocation();
            if (candidate.getWorld() == at.getWorld() && candidate.getX() == at.getX()
                    && candidate.getY() == at.getY() && candidate.getZ() == at.getZ()) {
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
    private static List<Waypoint> linearWaypoints(Waypoints trait) throws CommandException {
        WaypointProvider provider = trait.getCurrentProvider();
        if (!(provider instanceof LinearWaypointProvider linear))
            throw new CommandException(Messages.WAYPOINT_PROVIDER_NOT_LINEAR);
        return (List<Waypoint>) linear.waypoints();
    }

    private static Location commandLocation(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        Location stored = npc.getStoredLocation();
        ServerLevel level = args.argsLength() > 4 ? net.citizensnpcs.api.persistence.LocationPersister.resolveStrict(args.getString(4))
                : stored != null && stored.getWorld() != null ? stored.getWorld() : sender.getLevel();
        if (level == null) throw new CommandException(Messages.WORLD_NOT_FOUND);
        try { return new Location(level, args.getInteger(1), args.getInteger(2), args.getInteger(3)); }
        catch (NumberFormatException invalid) { throw new CommandException(CommandMessages.INVALID_NUMBER); }
    }
}
