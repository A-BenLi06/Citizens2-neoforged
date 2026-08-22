package net.citizensnpcs.trait;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.Messages;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.monster.Slime;

/**
 * Persists slime (and magma cube) size.
 */
@TraitName("slimesize")
public class SlimeSize extends Trait {
    @Persist
    private int size = 3;

    public SlimeSize() {
        super("slimesize");
    }

    public void describe(CommandSourceStack sender) {
        Messaging.sendTr(sender, Messages.SIZE_DESCRIPTION, npc.getName(), size);
    }

    public int getSize() {
        return size;
    }

    @Override
    public void onSpawn() {
        if (npc.getCosmeticEntity() instanceof Slime slime) {
            // false: do not reset health to the size's default, the NPC's own health is authoritative
            slime.setSize(size, false);
        }
    }

    public void setSize(int size) {
        this.size = size;
    }
}
