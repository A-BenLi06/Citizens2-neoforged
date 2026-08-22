package net.citizensnpcs.trait.waypoint.triggers;

import java.util.ArrayList;
import java.util.List;

import net.citizensnpcs.api.util.ChatPrompt;
import net.citizensnpcs.api.util.ChatPromptSession;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.Messages;

/** Collects chat lines, and optionally a radius, until the player types {@code finish}. */
public class ChatTriggerPrompt implements WaypointTriggerPrompt {
    private final List<String> lines = new ArrayList<>();
    private double radius = -1;

    @Override
    public ChatPrompt acceptInput(ChatPromptSession session, String input) {
        if (input.equalsIgnoreCase("back"))
            return (ChatPrompt) session.getSessionData("previous");
        if (input.startsWith("radius")) {
            String[] split = input.split(" ");
            if (split.length < 2) {
                Messaging.sendErrorTr(session.getPlayer().createCommandSourceStack(),
                        Messages.WAYPOINT_TRIGGER_CHAT_NO_RADIUS);
                return this;
            }
            try {
                radius = Double.parseDouble(split[1]);
                Messaging.sendTr(session.getPlayer().createCommandSourceStack(), Messages.CHAT_TRIGGER_RADIUS_SET,
                        radius);
            } catch (NumberFormatException ex) {
                Messaging.sendErrorTr(session.getPlayer().createCommandSourceStack(),
                        Messages.WAYPOINT_TRIGGER_CHAT_INVALID_RADIUS);
            }
            return this;
        }
        if (input.equalsIgnoreCase("finish")) {
            session.setSessionData(CREATED_TRIGGER_KEY, new ChatTrigger(radius, lines));
            return (ChatPrompt) session.getSessionData(RETURN_PROMPT_KEY);
        }
        lines.add(input);
        Messaging.sendTr(session.getPlayer().createCommandSourceStack(), Messages.CHAT_TRIGGER_MESSAGE_ADDED, input);
        return this;
    }

    @Override
    public WaypointTrigger createFromShortInput(ChatPromptSession session, String input) {
        return new ChatTrigger(radius, List.of(input));
    }

    @Override
    public String getPromptText(ChatPromptSession session) {
        if (session.getSessionData("said") == Boolean.TRUE) {
            Messaging.send(session.getPlayer().createCommandSourceStack(),
                    "Current lines:<br>-   " + String.join("<br>-   ", lines));
        } else {
            Messaging.sendTr(session.getPlayer().createCommandSourceStack(), Messages.CHAT_TRIGGER_PROMPT);
            session.setSessionData("said", true);
        }
        return "";
    }
}
