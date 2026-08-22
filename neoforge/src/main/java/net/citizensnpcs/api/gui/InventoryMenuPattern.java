package net.citizensnpcs.api.gui;

import java.util.List;

/** The concrete slots and transitions a {@link MenuPattern} expanded into. */
public class InventoryMenuPattern {
    private final MenuPattern data;
    private final List<InventoryMenuSlot> slots;
    private final List<InventoryMenuTransition> transitions;

    public InventoryMenuPattern(MenuPattern data, List<InventoryMenuSlot> slots,
            List<InventoryMenuTransition> transitions) {
        this.data = data;
        this.slots = slots;
        this.transitions = transitions;
    }

    public MenuPattern getData() {
        return data;
    }

    public List<InventoryMenuSlot> getSlots() {
        return slots;
    }

    public List<InventoryMenuTransition> getTransitions() {
        return transitions;
    }
}
