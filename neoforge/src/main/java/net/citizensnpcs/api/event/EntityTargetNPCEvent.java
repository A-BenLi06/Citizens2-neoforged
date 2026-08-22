package net.citizensnpcs.api.event;

import net.citizensnpcs.api.npc.NPC;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Called when something starts targeting an NPC. Cancelling it stops the target being taken.
 * <p>
 * Upstream wraps Bukkit's {@code EntityTargetEvent} and forwards cancellation to it; here the platform event is
 * {@link net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent}, so the targeter is carried directly rather
 * than through a wrapped event.
 */
public class EntityTargetNPCEvent extends NPCEvent implements ICancellableEvent {
    private final LivingEntity targeter;

    public EntityTargetNPCEvent(NPC npc, LivingEntity targeter) {
        super(npc);
        this.targeter = targeter;
    }

    /**
     * @return the entity that is about to target the NPC
     */
    public LivingEntity getTargeter() {
        return targeter;
    }
}
