package net.citizensnpcs.trait.waypoint.triggers;

import net.citizensnpcs.api.util.ChatPrompt;
import net.citizensnpcs.api.util.ChatPromptSession;
import net.citizensnpcs.api.util.Durations;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.Messages;

public class DelayTriggerPrompt implements WaypointTriggerPrompt {
    @Override
    public ChatPrompt acceptInput(ChatPromptSession session, String input) {
        WaypointTrigger trigger = createFromShortInput(session, input);
        if (trigger == null) {
            Messaging.sendErrorTr(session.getPlayer().createCommandSourceStack(), Messages.DELAY_TRIGGER_PROMPT);
            return this;
        }
        session.setSessionData(CREATED_TRIGGER_KEY, trigger);
        return (ChatPrompt) session.getSessionData(RETURN_PROMPT_KEY);
    }

    @Override
    public WaypointTrigger createFromShortInput(ChatPromptSession session, String input) {
        if (input == null || input.isBlank()) return null;
        try {
            java.time.Duration duration = Durations.parse(input.trim());
            int ticks = duration.isNegative() ? 0 : Durations.toTicks(duration);
            return new DelayTrigger(ticks);
        } catch (java.time.DateTimeException | IllegalArgumentException | ArithmeticException invalid) {
            return null;
        }
    }

    @Override
    public String getPromptText(ChatPromptSession session) {
        Messaging.sendTr(session.getPlayer().createCommandSourceStack(), Messages.DELAY_TRIGGER_PROMPT);
        return "";
    }

}
