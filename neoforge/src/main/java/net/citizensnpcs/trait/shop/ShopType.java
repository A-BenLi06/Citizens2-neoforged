package net.citizensnpcs.trait.shop;

/**
 * How a shop is presented, and how big it is.
 * <p>
 * The three navigation slots sit on the bottom row of the container by default; a one-row shop has nowhere else to put
 * them, so it uses the row it has.
 */
public enum ShopType {
    CHEST_1X9(1 * 9, 7, 6, 8),
    CHEST_2X9(2 * 9),
    CHEST_3X9(3 * 9),
    CHEST_4X9(4 * 9),
    DEFAULT(5 * 9),
    /** Vanilla's villager trading screen rather than a chest. */
    TRADER(5 * 9);

    private final int editSlotIndex;
    private final int inventorySize;
    private final int nextSlotIndex;
    private final int prevSlotIndex;

    ShopType(int inventorySize) {
        this(inventorySize, inventorySize - 9 + 3, inventorySize - 9 + 4, inventorySize - 9 + 5);
    }

    ShopType(int inventorySize, int prevSlotIndex, int editSlotIndex, int nextSlotIndex) {
        this.inventorySize = inventorySize;
        this.prevSlotIndex = prevSlotIndex;
        this.editSlotIndex = editSlotIndex;
        this.nextSlotIndex = nextSlotIndex;
    }

    public int getEditSlotIndex() {
        return editSlotIndex;
    }

    public int getInventorySize() {
        return inventorySize;
    }

    public int getNextSlotIndex() {
        return nextSlotIndex;
    }

    public int getPrevSlotIndex() {
        return prevSlotIndex;
    }
}
