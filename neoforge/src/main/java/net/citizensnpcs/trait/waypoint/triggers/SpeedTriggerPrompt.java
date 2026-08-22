package net.citizensnpcs.trait.waypoint.triggers;

import net.citizensnpcs.api.util.ChatPrompt;
import net.citizensnpcs.api.util.ChatPromptSession;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.Messages;

public class SpeedTriggerPrompt implements WaypointTriggerPrompt {
    @Override
    public ChatPrompt acceptInput(ChatPromptSession session, String input) {
        WaypointTrigger trigger = createFromShortInput(session, input);
        if (trigger == null) {
            // upstream leans on Bukkit's NumericPrompt to re-ask; without it the check is explicit
            Messaging.sendErrorTr(session.getPlayer().createCommandSourceStack(), Messages.SPEED_TRIGGER_PROMPT);
            return this;
        }
        session.setSessionData(CREATED_TRIGGER_KEY, trigger);
        return (ChatPrompt) session.getSessionData(RETURN_PROMPT_KEY);
    }

    @Override
    public WaypointTrigger createFromShortInput(ChatPromptSession session, String input) {
        try {
            return new SpeedTrigger((float) Math.max(Double.parseDouble(input.trim()), 0));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Override
    public String getPromptText(ChatPromptSession session) {
        Messaging.sendTr(session.getPlayer().createCommandSourceStack(), Messages.SPEED_TRIGGER_PROMPT);
        return "";
    }
}
