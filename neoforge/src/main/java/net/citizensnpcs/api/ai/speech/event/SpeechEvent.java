package net.citizensnpcs.api.ai.speech.event;

import net.citizensnpcs.api.ai.speech.SpeechContext;
import net.citizensnpcs.api.ai.speech.Talkable;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Represents an event where a {@link Talkable} entity speaks at/near a {@link Talkable} entity.
 */
public class SpeechEvent extends Event implements ICancellableEvent {
    SpeechContext context;
    String message;
    Talkable target;

    public SpeechEvent(Talkable target, SpeechContext context, String message) {
        this.target = target;
        this.context = context;
        this.message = message;
    }

    /**
     * Gets the {@link SpeechContext} associated with the SpeechEvent.
     *
     * @return the SpeechContext
     */
    public SpeechContext getContext() {
        return context;
    }

    /**
     * The final message to be sent to the bystander. Note: This may differ from the message contained in the
     * SpeechContext, as formatting may have occurred.
     *
     * @return the message to be sent to the {@link Talkable} bystander.
     */
    public String getMessage() {
        return message;
    }

    /**
     * @return the {@link Talkable} this message is directed at
     */
    public Talkable getTalkable() {
        return target;
    }

    /**
     * Sets the message to be sent to the bystander. Note: This may differ from the message contained in the
     * SpeechContext, as formatting may have occurred.
     */
    public void setMessage(String formattedMessage) {
        this.message = formattedMessage;
    }
}
