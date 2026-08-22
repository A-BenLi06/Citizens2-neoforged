package net.citizensnpcs.api.gui;

import net.minecraft.world.inventory.MenuType;

/**
 * The kind of container a menu page opens as.
 * <p>
 * Upstream uses Bukkit's {@code InventoryType}, which covers every container in the game; only the types Citizens
 * actually opens are kept here, each mapped to the vanilla {@link MenuType} that matches it exactly. A type with no
 * clean counterpart is better refused at startup than opened as something subtly different, which is why the enum is
 * short — upstream's own size table ends in {@code default: throw new UnsupportedOperationException()} for the same
 * reason.
 * <p>
 * {@link #CHEST} is the only one whose size varies: {@code @Menu(dimensions = {rows, cols})} decides it, rounded up to a
 * whole number of rows and capped at six, exactly as upstream rounds to a multiple of nine.
 */
public enum InventoryType {
    /** 9 columns by 1-6 rows, sized from the menu dimensions. */
    CHEST(-1, 9),
    /** 3x3, as a dispenser or dropper. */
    DISPENSER(9, 3),
    DROPPER(9, 3),
    /** A single row of five. */
    HOPPER(5, 5),
    /** 9x3, and the only chest-shaped container that is not a chest. */
    SHULKER_BOX(27, 9),
    /**
     * Two input slots and an output slot, plus a text field. The text field is the point: it is the only way to let a
     * player type into a GUI, and {@link InputMenus#stringSetter} uses it.
     */
    ANVIL(3, 3);

    private final int columns;
    private final int size;

    InventoryType(int size, int columns) {
        this.size = size;
        this.columns = columns;
    }

    /** How many slots a row of this container holds, for translating {@code {row, col}} positions into an index. */
    public int getColumns() {
        return columns;
    }

    /**
     * @param dimensions
     *            the {@code @Menu} dimensions, consulted only by {@link #CHEST}
     * @return the container size in slots
     */
    public int getSize(int[] dimensions) {
        if (size != -1)
            return size;
        int requested = dimensions[0] * dimensions[1];
        if (requested % 9 != 0) {
            requested += 9 - requested % 9;
        }
        return Math.max(9, Math.min(54, requested));
    }

    /** @return the vanilla menu type for a container of this kind and size */
    public MenuType<?> toMenuType(int size) {
        switch (this) {
            case HOPPER:
                return MenuType.HOPPER;
            case ANVIL:
                return MenuType.ANVIL;
            case DISPENSER:
            case DROPPER:
                return MenuType.GENERIC_3x3;
            case SHULKER_BOX:
                return MenuType.SHULKER_BOX;
            default:
                break;
        }
        switch (size / 9) {
            case 1:
                return MenuType.GENERIC_9x1;
            case 2:
                return MenuType.GENERIC_9x2;
            case 3:
                return MenuType.GENERIC_9x3;
            case 4:
                return MenuType.GENERIC_9x4;
            case 5:
                return MenuType.GENERIC_9x5;
            default:
                return MenuType.GENERIC_9x6;
        }
    }
}
