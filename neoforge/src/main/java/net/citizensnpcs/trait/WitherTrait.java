package net.citizensnpcs.trait;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.boss.wither.WitherBoss;

/**
 * Persists wither state: the blue invulnerability shield, and whether arrows bounce off.
 * <p>
 * The shield is driven by the invulnerability tick counter, which vanilla counts down; holding it above zero is what
 * keeps the effect on. Anything unset falls back to whether the NPC is protected.
 */
@TraitName("withertrait")
public class WitherTrait extends Trait {
    @Persist("arrowshield")
    private Boolean arrowShield;
    @Persist("charged")
    private Boolean invulnerable;
    @Persist("invulnerableticks")
    private Integer invulnerableTicks;

    public WitherTrait() {
        super("withertrait");
    }

    public Boolean blocksArrows() {
        return arrowShield;
    }

    public Integer getInvulnerableTicks() {
        return invulnerableTicks;
    }

    public boolean isInvulnerable() {
        if (invulnerable != null)
            return invulnerable;
        if (invulnerableTicks != null)
            return invulnerableTicks > 0;
        return npc.isProtected();
    }

    @Override
    public void run() {
        if (!(npc.getCosmeticEntity() instanceof WitherBoss wither))
            return;
        if (invulnerable != null) {
            wither.setInvulnerableTicks(invulnerable ? 20 : 0);
        } else if (invulnerableTicks != null) {
            wither.setInvulnerableTicks(invulnerableTicks);
        } else {
            wither.setInvulnerableTicks(npc.isProtected() ? 20 : 0);
        }
        if (arrowShield != null) {
            npc.data().set(ARROW_SHIELD_METADATA, arrowShield);
        } else {
            npc.data().remove(ARROW_SHIELD_METADATA);
        }
    }

    public void setBlocksArrows(boolean arrowShield) {
        this.arrowShield = arrowShield;
    }

    public void setInvulnerable(boolean invulnerable) {
        this.invulnerable = invulnerable;
    }

    public void setInvulnerableTicks(int ticks) {
        invulnerableTicks = ticks;
    }

    /** Read by the projectile-damage handling rather than applied to the entity here. */
    public static final String ARROW_SHIELD_METADATA = "wither-arrow-shield";
}
