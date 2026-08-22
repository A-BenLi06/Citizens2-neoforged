package net.citizensnpcs.trait.versioned;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.animal.SnowGolem;

/**
 * Snow golem appearance. Bukkit calls a golem with no pumpkin on its head "derp"; vanilla models the same thing the
 * other way round, as a pumpkin flag.
 * <p>
 * {@code formSnow} is applied from {@link net.citizensnpcs.EventListen}, not from here — vanilla lays the trail in
 * {@code aiStep} rather than in a goal, so it survives the NPC having its AI switched off and has to be vetoed through
 * the mob-griefing hook.
 */
@TraitName("snowmantrait")
public class SnowmanTrait extends Trait {
    @Persist("derp")
    private boolean derp;
    @Persist
    private boolean formSnow;

    public SnowmanTrait() {
        super("snowmantrait");
    }

    public boolean isDerp() {
        return derp;
    }

    @Override
    public void run() {
        if (npc.getCosmeticEntity() instanceof SnowGolem golem) {
            golem.setPumpkin(!derp);
        }
    }

    public void setDerp(boolean derp) {
        this.derp = derp;
    }

    public void setFormSnow(boolean snow) {
        formSnow = snow;
    }

    public boolean shouldFormSnow() {
        return formSnow;
    }
}
