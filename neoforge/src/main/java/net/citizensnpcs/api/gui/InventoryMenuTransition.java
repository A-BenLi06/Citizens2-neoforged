package net.citizensnpcs.api.gui;

/** A slot that moves to another page when clicked. */
public class InventoryMenuTransition {
    private final Class<? extends InventoryMenuPage> next;
    private final InventoryMenuSlot slot;

    public InventoryMenuTransition(InventoryMenuSlot slot, Class<? extends InventoryMenuPage> next) {
        this.slot = slot;
        this.next = next;
    }

    /** @return the page to move to when {@code clicked} is this transition's slot, else null */
    public Class<? extends InventoryMenuPage> accept(InventoryMenuSlot clicked) {
        return slot.equals(clicked) ? next : null;
    }

    public InventoryMenuSlot getSlot() {
        return slot;
    }
}
