package net.citizensnpcs.trait;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.DyeColor;

/**
 * Persists a sheep's wool colour and sheared state.
 * <p>
 * Re-asserted every tick, which is also what keeps a player from permanently dyeing or shearing an NPC sheep — Bukkit
 * lets upstream cancel {@code SheepDyeWoolEvent}, and NeoForge has no equivalent event to cancel.
 */
@TraitName("sheeptrait")
public class SheepTrait extends Trait {
    @Persist("color")
    private DyeColor color = DyeColor.WHITE;
    @Persist("sheared")
    private boolean sheared = false;

    public SheepTrait() {
        super("sheeptrait");
    }

    public DyeColor getColor() {
        return color;
    }

    public boolean isSheared() {
        return sheared;
    }

    @Override
    public void run() {
        if (npc.getCosmeticEntity() instanceof Sheep sheep) {
            sheep.setSheared(sheared);
            sheep.setColor(color);
        }
    }

    public void setColor(DyeColor color) {
        this.color = color;
    }

    public void setSheared(boolean sheared) {
        this.sheared = sheared;
    }

    public boolean toggleSheared() {
        return sheared = !sheared;
    }
}
