package net.citizensnpcs.trait;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.monster.Creeper;

/**
 * Persists a creeper's charged (powered) state.
 * <p>
 * Vanilla has no setter for it — the flag is only ever set when lightning strikes, or read back from NBT — so the synced
 * data key is written directly. Bukkit exposes {@code Creeper#setPowered}, which is why upstream needs nothing special.
 */
@TraitName("powered")
public class Powered extends Trait {
    @Persist("")
    private boolean powered;

    public Powered() {
        super("powered");
    }

    public boolean isPowered() {
        return powered;
    }

    @Override
    public void onSpawn() {
        if (npc.getEntity() instanceof Creeper creeper) {
            creeper.getEntityData().set(Creeper.DATA_IS_POWERED, powered);
        }
    }

    public void setPowered(boolean value) {
        powered = value;
        onSpawn();
    }
}
