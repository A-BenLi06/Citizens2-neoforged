package net.citizensnpcs.trait;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.Strider;

/**
 * Persists whether a pig or strider NPC wears a saddle.
 * <p>
 * Upstream probes for Bukkit's {@code Steerable} interface and falls back to {@code Pig}. The two vanilla saddleable
 * steerables are handled explicitly here for the reason given on {@link #updateSaddleState()}.
 */
@TraitName("saddle")
public class Saddle extends Trait {
    @Persist("")
    private boolean saddle;

    public Saddle() {
        super("saddle");
    }

    @Override
    public void onSpawn() {
        updateSaddleState();
    }

    public boolean toggle() {
        saddle = !saddle;
        updateSaddleState();
        return saddle;
    }

    public void setSaddle(boolean saddle) {
        this.saddle = saddle;
        updateSaddleState();
    }

    @Override
    public String toString() {
        return "Saddle{" + saddle + "}";
    }

    private void updateSaddleState() {
        Entity entity = npc.getEntity();
        // vanilla's equipSaddle sets the flag true whatever it is handed and has no inverse, so the synced key is
        // written directly - that is the only way to express toggling the saddle back off
        if (entity instanceof Pig pig) {
            pig.getEntityData().set(Pig.DATA_SADDLE_ID, saddle);
        } else if (entity instanceof Strider strider) {
            strider.getEntityData().set(Strider.DATA_SADDLE_ID, saddle);
        }
    }

    public boolean useSaddle() {
        return saddle;
    }
}
