package net.citizensnpcs.trait;

import java.util.Locale;
import java.util.Map;

import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.animal.horse.Markings;
import net.minecraft.world.entity.animal.horse.Variant;
import net.minecraft.world.item.ItemStack;

/**
 * A horse NPC's coat, markings, saddle, armour, chest and tamed state.
 * <p>
 * Vanilla packs coat and markings into one synced int and writes them only together, through a private method, so both
 * are always written at once. Bukkit's coat names match vanilla's variant names; its style {@code WHITEFIELD} is
 * vanilla's {@code WHITE_FIELD}, so that one value is mapped and both spellings are accepted.
 * <p>
 * The saddle is a real container slot here rather than the synced flag a pig or strider uses, so it can be cleared as
 * well as set. It is also read back every tick, as upstream does, so a saddle a player puts on by hand is persisted.
 */
@TraitName("horsemodifiers")
public class HorseModifiers extends Trait {
    @Persist("armor")
    private ItemStack armor = null;
    @Persist("carryingChest")
    private boolean carryingChest;
    @Persist("color")
    private Variant color = Variant.CREAMY;
    @Persist("saddle")
    private ItemStack saddle = null;
    private Markings style = Markings.NONE;
    @Persist("tamed")
    private boolean tamed;

    public HorseModifiers() {
        super("horsemodifiers");
    }

    public ItemStack getArmor() {
        return armor;
    }

    public Variant getColor() {
        return color;
    }

    public ItemStack getSaddle() {
        return saddle;
    }

    public Markings getStyle() {
        return style;
    }

    public boolean isCarryingChest() {
        return carryingChest;
    }

    public boolean isTamed() {
        return tamed;
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        style = parseStyle(key.getString("style"));
    }

    @Override
    public void save(DataKey key) {
        key.setString("style", BUKKIT_STYLE_NAMES.getOrDefault(style, style.name()));
    }

    @Override
    public void onSpawn() {
        updateModifiers();
    }

    /**
     * Reads the saddle and armour back off the entity, so equipment changed in the horse's own inventory is persisted.
     */
    @Override
    public void run() {
        if (npc.getCosmeticEntity() instanceof Horse horse) {
            saddle = horse.inventory.getItem(0);
            armor = horse.getBodyArmorItem();
        }
    }

    private void updateModifiers() {
        if (npc.getCosmeticEntity() instanceof Horse horse) {
            horse.setVariantAndMarkings(color == null ? Variant.CREAMY : color, style == null ? Markings.NONE : style);
            horse.equipSaddle(saddle == null ? ItemStack.EMPTY : saddle.copy(), null);
            horse.setBodyArmorItem(armor == null ? ItemStack.EMPTY : armor.copy());
            horse.setTamed(tamed);
        }
        if (npc.getCosmeticEntity() instanceof AbstractChestedHorse chested) {
            chested.setChest(carryingChest);
        }
    }

    public void setArmor(ItemStack armor) {
        this.armor = armor;
        updateModifiers();
    }

    public void setCarryingChest(boolean carryingChest) {
        this.carryingChest = carryingChest;
        updateModifiers();
    }

    public void setColor(Variant color) {
        this.color = color;
        updateModifiers();
    }

    public void setSaddle(ItemStack saddle) {
        this.saddle = saddle;
        updateModifiers();
    }

    public void setStyle(Markings style) {
        this.style = style;
        updateModifiers();
    }

    public void setTamed(boolean tamed) {
        this.tamed = tamed;
        updateModifiers();
    }

    /** Accepts either spelling; {@code NONE} for an empty or unrecognised value. */
    public static Markings parseStyle(String raw) {
        if (raw == null || raw.isEmpty())
            return Markings.NONE;
        String upper = raw.toUpperCase(Locale.ROOT);
        for (Map.Entry<Markings, String> entry : BUKKIT_STYLE_NAMES.entrySet()) {
            if (entry.getValue().equals(upper))
                return entry.getKey();
        }
        try {
            return Markings.valueOf(upper);
        } catch (IllegalArgumentException ex) {
            return Markings.NONE;
        }
    }

    /** The one marking Bukkit spells differently. */
    private static final Map<Markings, String> BUKKIT_STYLE_NAMES = Map.of(Markings.WHITE_FIELD, "WHITEFIELD");
}
