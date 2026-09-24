package net.citizensnpcs.api.gui;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Minecraft container behind one viewer's view of an {@link InventoryMenu}.
 * <p>
 * This is where the port and upstream differ most. Bukkit hands a plugin an {@code InventoryClickEvent} it can cancel
 * before anything moves; Minecraft has no such hook — {@link #clicked} <em>is</em> the move. So the click is translated
 * into a {@link CitizensInventoryClickEvent}, offered to the menu before a native move or each bulk-collection source.
 * A cancelled click is followed by {@link #broadcastFullState()} so the client, which has already predicted
 * the move locally, is put back in step.
 * <p>
 * Several viewers can share one menu, and therefore one {@link Container}: each gets its own container instance over the
 * same backing container, which is how a change made by one viewer shows up for the others.
 */
public class CitizensMenuContainer extends AbstractContainerMenu {
    private boolean transferring;
    private final int columns;
    private final Container container;
    private final InventoryMenu menu;
    private final int menuSize;

    public CitizensMenuContainer(MenuType<?> type, int containerId, Inventory playerInventory, Container container,
            int rows, int columns, InventoryMenu menu) {
        super(type, containerId);
        this.container = container;
        this.menu = menu;
        this.menuSize = container.getContainerSize();
        this.columns = columns;
        container.startOpen(playerInventory.player);

        for (int index = 0; index < menuSize; index++) {
            int row = index / columns;
            int col = index % columns;
            addSlot(new Slot(container, index, 8 + col * 18, 18 + row * 18));
        }
        int menuHeight = 18 + rows * 18 + 13;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, menuHeight + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, menuHeight + 58));
        }
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (menu != null && !menu.isCurrent(this, player)) return;
        if (menu == null || clickType != ClickType.PICKUP_ALL && clickType != ClickType.QUICK_MOVE
                && clickType != ClickType.QUICK_CRAFT) {
            handleClick(slotId, button, clickType, player);
            return;
        }
        if (transferring) return;
        transferring = true;
        try {
            handleClick(slotId, button, clickType, player);
        } finally {
            transferring = false;
        }
    }

    private void handleClick(int slotId, int button, ClickType clickType, Player player) {
        if (menu != null && clickType != ClickType.QUICK_CRAFT && quickcraftStatus != 0) {
            // Native non-drag clicks cancel an unfinished drag without performing another action.
            resetQuickCraft();
            broadcastFullState();
            return;
        }
        if (menu != null && clickType == ClickType.QUICK_CRAFT) {
            dragClick(slotId, button, player);
            return;
        }
        if (menu == null || slotId < 0 || slotId >= slots.size()) {
            super.clicked(slotId, button, clickType, player);
            return;
        }
        boolean inMenu = slotId < menuSize;
        if (clickType == ClickType.QUICK_MOVE) {
            shiftClick(slotId, button, player);
            if (menu.isCurrent(this, player)) broadcastFullState();
            return;
        }
        if (!inMenu) {
            // the player's own inventory is theirs to rearrange
            clickNative(slotId, button, clickType, player);
            return;
        }
        ItemStack current = slots.get(slotId).getItem();
        CitizensInventoryClickEvent event = new CitizensInventoryClickEvent(slotId,
                MenuClickType.of(clickType, button), actionFor(clickType, button, current, getCarried()),
                current.copy(), getCarried().copy(), clickType == ClickType.SWAP ? button : -1, menu.getViewers(),
                clickType == ClickType.PICKUP_ALL ? 0 : -1,
                player instanceof ServerPlayer clicker ? clicker : null, this);
        menu.handleClick(event);
        if (event.isCancelled() || !menu.isCurrent(this, player)) {
            if (menu.isCurrent(this, player)) broadcastFullState();
            return;
        }
        clickNative(slotId, button, clickType, player);
    }

    private void clickNative(int slotId, int button, ClickType clickType, Player player) {
        if (clickType != ClickType.PICKUP_ALL) {
            super.clicked(slotId, button, clickType, player);
            return;
        }
        collectToCursor(slotId, button, player);
        // The vanilla client predicts collection from every matching slot, including locked menu controls.
        if (menu.isCurrent(this, player)) broadcastFullState();
    }

    private void collectToCursor(int slotId, int button, Player player) {
        Slot origin = slots.get(slotId);
        ItemStack cursor = getCarried();
        if (cursor.isEmpty() || origin.hasItem() && origin.mayPickup(player)) return;
        Set<Slot> visited = new HashSet<>();
        int start = button == 0 ? 0 : slots.size() - 1;
        int direction = button == 0 ? 1 : -1;
        // Preserve native direction, partial-stack-first passes, stack compatibility and Slot pickup rules.
        for (int pass = 0; pass < 2; pass++) {
            for (int index = start; index >= 0 && index < slots.size() && cursor.getCount() < cursor.getMaxStackSize(); index += direction) {
                if (!menu.isCurrent(this, player) || getCarried() != cursor) return;
                Slot source = slots.get(index);
                ItemStack item = source.getItem();
                if (item.isEmpty() || !canItemQuickReplace(source, cursor, true) || !source.mayPickup(player)
                        || !canTakeItemForPickAll(cursor, source) || pass == 0 && item.getCount() == item.getMaxStackSize()) continue;
                int amount = Math.min(item.getCount(), cursor.getMaxStackSize() - cursor.getCount());
                if (index < menuSize) {
                    if (!visited.add(source)) continue;
                    ItemStack beforeItem = item.copy(), beforeCursor = cursor.copy();
                    CitizensInventoryClickEvent event = new CitizensInventoryClickEvent(index, MenuClickType.DOUBLE_CLICK,
                            InventoryAction.COLLECT_TO_CURSOR, item.copy(), cursor.copy(), -1, menu.getViewers(), amount,
                            player instanceof ServerPlayer viewer ? viewer : null, this);
                    menu.handleClick(event);
                    if (!menu.isCurrent(this, player) || getCarried() != cursor || source.getItem() != item
                            || !ItemStack.matches(beforeCursor, cursor) || !ItemStack.matches(beforeItem, source.getItem())) return;
                    if (event.isCancelled()) continue;
                }
                ItemStack taken = source.safeTake(item.getCount(), cursor.getMaxStackSize() - cursor.getCount(), player);
                cursor.grow(taken.getCount());
            }
        }
    }

    private void dragClick(int slotId, int button, Player player) {
        int header = getQuickcraftHeader(button);
        if (header == 1 && (slotId < 0 || slotId >= slots.size())) {
            resetQuickCraft();
        } else if (header == 2 && quickcraftStatus == 1 && !getCarried().isEmpty()) {
            int type = quickcraftType;
            ItemStack cursor = getCarried();
            ItemStack original = cursor.copy();
            List<Slot> targets = List.copyOf(quickcraftSlots);
            int share = targets.isEmpty() ? 0 : getQuickCraftPlaceCount(quickcraftSlots, type, original);
            // Finish the native gesture before invoking callbacks, so closing/reentry cannot replay it.
            resetQuickCraft();
            if (isValidQuickcraftType(type, player)) {
                if (targets.size() == 1) {
                    Slot target = targets.getFirst();
                    if (target.index >= 0 && target.index < slots.size() && slots.get(target.index) == target) {
                        // Native one-slot drags are ordinary pickup clicks, including the creative no-op.
                        if (type == 0 || type == 1) handleClick(target.index, type, ClickType.PICKUP, player);
                    }
                } else if (targets.size() > 1 && (type == 2 || original.getCount() >= targets.size())) {
                    finishDrag(player, targets, type, share, original, cursor);
                }
            }
        } else {
            // Let Minecraft own start/add admission, duplicate candidates and invalid phase resets.
            super.clicked(slotId, button, ClickType.QUICK_CRAFT, player);
        }
        // Admission has no inventory changes. Correct client prediction once the gesture ends or resets.
        if (quickcraftStatus == 0 && menu.isCurrent(this, player)) broadcastFullState();
    }

    private void finishDrag(Player player, List<Slot> targets, int type, int share, ItemStack original, ItemStack cursor) {
        ItemStack expectedCursor = cursor.copy();
        for (Slot target : targets) {
            if (!menu.isCurrent(this, player) || getCarried() != cursor || !ItemStack.matches(expectedCursor, cursor)
                    || !isValidQuickcraftType(type, player)) return;
            int index = target.index;
            if (index < 0 || index >= slots.size() || slots.get(index) != target) return;
            if (!canItemQuickReplace(target, original, true) || !target.mayPlace(original) || !canDragTo(target)) continue;
            ItemStack existing = target.getItem();
            int amount = Math.min(share, Math.min(original.getMaxStackSize(), target.getMaxStackSize(original)) - existing.getCount());
            if (amount <= 0) continue;
            if (index < menuSize) {
                ItemStack beforeItem = existing.copy(), beforeCursor = cursor.copy();
                InventoryAction action = type == 1 ? InventoryAction.PLACE_ONE
                        : type == 2 || amount == cursor.getCount() ? InventoryAction.PLACE_ALL : InventoryAction.PLACE_SOME;
                CitizensInventoryClickEvent event = new CitizensInventoryClickEvent(index,
                        type == 2 ? MenuClickType.MIDDLE : type == 1 ? MenuClickType.RIGHT : MenuClickType.LEFT,
                        action, existing.copy(), original.copyWithCount(amount), -1, menu.getViewers(), -1,
                        player instanceof ServerPlayer viewer ? viewer : null, this);
                menu.handleClick(event);
                if (!menu.isCurrent(this, player) || slots.get(index) != target || target.getItem() != existing
                        || getCarried() != cursor || !ItemStack.matches(beforeItem, existing)
                        || !ItemStack.matches(beforeCursor, cursor)) return;
                if (event.isCancelled()) continue;
                if (!isValidQuickcraftType(type, player) || !target.mayPlace(original) || !canDragTo(target)
                        || Math.min(original.getMaxStackSize(), target.getMaxStackSize(original)) - existing.getCount() < amount) return;
            }
            // Consume each accepted share before notifying the destination's container listeners. A close
            // callback must return only the remaining cursor, not duplicate an already placed share.
            cursor.shrink(amount);
            expectedCursor = cursor.copy();
            target.setByPlayer(original.copyWithCount(existing.getCount() + amount));
        }
    }

    private void shiftClick(int slotId, int button, Player player) {
        if (button != 0 && button != 1) return;
        Slot source = slots.get(slotId);
        ItemStack moving = source.getItem();
        if (moving.isEmpty() || !source.mayPickup(player)) return;
        boolean fromMenu = slotId < menuSize;
        int first = fromMenu ? slots.size() - 1 : 0;
        int end = fromMenu ? menuSize - 1 : menuSize;
        int direction = fromMenu ? -1 : 1;
        ItemStack cursor = getCarried();
        // Native chest transfers merge existing stacks first, then use empty slots. Each menu-side
        // transfer gets the reference API's actual pickup/placement action and quantity.
        for (int pass = 0; pass < 2; pass++) {
            for (int index = first; index != end && !moving.isEmpty(); index += direction) {
                if (!menu.isCurrent(this, player) || source.getItem() != moving || getCarried() != cursor) return;
                Slot target = slots.get(index);
                ItemStack existing = target.getItem();
                if (pass == 0 ? !moving.isStackable() || existing.isEmpty()
                        || !ItemStack.isSameItemSameComponents(existing, moving) : !existing.isEmpty()) continue;
                if (!target.mayPlace(moving)) continue;
                int amount = Math.min(moving.getCount(), target.getMaxStackSize(moving) - existing.getCount());
                if (amount <= 0) continue;
                ItemStack beforeSource = moving.copy(), beforeTarget = existing.copy(), beforeCursor = cursor.copy();
                boolean all = amount == moving.getCount();
                InventoryAction action = fromMenu ? (all ? InventoryAction.PICKUP_ALL : InventoryAction.PICKUP_SOME)
                        : (all ? InventoryAction.PLACE_ALL : InventoryAction.PLACE_SOME);
                CitizensInventoryClickEvent event = new CitizensInventoryClickEvent(fromMenu ? slotId : index,
                        MenuClickType.of(ClickType.QUICK_MOVE, button), action,
                        fromMenu ? moving.copy() : existing.copy(), fromMenu ? cursor.copy() : moving.copyWithCount(amount),
                        -1, menu.getViewers(), amount, player instanceof ServerPlayer viewer ? viewer : null, this);
                menu.handleClick(event);
                // Handlers can change screens, sessions or either inventory. Never apply a stale proposal afterward.
                if (!menu.isCurrent(this, player) || slots.get(slotId) != source || slots.get(index) != target
                        || source.getItem() != moving || target.getItem() != existing || getCarried() != cursor
                        || !ItemStack.matches(beforeSource, moving) || !ItemStack.matches(beforeTarget, existing)
                        || !ItemStack.matches(beforeCursor, cursor)) return;
                if (event.isCancelled()) {
                    if (fromMenu) return;
                    continue;
                }
                if (!source.mayPickup(player) || !target.mayPlace(moving)
                        || target.getMaxStackSize(moving) - existing.getCount() < amount) return;
                if (existing.isEmpty()) {
                    target.setByPlayer(moving.split(amount));
                } else {
                    existing.grow(amount);
                    moving.shrink(amount);
                    target.setChanged();
                }
                if (moving.isEmpty()) source.setByPlayer(ItemStack.EMPTY);
                else source.setChanged();
            }
        }
    }

    public Container getMenuContainer() {
        return container;
    }

    public int getMenuSize() {
        return menuSize;
    }

    /**
     * Vanilla's direct transfer helper. Callers are responsible for authorizing their own programmatic move;
     * player shift-clicks use per-slot callbacks in {@link #clicked}.
     */
    public boolean moveIntoPlayerInventory(ItemStack stack) {
        return moveItemStackTo(stack, menuSize, slots.size(), true);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // routed through clicked() above so the menu can veto it; vanilla would move the item with no hook at all
        return ItemStack.EMPTY;
    }

    @Override
    public void removed(Player player) {
        resetQuickCraft();
        super.removed(player);
        container.stopOpen(player);
        if (menu != null) {
            menu.onContainerClosed(this, player);
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    /**
     * Names what the click would do, in the vocabulary Citizens' handlers branch on. Minecraft never computes this: it
     * just performs the move.
     */
    private static InventoryAction actionFor(ClickType clickType, int button, ItemStack slotItem, ItemStack cursor) {
        switch (clickType) {
            case PICKUP:
                if (cursor.isEmpty())
                    return slotItem.isEmpty() ? InventoryAction.NOTHING
                            : button == 1 ? InventoryAction.PICKUP_HALF : InventoryAction.PICKUP_ALL;
                if (slotItem.isEmpty())
                    return button == 1 ? InventoryAction.PLACE_ONE : InventoryAction.PLACE_ALL;
                if (!ItemStack.isSameItemSameComponents(slotItem, cursor))
                    return InventoryAction.SWAP_WITH_CURSOR;
                if (button == 1)
                    return InventoryAction.PLACE_ONE;
                return slotItem.getCount() + cursor.getCount() > slotItem.getMaxStackSize()
                        ? InventoryAction.PLACE_SOME
                        : InventoryAction.PLACE_ALL;
            case QUICK_MOVE:
                return InventoryAction.MOVE_TO_OTHER_INVENTORY;
            case SWAP:
                return InventoryAction.HOTBAR_SWAP;
            case CLONE:
                return InventoryAction.CLONE_STACK;
            case THROW:
                if (slotItem.isEmpty())
                    return InventoryAction.NOTHING;
                return button == 1 ? InventoryAction.DROP_ALL_SLOT : InventoryAction.DROP_ONE_SLOT;
            case PICKUP_ALL:
                return InventoryAction.COLLECT_TO_CURSOR;
            default:
                return InventoryAction.UNKNOWN;
        }
    }
}
