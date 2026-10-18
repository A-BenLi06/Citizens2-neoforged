package net.citizensnpcs.api.event;

import net.citizensnpcs.util.CreateMachineEvidence;
import net.minecraft.server.level.ServerPlayer;

/** Native millstone results rolled after consumption, before insertion. Not a persistence acknowledgement. */
public final class MachineResultEvent extends CitizensEvent {
    private final ServerPlayer beneficiary;
    private final CreateMachineEvidence.Observation observation;
    public MachineResultEvent(ServerPlayer beneficiary, CreateMachineEvidence.Observation observation) {
        this.beneficiary = java.util.Objects.requireNonNull(beneficiary); this.observation = java.util.Objects.requireNonNull(observation);
    }
    public ServerPlayer getBeneficiary() { return beneficiary; }
    public CreateMachineEvidence.Observation getObservation() { return observation; }
}
