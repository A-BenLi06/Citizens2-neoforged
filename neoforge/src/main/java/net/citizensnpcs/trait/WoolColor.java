package net.citizensnpcs.trait;

import java.util.Locale;

import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.DyeColor;

/**
 * Persists a sheep NPC's wool colour, stored as a bare string rather than a nested key.
 * <p>
 * Upstream cancels {@code SheepDyeWoolEvent} to stop players recolouring the NPC. NeoForge has no such event, so the
 * colour is re-asserted each tick instead: a player can dye the sheep but it snaps back, which is the same outcome.
 * <p>
 * Superseded by {@link SheepTrait}, which also covers the sheared flag; kept because existing saves use it.
 */
@TraitName("woolcolor")
public class WoolColor extends Trait {
    private DyeColor color = DyeColor.WHITE;

    public WoolColor() {
        super("woolcolor");
    }

    public DyeColor getColor() {
        return color;
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        color = parse(key.getString(""));
    }

    /** Vanilla's colour names match Bukkit's, so a saved value reads back directly. */
    private static DyeColor parse(String raw) {
        if (raw == null || raw.isEmpty())
            return DyeColor.WHITE;
        try {
            return DyeColor.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return DyeColor.WHITE;
        }
    }

    @Override
    public void run() {
        if (npc.getEntity() instanceof Sheep sheep) {
            sheep.setColor(color);
        }
    }

    @Override
    public void save(DataKey key) {
        key.setString("", color.name());
    }

    public void setColor(DyeColor color) {
        this.color = color;
        run();
    }

    @Override
    public String toString() {
        return "WoolColor{" + color.name() + "}";
    }
}
