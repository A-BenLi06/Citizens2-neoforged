package net.citizensnpcs.api.gui;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.world.Container;

/**
 * What a page is given when it is initialised: its slots, its backing container, its title, and a map of data carried
 * across page transitions.
 */
public class MenuContext {
    private final Container container;
    private final Map<String, Object> data = new HashMap<>();
    private final InventoryMenu menu;
    private final InventoryMenuSlot[] slots;
    private String title;

    public MenuContext(InventoryMenu menu, InventoryMenuSlot[] slots, Container container, String title) {
        this(menu, slots, container, title, Collections.emptyMap());
    }

    public MenuContext(InventoryMenu menu, InventoryMenuSlot[] slots, Container container, String title,
            Map<String, Object> data) {
        this.container = container;
        this.title = title;
        this.slots = slots;
        this.menu = menu;
        this.data.putAll(data);
    }

    public void clearSlots() {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null) {
                slots[i].clear();
            }
            slots[i] = null;
        }
    }

    /** Values that survive a transition to another page, and that {@link InjectContext} fields are filled from. */
    public Map<String, Object> data() {
        return data;
    }

    public Container getContainer() {
        return container;
    }

    public InventoryMenu getMenu() {
        return menu;
    }

    /** Slots are created on demand, so a page only pays for the ones it touches. */
    public InventoryMenuSlot getSlot(int i) {
        if (slots[i] == null)
            return slots[i] = new InventoryMenuSlot(container, i);
        return slots[i];
    }

    public int getSize() {
        return slots.length;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
        menu.updateTitle(title);
    }
}
