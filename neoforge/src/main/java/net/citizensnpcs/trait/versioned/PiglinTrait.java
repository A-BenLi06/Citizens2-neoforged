package net.citizensnpcs.trait.versioned;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.monster.piglin.Piglin;

/**
 * Whether a piglin NPC dances, as it does after winning a fight.
 * <p>
 * Upstream reaches this through its NMS bridge because Bukkit exposes no setter for it; vanilla's is public.
 */
@TraitName("piglintrait")
public class PiglinTrait extends Trait {
    @Persist
    private boolean dancing;

    public PiglinTrait() {
        super("piglintrait");
    }

    public boolean isDancing() {
        return dancing;
    }

    @Override
    public void run() {
        if (npc.getCosmeticEntity() instanceof Piglin piglin) {
            piglin.setDancing(dancing);
        }
    }

    public void setDancing(boolean dancing) {
        this.dancing = dancing;
    }
}
