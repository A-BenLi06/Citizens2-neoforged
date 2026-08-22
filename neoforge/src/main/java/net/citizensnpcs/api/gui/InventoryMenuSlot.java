package net.citizensnpcs.api.gui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * A single slot of a {@link InventoryMenu} page: what it displays, and what happens when it is clicked.
 * <p>
 * The default is deliberately locked: a slot with no click handler and no filter cancels every click, so the items a menu
 * uses as buttons cannot be picked up and carried off. A slot only becomes interactive once something calls
 * {@link #setFilter} or adds a handler that allows the click through.
 */
public class InventoryMenuSlot {
    private Set<InventoryAction> actionFilter;
    private final Container container;
    private final List<Consumer<CitizensInventoryClickEvent>> handlers = new ArrayList<>();
    private final int index;

    InventoryMenuSlot(Container container, int index) {
        this.container = container;
        this.index = index;
    }

    public void addClickHandler(Consumer<CitizensInventoryClickEvent> func) {
        handlers.add(func);
    }

    public void clear() {
        handlers.clear();
        actionFilter = null;
        setItemStack(ItemStack.EMPTY);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        InventoryMenuSlot other = (InventoryMenuSlot) obj;
        return index == other.index && Objects.equals(container, other.container);
    }

    public List<Consumer<CitizensInventoryClickEvent>> getClickHandlers() {
        return handlers;
    }

    /** @return the item in this slot, or null when it is empty — upstream's null-for-air convention */
    public ItemStack getCurrentItem() {
        ItemStack stack = container.getItem(index);
        return stack.isEmpty() ? null : stack;
    }

    public ItemStack getCurrentItemNonNull() {
        return container.getItem(index);
    }

    public Collection<InventoryAction> getFilter() {
        return actionFilter;
    }

    public int getIndex() {
        return index;
    }

    @Override
    public int hashCode() {
        return 31 * (31 + index) + (container == null ? 0 : container.hashCode());
    }

    public void setClickHandler(Consumer<CitizensInventoryClickEvent> handler) {
        handlers.clear();
        handlers.add(handler);
    }

    /** Replaces the name and lore of whatever is already in the slot, keeping the item itself. */
    public void setDescription(String description) {
        ItemStack stack = container.getItem(index);
        if (stack.isEmpty())
            return;
        String[] lines = description == null ? new String[0] : description.split("\r\n|\n|\\\\n|<br>");
        if (lines.length > 0) {
            MenuItems.setDisplayName(stack, lines[0]);
        }
        if (lines.length > 1) {
            MenuItems.setLore(stack, List.of(lines).subList(1, lines.length));
        }
        MenuItems.hideAttributes(stack);
        container.setItem(index, stack);
    }

    /**
     * Sets which actions are allowed through. Null or empty means every action, matching upstream where an empty filter
     * is "no restriction" rather than "nothing allowed".
     */
    public void setFilter(Collection<InventoryAction> filter) {
        this.actionFilter = filter == null || filter.isEmpty() ? EnumSet.allOf(InventoryAction.class)
                : EnumSet.copyOf(filter);
    }

    public void setItemStack(ItemStack stack) {
        container.setItem(index, stack == null ? ItemStack.EMPTY : stack);
    }

    public void setItemStack(ItemStack stack, String name) {
        setItemStack(stack, name, null);
    }

    public void setItemStack(ItemStack stack, String name, String description) {
        ItemStack copy = stack == null ? ItemStack.EMPTY : stack;
        MenuItems.setDisplayName(copy, name);
        if (description != null) {
            MenuItems.setLore(copy, description);
        }
        MenuItems.hideAttributes(copy);
        container.setItem(index, copy);
    }

    /** Builds the display item described by a {@link MenuSlot} annotation and puts it in the slot. */
    void initialise(MenuSlot data) {
        ItemStack stack = MenuItems.byId(data.material(), data.amount());
        if (!stack.isEmpty()) {
            MenuItems.hideAttributes(stack);
            if (!data.lore().equals("EMPTY")) {
                MenuItems.setLore(stack, data.lore());
            }
            if (!data.title().equals("EMPTY")) {
                MenuItems.setDisplayName(stack, data.title());
            }
        }
        container.setItem(index, stack);
    }

    void onClick(CitizensInventoryClickEvent event) {
        if (actionFilter == null && handlers.isEmpty() || actionFilter != null && !actionFilter.contains(event.getAction())) {
            event.setCancelled(true);
        }
        for (Consumer<CitizensInventoryClickEvent> handler : new ArrayList<>(handlers)) {
            handler.accept(event);
        }
    }
}
