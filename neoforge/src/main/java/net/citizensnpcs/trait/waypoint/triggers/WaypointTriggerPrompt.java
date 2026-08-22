package net.citizensnpcs.trait.waypoint.triggers;

import net.citizensnpcs.api.util.ChatPrompt;
import net.citizensnpcs.api.util.ChatPromptSession;

/**
 * A prompt that configures one kind of {@link WaypointTrigger}.
 * <p>
 * The contract: put the finished trigger under {@link #CREATED_TRIGGER_KEY} and return the prompt stored under
 * {@link #RETURN_PROMPT_KEY}. A null trigger means the prompt gave up, and the caller reports an error.
 */
public interface WaypointTriggerPrompt extends ChatPrompt {
    /**
     * Builds the trigger straight from arguments typed on the same line as the trigger name, so
     * {@code delay 20} works without a follow-up question.
     *
     * @return the trigger, or null when the input could not be understood
     */
    WaypointTrigger createFromShortInput(ChatPromptSession session, String input);

    String CREATED_TRIGGER_KEY = "created-trigger";
    String RETURN_PROMPT_KEY = "return-to";
}
