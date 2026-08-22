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
        String[] parts = input.split(":");
        if (parts.length != 4) {
            Messaging.sendErrorTr(player.createCommandSourceStack(), Messages.INVALID_TRIGGER_TELEPORT_FORMAT);
            return this;
        }
        ServerLevel level = Util.getLevel(player.getServer(), parts[0]);
        if (level == null) {
            Messaging.sendErrorTr(player.createCommandSourceStack(), Messages.WORLD_NOT_FOUND);
            return this;
        }
        try {
            Location at = new Location(level, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]));
            // upstream stores a bare Location here rather than a trigger, which makes the coordinate form unusable -
            // only its "here" branch wraps it. Wrapped in both.
            session.setSessionData(CREATED_TRIGGER_KEY, new TeleportTrigger(at));
        } catch (NumberFormatException ex) {
            Messaging.sendErrorTr(player.createCommandSourceStack(), Messages.INVALID_TRIGGER_TELEPORT_FORMAT);
            return this;
        }
        return (ChatPrompt) session.getSessionData(RETURN_PROMPT_KEY);
    }

    @Override
    public WaypointTrigger createFromShortInput(ChatPromptSession session, String input) {
        return null;
    }

    @Override
    public String getPromptText(ChatPromptSession session) {
        Messaging.sendTr(session.getPlayer().createCommandSourceStack(), Messages.WAYPOINT_TRIGGER_TELEPORT_PROMPT);
        return "";
    }
}
