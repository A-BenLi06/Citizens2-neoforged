package net.citizensnpcs.trait;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;

/** Whether an end crystal NPC shows the bedrock slab underneath it. */
@TraitName("endercrystaltrait")
public class EnderCrystalTrait extends Trait {
    @Persist
    private boolean showBase;

    public EnderCrystalTrait() {
        super("endercrystaltrait");
    }

    public boolean isShowBase() {
        return showBase;
    }

    @Override
    public void onSpawn() {
        updateModifiers();
    }

    public void setShowBase(boolean showBase) {
        this.showBase = showBase;
        updateModifiers();
    }

    private void updateModifiers() {
        if (npc.getCosmeticEntity() instanceof EndCrystal crystal) {
            crystal.setShowBottom(showBase);
        }
    }
}
