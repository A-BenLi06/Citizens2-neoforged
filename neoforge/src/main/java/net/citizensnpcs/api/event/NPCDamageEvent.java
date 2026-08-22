package net.citizensnpcs.api.event;

import net.citizensnpcs.api.npc.NPC;
import net.minecraft.world.damagesource.DamageSource;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Called before an NPC takes damage. Cancelling stops the damage entirely; changing the amount changes what lands.
 * <p>
 * Upstream wraps Bukkit's {@code EntityDamageEvent} and exposes its {@code DamageCause} enum. Minecraft describes damage
 * with a {@link DamageSource} instead — which carries strictly more information (the attacker, the weapon, the damage
 * type's registry key and its tags) — so that is what is exposed here.
 */
public class NPCDamageEvent extends NPCEvent implements ICancellableEvent {
    private float damage;
    private final float originalDamage;
    private final DamageSource source;

    public NPCDamageEvent(NPC npc, DamageSource source, float damage) {
        super(npc);
        this.source = source;
        this.damage = damage;
        this.originalDamage = damage;
    }

    public float getDamage() {
        return damage;
    }

    public float getOriginalDamage() {
        return originalDamage;
    }

    public DamageSource getSource() {
        return source;
    }

    public void setDamage(double damage) {
        this.damage = (float) damage;
    }

    public void setDamage(float damage) {
        this.damage = damage;
    }
}
