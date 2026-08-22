package net.citizensnpcs.trait.versioned;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.monster.Phantom;

/** A phantom NPC size, which also scales its hitbox. */
@TraitName("phantomtrait")
public class PhantomTrait extends Trait {
    @Persist
    private int size = 1;

    public PhantomTrait() {
        super("phantomtrait");
    }

    public int getSize() {
        return size;
    }

    @Override
    public void run() {
        if (npc.getCosmeticEntity() instanceof Phantom phantom) {
            phantom.setPhantomSize(size);
        }
    }

    public void setSize(int size) {
        this.size = size;
    }
}
