package net.citizensnpcs.trait.waypoint.triggers;

import net.citizensnpcs.api.util.ChatPrompt;
import net.citizensnpcs.api.util.ChatPromptSession;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.Messages;
import net.citizensnpcs.util.Util;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Accepts {@code here}, {@code back}, or {@code world:x:y:z}. */
public class TeleportTriggerPrompt implements WaypointTriggerPrompt {
    @Override
    public ChatPrompt acceptInput(ChatPromptSession session, String input) {
        input = input.trim();
        if (input.equalsIgnoreCase("back"))
            return (ChatPrompt) session.getSessionData("previous");

        ServerPlayer player = session.getPlayer();
        if (input.equalsIgnoreCase("here")) {
            session.setSessionData(CREATED_TRIGGER_KEY, new TeleportTrigger(Location.of(player)));
            return (ChatPrompt) session.getSessionData(RETURN_PROMPT_KEY);
        }
        Destination destination;
        try {
            destination = parseDestination(input);
        } catch (IllegalArgumentException invalid) {
            Messaging.sendErrorTr(player.createCommandSourceStack(), Messages.INVALID_TRIGGER_TELEPORT_FORMAT);
            return this;
        }
        TeleportTrigger trigger = resolve(player, destination);
        if (trigger == null) {
            Messaging.sendErrorTr(player.createCommandSourceStack(), Messages.WORLD_NOT_FOUND);
            return this;
        }
        session.setSessionData(CREATED_TRIGGER_KEY, trigger);
        return (ChatPrompt) session.getSessionData(RETURN_PROMPT_KEY);
    }

    @Override
    public WaypointTrigger createFromShortInput(ChatPromptSession session, String input) {
        if (input == null) return null;
        if (input.trim().equalsIgnoreCase("here")) return new TeleportTrigger(Location.of(session.getPlayer()));
        try { return resolve(session.getPlayer(), parseDestination(input)); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    private static TeleportTrigger resolve(ServerPlayer player, Destination destination) {
        ServerLevel level = Util.getLevel(player.getServer(), destination.world());
        return level == null ? null : new TeleportTrigger(new Location(level, destination.x(), destination.y(), destination.z()));
    }

    record Destination(String world, int x, int y, int z) { }

    static Destination parseDestination(String input) {
        if (input == null) throw new IllegalArgumentException("Missing teleport destination");
        String value = input.trim();
        int z = value.lastIndexOf(':');
        int y = z < 0 ? -1 : value.lastIndexOf(':', z - 1);
        int x = y < 0 ? -1 : value.lastIndexOf(':', y - 1);
        if (x <= 0 || value.substring(0, x).isBlank()) throw new IllegalArgumentException("Expected world:x:y:z");
        return new Destination(value.substring(0, x).trim(), Integer.parseInt(value.substring(x + 1, y).trim()),
                Integer.parseInt(value.substring(y + 1, z).trim()), Integer.parseInt(value.substring(z + 1).trim()));
    }

    @Override
    public String getPromptText(ChatPromptSession session) {
        Messaging.sendTr(session.getPlayer().createCommandSourceStack(), Messages.WAYPOINT_TRIGGER_TELEPORT_PROMPT);
        return "";
    }
}
