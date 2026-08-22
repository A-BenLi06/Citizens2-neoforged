package net.citizensnpcs.trait.versioned;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.animal.Bee;

/**
 * A bee NPC's nectar, stinger and anger.
 * <p>
 * Bukkit's setAnger is vanilla's remaining persistent anger time, in ticks. Neither the nectar nor the stinger flag has
 * a setter of any visibility — vanilla writes both from its own AI — so both are widened.
 */
@TraitName("beetrait")
public class BeeTrait extends Trait {
    @Persist
    private int anger;
    @Persist
    private boolean nectar = false;
    @Persist
    private boolean stung = false;

    public BeeTrait() {
        super("beetrait");
    }

    public int getAnger() {
        return anger;
    }

    public boolean hasNectar() {
        return nectar;
    }

    public boolean hasStung() {
        return stung;
    }

    @Override
    public void run() {
        if (!(npc.getCosmeticEntity() instanceof Bee bee))
            return;
        bee.setHasStung(stung);
        bee.setRemainingPersistentAngerTime(anger);
        bee.setHasNectar(nectar);
    }

    public void setAnger(int anger) {
        this.anger = anger;
    }

    public void setNectar(boolean nectar) {
        this.nectar = nectar;
    }

    public void setStung(boolean stung) {
        this.stung = stung;
    }
}
