package net.citizensnpcs.api.event;

import net.citizensnpcs.util.CreateMachineEvidence;

/** Native processing returned and exact output/remainder delta was verified; world save is separate. */
public final class MachineOutcomeEvent extends CitizensEvent {
    public enum Outcome { RETURNED, INTERRUPTED, UNCONFIRMED }
    private final CreateMachineEvidence.Observation observation;
    private final Outcome outcome;
    public MachineOutcomeEvent(CreateMachineEvidence.Observation observation, Outcome outcome) {
        this.observation = java.util.Objects.requireNonNull(observation); this.outcome = java.util.Objects.requireNonNull(outcome);
    }
    public CreateMachineEvidence.Observation getObservation() { return observation; }
    public Outcome getOutcome() { return outcome; }
}
