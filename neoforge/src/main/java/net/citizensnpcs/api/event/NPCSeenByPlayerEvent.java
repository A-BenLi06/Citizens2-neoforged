package net.citizensnpcs.api.event;

import net.citizensnpcs.api.npc.NPC;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Called before an eligible player starts tracking an NPC's entity, before viewer membership or pairing packets.
 * Canceling prevents that tracking attempt, including the player profile and spawn data. A canceled attempt may be
 * retried by subsequent tracking updates; the decision is not cached. Already paired viewers do not receive this event
 * again until they leave and reenter tracking. Use the NPC's visibility rules to hide an existing viewer.
 * <p>
 * Both native world tracking and PacketNPC admission honor this contract. NeoForge's noncancellable
 * {@code PlayerEvent.StartTracking} remains the notification after successful pairing, suitable for supplemental
 * per-viewer packets. Native range/chunk eligibility and Citizens visibility rules are checked before admission.
 */
public class NPCSeenByPlayerEvent extends NPCEvent implements ICancellableEvent {
    private final ServerPlayer player;

    public NPCSeenByPlayerEvent(NPC npc, ServerPlayer player) {
        super(npc);
        this.player = player;
    }

    public ServerPlayer getPlayer() {
        return player;
    }
}
