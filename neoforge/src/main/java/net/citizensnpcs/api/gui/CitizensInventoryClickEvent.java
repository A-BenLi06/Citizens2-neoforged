package net.citizensnpcs.api.gui;

import java.util.Collection;
import java.util.List;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * One click on a menu slot, and the decision about whether it is allowed.
 * <p>
 * Upstream extends Bukkit's {@code InventoryClickEvent} and wraps a second one, because Bukkit hands it an event object
 * it must both read and mutate. Minecraft has no click event at all — {@code AbstractContainerMenu.clicked} just performs
 * the move — so this is a plain value object that {@link CitizensMenuContainer} fills in before the move happens.
 * Cancelling it means the move is never performed, which is the same contract Bukkit gives.
 * <p>
 * {@code null} versus an empty stack: upstream's getters return null for air so that {@code == null} checks read
 * naturally, and Citizens' own menus rely on that. Minecraft uses {@link ItemStack#EMPTY} instead, so both are offered —
 * {@link #getCurrentItem()} nulls out an empty stack, {@link #getCurrentItemNonNull()} never does.
 */
public class CitizensInventoryClickEvent {
    private final InventoryAction action;
    private boolean cancelled;
    private final MenuClickType click;
    private ItemStack currentItem;
    private final ItemStack cursor;
    private final int hotbarButton;
    private final ItemStack result;
    private final int slot;
    private final Collection<ServerPlayer> viewers;
    private final ServerPlayer whoClicked;
    private final AbstractContainerMenu container;

    public CitizensInventoryClickEvent(int slot, MenuClickType click, InventoryAction action, ItemStack currentItem,
            ItemStack cursor, int hotbarButton, Collection<ServerPlayer> viewers, int pickupAmount) {
        this(slot, click, action, currentItem, cursor, hotbarButton, viewers, pickupAmount, null, null);
    }

    public CitizensInventoryClickEvent(int slot, MenuClickType click, InventoryAction action, ItemStack currentItem,
            ItemStack cursor, int hotbarButton, Collection<ServerPlayer> viewers, int pickupAmount,
            ServerPlayer whoClicked, AbstractContainerMenu container) {
        this.whoClicked = whoClicked;
        this.container = container;
        this.slot = slot;
        this.click = click;
        this.action = action;
        this.currentItem = currentItem == null ? ItemStack.EMPTY : currentItem;
        this.cursor = cursor == null ? ItemStack.EMPTY : cursor;
        this.hotbarButton = hotbarButton;
        this.viewers = viewers;
        this.result = computeResult(pickupAmount);
    }

    public InventoryAction getAction() {
        return action;
    }

    public MenuClickType getClick() {
        return click;
    }

    /** @return the item in the clicked slot, or null when the slot is empty */
    public ItemStack getCurrentItem() {
        return currentItem.isEmpty() ? null : currentItem;
    }

    public ItemStack getCurrentItemNonNull() {
        return currentItem;
    }

    /** @return the item on the cursor, or null when the cursor is empty */
    public ItemStack getCursor() {
        return cursor.isEmpty() ? null : cursor;
    }

    public ItemStack getCursorNonNull() {
        return cursor;
    }

    public int getHotbarButton() {
        return hotbarButton;
    }

    /** @return what the slot would hold after the click, or null when it would be emptied */
    public ItemStack getResultItem() {
        return result == null || result.isEmpty() ? null : result;
    }

    public ItemStack getResultItemNonNull() {
        return result == null ? ItemStack.EMPTY : result;
    }

    public int getSlot() {
        return slot;
    }

    /** Every player currently looking at this menu, which is how a toggle plays its click sound to all of them. */
    public Collection<ServerPlayer> getViewers() {
        return viewers;
    }

    /**
     * @return the player who clicked, or null when the event was built without one (unit tests)
     */
    public ServerPlayer getWhoClicked() {
        return whoClicked;
    }

    /**
     * Replaces what the player is holding on the cursor. Upstream reaches this through the Bukkit view; a container menu
     * carries the cursor stack itself.
     */
    public void setCursor(ItemStack stack) {
        if (container != null) {
            container.setCarried(stack == null ? ItemStack.EMPTY : stack);
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public boolean isLeftClick() {
        return click.isLeftClick();
    }

    public boolean isRightClick() {
        return click.isRightClick();
    }

    public boolean isShiftClick() {
        return click.isShiftClick();
    }

    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    public void setCurrentItem(ItemStack item) {
        this.currentItem = item == null ? ItemStack.EMPTY : item;
    }

    /** Rewrites the lore of the item in this slot, which is how a toggle button shows its new state. */
    public void setCurrentItemDescription(String description) {
        if (currentItem.isEmpty())
            return;
        MenuItems.hideAttributes(currentItem);
        MenuItems.setLore(currentItem, description);
    }

    public void setCurrentItemDescription(List<String> description) {
        if (currentItem.isEmpty())
            return;
        MenuItems.hideAttributes(currentItem);
        MenuItems.setLore(currentItem, description);
    }

    /**
     * Predicts what the slot ends up holding, so a handler can inspect the outcome before deciding whether to allow it.
     * Upstream computes the same thing from the Bukkit action.
     */
    private ItemStack computeResult(int pickupAmount) {
        boolean slotEmpty = currentItem.isEmpty();
        ItemStack stack = slotEmpty ? cursor.copy() : currentItem.copy();
        int formerAmount = slotEmpty ? 0 : currentItem.getCount();
        switch (action) {
            case PICKUP_ONE:
                stack.setCount(formerAmount - 1);
                return stack;
            case PICKUP_SOME:
                stack.setCount(formerAmount - pickupAmount);
                return stack;
            case COLLECT_TO_CURSOR:
                stack.setCount(formerAmount - (pickupAmount >= 0 ? pickupAmount
                        : Math.min(formerAmount, Math.max(0, cursor.getMaxStackSize() - cursor.getCount()))));
                return stack;
            case PICKUP_HALF:
                stack.setCount((int) Math.floor(formerAmount / 2.0));
                return stack;
            case PICKUP_ALL:
                return null;
            case PLACE_ALL:
            case PLACE_SOME:
                stack.setCount(Math.min(formerAmount + cursor.getCount(), stack.getMaxStackSize()));
                return stack;
            case PLACE_ONE:
                stack.setCount(Math.min(formerAmount + 1, stack.getMaxStackSize()));
                return stack;
            case SWAP_WITH_CURSOR:
                return cursor.copy();
            default:
                // upstream cancels on anything it cannot predict; the container does that when the result is null
                return null;
        }
    }
}
