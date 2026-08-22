package net.citizensnpcs.api.gui;

/**
 * What a click would do to the items involved, at the granularity Bukkit reports it.
 * <p>
 * Minecraft has no such concept: its {@code clicked()} performs the move and never names it. Citizens' menus branch on
 * these names — {@code PICKUP_ALL} meaning "the player took the item out of the slot" and {@code PLACE_ALL} meaning "the
 * player put one in" is how the equipment editors tell equip from unequip — so the vocabulary is reproduced and
 * {@link CitizensInventoryClickEvent} derives the value from the vanilla click plus the slot and cursor contents.
 * <p>
 * The whole Bukkit set is kept, not just the handful Citizens branches on, so that a {@code filter} written against
 * upstream source keeps its exact meaning.
 */
public enum InventoryAction {
    CLONE_STACK,
    COLLECT_TO_CURSOR,
    DROP_ALL_CURSOR,
    DROP_ALL_SLOT,
    DROP_ONE_CURSOR,
    DROP_ONE_SLOT,
    HOTBAR_MOVE_AND_READD,
    HOTBAR_SWAP,
    MOVE_TO_OTHER_INVENTORY,
    NOTHING,
    PICKUP_ALL,
    PICKUP_HALF,
    PICKUP_ONE,
    PICKUP_SOME,
    PLACE_ALL,
    PLACE_ONE,
    PLACE_SOME,
    SWAP_WITH_CURSOR,
    UNKNOWN;
}
