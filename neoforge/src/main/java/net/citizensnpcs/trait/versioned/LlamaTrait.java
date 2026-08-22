package net.citizensnpcs.trait.versioned;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.animal.horse.Llama;

/**
 * A llama NPC's coat and carrying strength.
 * <p>
 * Bukkit calls the coat a colour and vanilla a variant, but the four constant names are identical, so stored values
 * read back unchanged. Strength decides how many inventory columns the llama carries; vanilla only ever sets it while
 * spawning, so the setter is widened.
 */
@TraitName("llamatrait")
public class LlamaTrait extends Trait {
    @Persist
    private Llama.Variant color = Llama.Variant.BROWN;
    @Persist
    private int strength = 3;

    public LlamaTrait() {
        super("llamatrait");
    }

    public Llama.Variant getColor() {
        return color;
    }

    public int getStrength() {
        return strength;
    }

    @Override
    public void run() {
        if (!(npc.getCosmeticEntity() instanceof Llama llama))
            return;
        if (color != null) {
            llama.setVariant(color);
        }
        llama.setStrength(strength);
    }

    public void setColor(Llama.Variant color) {
        this.color = color;
    }

    public void setStrength(int strength) {
        this.strength = strength;
    }
}
