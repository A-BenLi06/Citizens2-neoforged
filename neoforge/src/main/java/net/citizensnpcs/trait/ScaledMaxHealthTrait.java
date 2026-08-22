package net.citizensnpcs.trait;

import net.citizensnpcs.api.event.NPCDamageEvent;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitEventHandler;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Gives an NPC more health than a mob is allowed to have.
 * <p>
 * Minecraft caps a max-health attribute at 1024, so an NPC that should have, say, ten thousand hit points is given the
 * cap and its incoming damage is divided by the same factor: it takes ten thousand points of damage to kill it while the
 * server only ever sees numbers a mob can hold.
 * <p>
 * Upstream's version of this never runs. Its damage handler reads
 * {@code if (maxHealth == null || (npc.getEntity() instanceof LivingEntity)) return;} — the second test is inverted, so
 * it returns for exactly the entities it is meant to act on, and would have thrown a cast error for the rest. The
 * arithmetic behind it is inverted too ({@code cap / damage} rather than {@code damage * cap / maxHealth}), so a
 * one-point hit would have taken off a thousand. Both are fixed here; the trait is otherwise as upstream describes it.
 */
@TraitName("scaledhealthtrait")
public class ScaledMaxHealthTrait extends Trait {
    @Persist
    private Double maxHealth;

    public ScaledMaxHealthTrait() {
        super("scaledhealthtrait");
    }

    public Double getMaxHealth() {
        return maxHealth;
    }

    /** How much of a real hit point one nominal hit point is worth. */
    private double scale() {
        return maxHealth == null || maxHealth <= MAX_VALUE ? 1 : MAX_VALUE / maxHealth;
    }

    @TraitEventHandler
    public void onDamage(NPCDamageEvent event) {
        if (maxHealth == null || maxHealth <= MAX_VALUE || event.isCanceled())
            return;
        if (!(npc.getEntity() instanceof LivingEntity))
            return;
        event.setDamage(event.getDamage() * scale());
    }

    @Override
    public void onSpawn() {
        if (maxHealth == null || !(npc.getEntity() instanceof LivingEntity living))
            return;
        AttributeInstance attribute = living.getAttribute(Attributes.MAX_HEALTH);
        if (attribute == null)
            return;
        attribute.setBaseValue(Math.min(MAX_VALUE, maxHealth));
        // vanilla does not raise current health when the maximum grows, so a freshly spawned NPC would sit at 20
        living.setHealth((float) attribute.getValue());
    }

    public void setMaxHealth(Double maxHealth) {
        this.maxHealth = maxHealth;
    }

    /** Vanilla's own ceiling on {@link Attributes#MAX_HEALTH}. */
    private static final double MAX_VALUE = 1024;
}
