package net.citizensnpcs.trait.versioned;

import java.util.Locale;

import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemDisplayContext;

/**
 * How an item display NPC holds its item — the same nine contexts the vanilla item model uses.
 * <p>
 * Bukkit's enum names are vanilla's <em>serialized</em> names uppercased ({@code THIRDPERSON_RIGHTHAND} for
 * {@code thirdperson_righthand}), which is not the same as vanilla's Java constant names
 * ({@code THIRD_PERSON_RIGHT_HAND}). Matching on the serialized name therefore reads Bukkit's saves directly, needs no
 * mapping table to keep in step, and picks up contexts added by other mods — the enum is NeoForge-extensible.
 */
@TraitName("itemdisplaytrait")
public class ItemDisplayTrait extends Trait {
    private ItemDisplayContext transform;

    public ItemDisplayTrait() {
        super("itemdisplaytrait");
    }

    public ItemDisplayContext getTransform() {
        return transform;
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        transform = parse(key.getString("transform"));
    }

    @Override
    public void save(DataKey key) {
        key.setString("transform",
                transform == null ? "" : transform.getSerializedName().toUpperCase(Locale.ROOT));
    }

    @Override
    public void onSpawn() {
        apply();
    }

    private void apply() {
        if (transform != null && npc.getCosmeticEntity() instanceof Display.ItemDisplay display) {
            display.setItemTransform(transform);
        }
    }

    public void setTransform(ItemDisplayContext transform) {
        this.transform = transform;
        apply();
    }

    /** Null for an empty or unrecognised value; accepts either the serialized name or the Java constant name. */
    public static ItemDisplayContext parse(String raw) {
        if (raw == null || raw.isEmpty())
            return null;
        String lower = raw.toLowerCase(Locale.ROOT);
        for (ItemDisplayContext candidate : ItemDisplayContext.values()) {
            if (candidate.getSerializedName().equals(lower))
                return candidate;
        }
        try {
            return ItemDisplayContext.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
