package net.citizensnpcs.trait.waypoint.triggers;

import net.citizensnpcs.api.util.ChatPrompt;
import net.citizensnpcs.api.util.ChatPromptSession;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.Messages;

/**
 * Asks which kind of trigger to add. A trigger name on its own opens that trigger's own prompt; a name followed by
 * arguments — {@code delay 20} — is finished on the spot.
 */
public class TriggerAddPrompt implements ChatPrompt {
    @Override
    public ChatPrompt acceptInput(ChatPromptSession session, String rawInput) {
        String input = rawInput.toLowerCase().trim();
        if (input.equalsIgnoreCase("back")) {
            session.setSessionData("said", false);
            return (ChatPrompt) session.getSessionData("previous");
        }
        String[] split = input.split(" ", 2);
        WaypointTriggerPrompt prompt = WaypointTriggerRegistry.getTriggerPromptFrom(split[0]);
        String extra = split.length > 1 ? split[1].trim() : "";
        session.setSessionData("said", false);
        if (prompt == null) {
            Messaging.sendErrorTr(session.getPlayer().createCommandSourceStack(),
                    Messages.WAYPOINT_TRIGGER_EDITOR_INVALID_TRIGGER, split[0]);
            return this;
        }
        if (!extra.isEmpty()) {
            WaypointTrigger created = prompt.createFromShortInput(session, extra);
            if (created != null) {
                session.setSessionData(WaypointTriggerPrompt.CREATED_TRIGGER_KEY, created);
                return (ChatPrompt) session.getSessionData("previous");
            }
        }
        return prompt;
    }

    @Override
    public String getPromptText(ChatPromptSession session) {
        if (session.getSessionData("said") == Boolean.TRUE)
            return "";
        session.setSessionData("said", true);
        session.setSessionData(WaypointTriggerPrompt.RETURN_PROMPT_KEY, session.getSessionData("previous"));
        Messaging.sendTr(session.getPlayer().createCommandSourceStack(), Messages.WAYPOINT_TRIGGER_ADD_PROMPT,
                WaypointTriggerRegistry.describeValidTriggerNames());
        return "";
    }
}
