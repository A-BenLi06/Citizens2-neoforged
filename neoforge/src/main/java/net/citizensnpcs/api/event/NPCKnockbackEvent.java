package net.citizensnpcs.api.event;

import net.citizensnpcs.api.npc.NPC;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Called before an NPC is knocked back. Cancelling leaves it where it stands; changing the strength or the direction
 * changes how far and which way it goes.
 * <p>
 * Upstream wraps its own knockback call inside its NMS bridge. The NeoForge counterpart is
 * {@link net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent}, fired from {@code LivingEntity.knockback} —
 * that is, at the one point vanilla actually applies knockback, so this covers every source of it rather than only the
 * ones Citizens itself triggers. {@link net.citizensnpcs.EventListen} translates between the two.
 */
public class NPCKnockbackEvent extends NPCEvent implements ICancellableEvent {
    private double ratioX;
    private double ratioZ;
    private float strength;

    public NPCKnockbackEvent(NPC npc, float strength, double ratioX, double ratioZ) {
        super(npc);
        this.strength = strength;
        this.ratioX = ratioX;
        this.ratioZ = ratioZ;
    }

    /** @return the x component of the direction the knockback pushes in */
    public double getRatioX() {
        return ratioX;
    }

    /** @return the z component of the direction the knockback pushes in */
    public double getRatioZ() {
        return ratioZ;
    }

    public float getStrength() {
        return strength;
    }

    public void setRatioX(double ratioX) {
        this.ratioX = ratioX;
    }

    public void setRatioZ(double ratioZ) {
        this.ratioZ = ratioZ;
    }

    public void setStrength(float strength) {
        this.strength = strength;
    }
}
