package net.citizensnpcs.api.gui;

import net.minecraft.world.inventory.ClickType;

/**
 * How a slot was clicked, at the granularity Bukkit reports it.
 * <p>
 * Minecraft's own {@link net.minecraft.world.inventory.ClickType} is coarser: it says {@code PICKUP} and leaves left
 * versus right to the button number. Citizens' menu annotations are written against the finer Bukkit names, so those are
 * reproduced here and {@link #of} does the widening.
 * <p>
 * Nothing in Citizens actually filters on a specific value — every {@code filter} in the codebase is empty — so this
 * exists for API compatibility and for {@link CitizensInventoryClickEvent#isRightClick()} and friends.
 */
public enum MenuClickType {
    CONTROL_DROP,
    CREATIVE,
    DOUBLE_CLICK,
    DROP,
    LEFT,
    MIDDLE,
    NUMBER_KEY,
    RIGHT,
    SHIFT_LEFT,
    SHIFT_RIGHT,
    SWAP_OFFHAND,
    UNKNOWN;

    public boolean isLeftClick() {
        return this == LEFT || this == SHIFT_LEFT || this == DOUBLE_CLICK || this == CREATIVE;
    }

    public boolean isRightClick() {
        return this == RIGHT || this == SHIFT_RIGHT;
    }

    public boolean isShiftClick() {
        return this == SHIFT_LEFT || this == SHIFT_RIGHT || this == CONTROL_DROP;
    }

    /** Widens a vanilla click into the Bukkit-shaped name Citizens expects. */
    public static MenuClickType of(ClickType type, int button) {
        switch (type) {
            case PICKUP:
                return button == 1 ? RIGHT : LEFT;
            case QUICK_MOVE:
                return button == 1 ? SHIFT_RIGHT : SHIFT_LEFT;
            case SWAP:
                // vanilla sends the offhand swap as button 40, and hotbar keys as 0-8
                return button == 40 ? SWAP_OFFHAND : NUMBER_KEY;
            case CLONE:
                return MIDDLE;
            case THROW:
                return button == 1 ? CONTROL_DROP : DROP;
            case PICKUP_ALL:
                return DOUBLE_CLICK;
            default:
                return UNKNOWN;
        }
    }
}
