package net.citizensnpcs.trait;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.monster.EnderMan;

/**
 * Persists whether an enderman NPC shows its angry (screaming) face.
 * <p>
 * Vanilla only sets this from its own stare-detection AI, so the synced data key is written directly — the same reason
 * {@link Powered} does.
 */
@TraitName("endermantrait")
public class EndermanTrait extends Trait {
    @Persist("angry")
    private boolean angry;

    public EndermanTrait() {
        super("endermantrait");
    }

    public boolean isAngry() {
        return angry;
    }

    @Override
    public void run() {
        if (npc.getCosmeticEntity() instanceof EnderMan enderman) {
            enderman.getEntityData().set(EnderMan.DATA_CREEPY, angry);
        }
    }

    public void setAngry(boolean angry) {
        this.angry = angry;
    }

    public boolean toggleAngry() {
        return angry = !angry;
    }
}
