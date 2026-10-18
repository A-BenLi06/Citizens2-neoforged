package net.citizensnpcs.api.event;

import net.citizensnpcs.util.CraftingEvidence;

/** Native result-slot processing outcome, not a world/inventory persistence acknowledgement. */
public final class CraftingOutcomeEvent extends CitizensEvent {
    public enum Outcome { RETURNED, INTERRUPTED, UNCONFIRMED }
    private final CraftingEvidence.Observation observation;
    private final Outcome outcome;

    public CraftingOutcomeEvent(CraftingEvidence.Observation observation, Outcome outcome) {
        this.observation = java.util.Objects.requireNonNull(observation);
        this.outcome = java.util.Objects.requireNonNull(outcome);
    }

    public CraftingEvidence.Observation getObservation() { return observation; }
    public Outcome getOutcome() { return outcome; }
}
