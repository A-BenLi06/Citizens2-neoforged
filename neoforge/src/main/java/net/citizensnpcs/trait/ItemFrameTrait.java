package net.citizensnpcs.trait;

import java.util.Locale;

import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.StoredItems;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;

/**
 * An item frame NPC's contents, rotation, facing, visibility and whether it can be interacted with.
 * <p>
 * Vanilla stores the rotation as an int, which is exactly the ordinal of Bukkit's eight-value rotation enum, so the enum
 * is reproduced here to keep the stored names. Bukkit's block-face names match vanilla's direction names for the six an
 * item frame can use. The direction setter and the fixed flag are both non-public in vanilla, which Bukkit exposes.
 */
@TraitName("itemframe")
public class ItemFrameTrait extends Trait {
    private Direction facing = Direction.NORTH;
    @Persist
    private Boolean fixed;
    private ItemStack item;
    private final StoredItems<String> stored = new StoredItems<>();
    private FrameRotation rotation = FrameRotation.NONE;
    @Persist
    private boolean visible = true;

    public ItemFrameTrait() {
        super("itemframe");
    }

    public Direction getFacing() {
        return facing;
    }

    public Boolean getFixed() {
        return fixed;
    }

    public ItemStack getItem() {
        return item;
    }

    public boolean hasUnresolvedItem() { return stored.contains("item"); }

    public FrameRotation getRotation() {
        return rotation;
    }

    public boolean isVisible() {
        return visible;
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        item = stored.load("item", key.getRelative("item"));
        facing = parseFacing(key.getString("facing"));
        rotation = parseRotation(key.getString("rotation"));
    }

    @Override
    public void save(DataKey key) {
        stored.save("item", key.getRelative("item"), item);
        key.setString("facing", facing == null ? "" : facing.name());
        key.setString("rotation", rotation == null ? "" : rotation.name());
    }

    @Override
    public void onSpawn() {
        if (!(npc.getCosmeticEntity() instanceof ItemFrame frame))
            return;
        if (rotation != null) {
            frame.setRotation(rotation.ordinal());
        }
        if (item != null) {
            frame.setItem(item.copy());
        }
        if (facing != null) {
            frame.setDirection(facing);
        }
        // upstream's default: a protected NPC's frame cannot have its contents taken or turned
        frame.fixed = fixed != null ? fixed : npc.isProtected();
        frame.setInvisible(!visible);
    }

    public void setFacing(Direction facing) {
        this.facing = facing;
    }

    public void setFixed(Boolean fixed) {
        this.fixed = fixed;
    }

    public void setItem(ItemStack item) {
        stored.clear();
        this.item = item;
    }

    public void setRotation(FrameRotation rotation) {
        this.rotation = rotation;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    /** {@code NORTH} for an empty or unrecognised value, matching upstream's default. */
    public static Direction parseFacing(String raw) {
        if (raw == null || raw.isEmpty())
            return Direction.NORTH;
        try {
            return Direction.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Direction.NORTH;
        }
    }

    public static FrameRotation parseRotation(String raw) {
        if (raw == null || raw.isEmpty())
            return FrameRotation.NONE;
        try {
            return FrameRotation.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return FrameRotation.NONE;
        }
    }

    /**
     * The eight frame rotations under Bukkit's names, which is what saves hold. Declaration order matters: the ordinal is
     * the int vanilla stores.
     */
    public enum FrameRotation {
        NONE,
        CLOCKWISE_45,
        CLOCKWISE,
        CLOCKWISE_135,
        FLIPPED,
        FLIPPED_45,
        COUNTER_CLOCKWISE,
        COUNTER_CLOCKWISE_45
    }
}
