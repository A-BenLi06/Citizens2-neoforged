package net.citizensnpcs.trait.waypoint.triggers;

import java.util.ArrayList;
import java.util.List;

import net.citizensnpcs.api.util.ChatPrompt;
import net.citizensnpcs.api.util.ChatPromptSession;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.Messages;

/** Collects commands one line at a time until the player types {@code finish}. */
public class CommandTriggerPrompt implements WaypointTriggerPrompt {
    private final List<String> commands = new ArrayList<>();

    @Override
    public ChatPrompt acceptInput(ChatPromptSession session, String input) {
        if (input.equalsIgnoreCase("back"))
            return (ChatPrompt) session.getSessionData("previous");
        if (input.equalsIgnoreCase("finish")) {
            session.setSessionData(CREATED_TRIGGER_KEY, new CommandTrigger(commands));
            return (ChatPrompt) session.getSessionData(RETURN_PROMPT_KEY);
        }
        commands.add(input);
        Messaging.sendTr(session.getPlayer().createCommandSourceStack(), Messages.COMMAND_TRIGGER_ADDED, input);
        return this;
    }

    @Override
    public WaypointTrigger createFromShortInput(ChatPromptSession session, String input) {
        return new CommandTrigger(List.of(input));
    }

    @Override
    public String getPromptText(ChatPromptSession session) {
        Messaging.sendTr(session.getPlayer().createCommandSourceStack(), Messages.COMMAND_TRIGGER_PROMPT);
        return "";
    }
}
