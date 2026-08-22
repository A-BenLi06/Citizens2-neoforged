package net.citizensnpcs.trait.versioned;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.animal.sniffer.Sniffer;

/**
 * A sniffer NPC animation state.
 * <p>
 * Only re-asserted when it drifts: each transition restarts the animation, so writing it every tick would freeze the
 * sniffer on the first frame. Unset means "leave it alone", as upstream.
 */
@TraitName("sniffertrait")
public class SnifferTrait extends Trait {
    @Persist
    private Sniffer.State state = null;

    public SnifferTrait() {
        super("sniffertrait");
    }

    public Sniffer.State getState() {
        return state;
    }

    @Override
    public void run() {
        if (state != null && npc.getCosmeticEntity() instanceof Sniffer sniffer && sniffer.getState() != state) {
            sniffer.transitionTo(state);
        }
    }

    public void setState(Sniffer.State state) {
        this.state = state;
    }
}
