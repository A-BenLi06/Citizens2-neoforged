package net.citizensnpcs.trait.waypoint.triggers;

import net.citizensnpcs.api.util.ChatPrompt;
import net.citizensnpcs.api.util.ChatPromptSession;
import net.citizensnpcs.api.util.Durations;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.Messages;

public class DelayTriggerPrompt implements WaypointTriggerPrompt {
    @Override
    public ChatPrompt acceptInput(ChatPromptSession session, String input) {
        session.setSessionData(CREATED_TRIGGER_KEY, new DelayTrigger(parseTicks(input)));
        return (ChatPrompt) session.getSessionData(RETURN_PROMPT_KEY);
    }

    @Override
    public WaypointTrigger createFromShortInput(ChatPromptSession session, String input) {
        return new DelayTrigger(parseTicks(input));
    }

    @Override
    public String getPromptText(ChatPromptSession session) {
        Messaging.sendTr(session.getPlayer().createCommandSourceStack(), Messages.DELAY_TRIGGER_PROMPT);
        return "";
    }

    /** Accepts a bare tick count or a duration such as {@code 3s}, which is what upstream's parseTicks does. */
    private static int parseTicks(String input) {
        try {
            return Math.max(0, Durations.toTicks(Durations.parse(input.trim())));
        } catch (RuntimeException ex) {
            return 0;
        }
    }
}
