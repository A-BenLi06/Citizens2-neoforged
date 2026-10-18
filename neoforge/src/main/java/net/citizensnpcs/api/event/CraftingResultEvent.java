package net.citizensnpcs.api.event;

import net.citizensnpcs.util.CraftingEvidence;
import net.minecraft.server.level.ServerPlayer;

/**
 * A supported native crafting result is being taken. Fired on the server thread
 * before vanilla consumes the grid. This is not a durable completion or reward.
 */
public final class CraftingResultEvent extends CitizensEvent {
    private final ServerPlayer player;
    private final CraftingEvidence.Observation observation;

    public CraftingResultEvent(ServerPlayer player, CraftingEvidence.Observation observation) {
        this.player = java.util.Objects.requireNonNull(player);
        this.observation = java.util.Objects.requireNonNull(observation);
    }

    public ServerPlayer getPlayer() { return player; }
    public CraftingEvidence.Observation getObservation() { return observation; }
}
