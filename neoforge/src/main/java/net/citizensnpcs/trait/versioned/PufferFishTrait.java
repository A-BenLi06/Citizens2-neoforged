package net.citizensnpcs.trait.versioned;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.animal.Pufferfish;

/**
 * How far a pufferfish NPC is puffed up, from 0 to 2.
 * <p>
 * Upstream's trait stores the value and never applies it — it has neither a run nor a spawn hook, so the puff state set
 * through its command never reaches the entity. It is something a player can see, so it is applied here.
 * <p>
 * Vanilla re-derives the state each tick from nearby threats, so it is re-asserted rather than set once. Only written
 * when it has drifted: {@code setPuffState} plays a sound on every change.
 */
@TraitName("pufferfishtrait")
public class PufferFishTrait extends Trait {
    @Persist
    private int puffState = 0;

    public PufferFishTrait() {
        super("pufferfishtrait");
    }

    public int getPuffState() {
        return puffState;
    }

    @Override
    public void run() {
        if (npc.getCosmeticEntity() instanceof Pufferfish fish && fish.getPuffState() != puffState) {
            fish.setPuffState(puffState);
        }
    }

    public void setPuffState(int state) {
        puffState = Math.min(Math.max(state, 0), 2);
    }
}
