package net.citizensnpcs.trait.shop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.google.common.base.Joiner;

import net.citizensnpcs.api.gui.InputMenus;
import net.citizensnpcs.api.gui.InventoryMenuPage;
import net.citizensnpcs.api.gui.InventoryMenuSlot;
import net.citizensnpcs.api.gui.Menu;
import net.citizensnpcs.api.gui.MenuContext;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.util.ItemStorage;
import net.citizensnpcs.api.util.StoredItemList;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.citizensnpcs.util.Util;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * A cost or reward paid in items.
 */
public class ItemAction extends NPCShopAction {
    @Persist
    public List<ItemStack> items = new ArrayList<>();
    @Persist
    public List<String> metaFilter = new ArrayList<>();
    @Persist
    public boolean requireUndamaged = true;

    public ItemAction() {
    }

    public ItemAction(ItemStack... items) {
        this(Arrays.asList(items));
    }

    public ItemAction(List<ItemStack> items) {
        setItems(items);
    }

    @Override public ItemAction clone() {
        ItemAction result = (ItemAction) super.clone();
        result.items = items instanceof StoredItemList stored ? stored.copy()
                : items.stream().map(item -> item == null ? null : item.copy()).collect(Collectors.toCollection(ArrayList::new));
        result.metaFilter = new ArrayList<>(metaFilter);
        return result;
    }

    /** Simulate the same bounded placement that will be used during publication. */
    private boolean canAccept(InventoryMultiplexer im, int repeats) {
        return addItems(copy(im.getInventory()), repeats, im::getSlotLimit);
    }

    private static ItemStack[] copy(ItemStack[] source) {
        return Arrays.stream(source).map(stack -> isEmpty(stack) ? ItemStack.EMPTY : stack.copy()).toArray(ItemStack[]::new);
    }

    @FunctionalInterface
    private interface SlotLimit { int get(int slot, ItemStack stack); }
    @Override
    public String describe() {
        if (hasUnavailableItems()) return Messaging.tr("citizens.items.unavailable");
        if (items.size() == 1)
            return stringify(items.get(0));
        String description = items.size() + " items";
        for (int i = 0; i < items.size(); i++) {
            description += NEWLINE + stringify(items.get(i));
            if (i == 3) {
                description += "...";
                break;
            }
        }
        return description;
    }

    @Override
    public int getMaxRepeats(Entity entity, InventoryMultiplexer im) {
        if (hasUnavailableItems() || items.isEmpty()) return 0;
        long required = items.stream().mapToLong(ItemStack::getCount).sum();
        long available = Arrays.stream(im.getInventory()).filter(stack -> !isEmpty(stack) && !tooDamaged(stack))
                .mapToLong(ItemStack::getCount).sum();
        int low = 0, high = (int) Math.min(Integer.MAX_VALUE, available / required);
        while (low < high) {
            int middle = low + (int) (((long) high - low + 1) / 2);
            if (takeItems(im.getInventory(), middle, false)) low = middle;
            else high = middle - 1;
        }
        return low;
    }

    private boolean addItems(ItemStack[] inventory, int repeats, SlotLimit limit) {
        if (repeats < 0) return false;
        // Batch each template's repeated quantity, visiting templates in last-configured-first priority.
        for (int item = items.size() - 1; item >= 0; item--) {
            ItemStack template = items.get(item);
            long remaining = (long) template.getCount() * repeats;
            for (int slot = 0; slot < inventory.length && remaining > 0; slot++) {
                ItemStack existing = inventory[slot];
                if (isEmpty(existing) || !ItemStack.isSameItemSameComponents(existing, template)) continue;
                int room = Math.max(0, limit.get(slot, template) - existing.getCount());
                int moved = (int) Math.min(remaining, room);
                if (moved != 0) { existing.grow(moved); remaining -= moved; }
            }
            for (int slot = 0; slot < inventory.length && remaining > 0; slot++) {
                if (!isEmpty(inventory[slot])) continue;
                int moved = (int) Math.min(remaining, Math.max(0, limit.get(slot, template)));
                if (moved == 0) continue;
                inventory[slot] = template.copyWithCount(moved);
                remaining -= moved;
            }
            if (remaining != 0) return false;
        }
        return true;
    }

    private void giveItems(ItemStack[] inventory, int repeats, SlotLimit limit) {
        if (!addItems(inventory, repeats, limit)) throw new IllegalStateException("Insufficient inventory capacity for item transfer");
    }

    /** Additional array slots needed after merging, or -1 when the stock limit cannot accommodate the transfer. */
    private int stockAdditional(NPCShopStorage storage, int repeats) {
        if (storage.isUnlimited()) return 0;
        ItemStack[] current = storage.getContents();
        long extra = 0;
        for (ItemStack item : items) {
            if (item.getMaxStackSize() <= 0) return -1;
            long amount = (long) item.getCount() * repeats;
            long needed = (amount + item.getMaxStackSize() - 1) / item.getMaxStackSize();
            if (extra > Long.MAX_VALUE - needed) return -1;
            extra += needed;
        }
        if (extra > Long.MAX_VALUE - current.length) return -1;
        long capacity = (long) current.length + extra;
        int maximum = storage.getInventorySizeLimit();
        if (maximum != -1) capacity = Math.min(capacity, Math.max(current.length, maximum));
        if (capacity > Integer.MAX_VALUE || capacity < 0) return -1;
        ItemStack[] candidate = Arrays.copyOf(copy(current), (int) capacity);
        Arrays.fill(candidate, current.length, candidate.length, ItemStack.EMPTY);
        if (!addItems(candidate, repeats, (slot, stack) -> stack.getMaxStackSize())) return -1;
        int occupied = (int) Arrays.stream(candidate).filter(stack -> !isEmpty(stack)).count();
        if (maximum != -1 && occupied > maximum) return -1;
        return Math.max(0, occupied - current.length);
    }

    private void deposit(NPCShopStorage storage, int repeats) {
        int additional = stockAdditional(storage, repeats);
        if (additional < 0) throw new IllegalStateException("Insufficient shop stock capacity");
        storage.transact(stacks -> giveItems(stacks, repeats, (slot, stack) -> stack.getMaxStackSize()), additional);
    }

    private void removeItems(ItemStack[] inventory, int repeats) {
        if (!takeItems(inventory, repeats, true)) throw new IllegalStateException("Insufficient items for transfer");
    }
    @Override
    public Transaction grant(NPCShopStorage storage, Entity entity, InventoryMultiplexer im, int repeats) {
        if (repeats < 0 || hasUnavailableItems() || storage.hasUnavailableItems()) return Transaction.fail();
        if (repeats == 0) return Transaction.success();
        return Transaction.create(() -> storage.isActive() && (storage.isUnlimited() || takeItems(storage.getContents(), repeats, false))
                && canAccept(im, repeats), () -> transfer(storage, () -> {
                    if (!canAccept(im, repeats)) throw new IllegalStateException("Insufficient inventory capacity for reward");
                    storage.transact(inventory -> removeItems(inventory, repeats));
                    im.transact(inventory -> giveItems(inventory, repeats, im::getSlotLimit));
                }), () -> transfer(storage, () -> {
                    deposit(storage, repeats);
                    im.transact(inventory -> removeItems(inventory, repeats));
                }));
    }

    @Override
    public Transaction take(NPCShopStorage storage, Entity entity, InventoryMultiplexer im, int repeats) {
        if (repeats < 0 || hasUnavailableItems() || storage.hasUnavailableItems()) return Transaction.fail();
        if (repeats == 0) return Transaction.success();
        return Transaction.create(() -> storage.isActive() && takeItems(im.getInventory(), repeats, false)
                && stockAdditional(storage, repeats) >= 0, () -> transfer(storage, () -> {
                    if (!takeItems(im.getInventory(), repeats, false)) throw new IllegalStateException("Insufficient items for payment");
                    deposit(storage, repeats);
                    im.transact(inventory -> removeItems(inventory, repeats));
                }), () -> transfer(storage, () -> {
                    storage.transact(inventory -> removeItems(inventory, repeats));
                    im.transact(inventory -> giveItems(inventory, repeats, im::getSlotLimit));
                }));
    }

    private static void transfer(NPCShopStorage storage, Runnable operation) {
        // Stock transactions publish a new detached list. Keep the exact previous list, including its persistence
        // metadata, until the player-side write succeeds. The same rule applies when refunding a completed action.
        List<ItemStack> before = storage.isUnlimited() ? null : storage.getInventory();
        try { operation.run(); }
        catch (RuntimeException | Error failure) {
            if (before != null) {
                try { storage.setInventory(before); }
                catch (RuntimeException | Error restore) {
                    if (restore != failure) failure.addSuppressed(restore);
                }
            }
            throw failure;
        }
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.isEmpty();
    }

    private boolean hasUnavailableItems() {
        return StoredItemList.hasUnavailable(items) || items.stream().anyMatch(ItemAction::isEmpty);
    }

    private static final String NEWLINE = "\n";

    /**
     * Whether {@code toMatch} counts as the item this action wants.
     * <p>
     * With no meta filter this is vanilla's own "same item, same components" test, except that repair cost is ignored for
     * an action that does not require undamaged items - it is a common footgun, since an anvil quietly adds it and a
     * player would not expect an otherwise identical tool to stop being accepted.
     */
    private boolean matches(ItemStack a, ItemStack toMatch) {
        if (Messaging.isDebugging()) {
            Messaging.debug("Shop filter: comparing", a, "to", toMatch, "(" + metaFilter + ")",
                    ItemStack.isSameItemSameComponents(a, toMatch));
        }
        if (a.getItem() != toMatch.getItem())
            return false;
        if (!metaFilter.isEmpty())
            return metaMatches(a, toMatch, metaFilter);
        if (!requireUndamaged && (a.has(DataComponents.REPAIR_COST) || toMatch.has(DataComponents.REPAIR_COST))) {
            a = a.copy();
            toMatch = toMatch.copy();
            a.remove(DataComponents.REPAIR_COST);
            toMatch.remove(DataComponents.REPAIR_COST);
        }
        return ItemStack.isSameItemSameComponents(a, toMatch);
    }

    /**
     * Compares only the component paths named in the filter, so a shop can ask for "any sword with this custom name" or
     * "any item carrying this custom_data tag" and ignore everything else about the item.
     * <p>
     * Paths are dotted component ids without the {@code minecraft:} prefix, e.g. {@code custom_name} or
     * {@code custom_data.shopid}.
     */
    @SuppressWarnings("unchecked")
    private boolean metaMatches(ItemStack needle, ItemStack haystack, List<String> meta) {
        Map<String, Object> source = ItemStorage.componentMap(needle);
        Map<String, Object> compare = ItemStorage.componentMap(haystack);
        for (String path : meta) {
            String[] parts = path.trim().split("[.]");
            Object acc = source;
            Object cmp = compare;
            for (int i = 0; i < parts.length; i++) {
                if (acc == null || cmp == null)
                    return false;
                if (!(acc instanceof Map) || !(cmp instanceof Map))
                    return false;
                Map<String, Object> nextAcc = (Map<String, Object>) acc;
                Map<String, Object> nextCmp = (Map<String, Object>) cmp;
                if (!nextAcc.containsKey(parts[i])) {
                    Messaging.warn("Probable error in shop filter: source item does not contain requested component",
                            metaFilter, "- actual components are:", source);
                    return false;
                }
                if (!nextCmp.containsKey(parts[i]))
                    return false;
                acc = nextAcc.get(parts[i]);
                cmp = nextCmp.get(parts[i]);
                if (i == parts.length - 1 && !java.util.Objects.equals(acc, cmp))
                    return false;
            }
        }
        return true;
    }

    private void setItems(List<ItemStack> items) {
        this.items = items;
        if (metaFilter.isEmpty())
            return;
        // warns now, while the editor is open, if the filter names something the item does not have - rather than
        // silently never matching anything once the shop is in use
        for (ItemStack item : items) {
            if (!isEmpty(item)) metaMatches(item, item, metaFilter);
        }
    }

    private String stringify(ItemStack item) {
        return item.getCount() + " " + item.getHoverName().getString();
    }

    /** Allocate requirements cumulatively, using wide counts so multiplication and summation cannot wrap. */
    private boolean takeItems(ItemStack[] contents, int repeats, boolean modify) {
        if (repeats < 0) return false;
        long[] required = items.stream().mapToLong(item -> (long) item.getCount() * repeats).toArray();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (isEmpty(stack) || tooDamaged(stack)) continue;
            int remaining = stack.getCount();
            for (int item = 0; item < items.size() && remaining > 0; item++) {
                if (required[item] == 0 || !matches(items.get(item), stack)) continue;
                int taken = (int) Math.min(remaining, required[item]);
                remaining -= taken; required[item] -= taken;
            }
            if (modify && remaining != stack.getCount())
                contents[slot] = remaining == 0 ? ItemStack.EMPTY : stack.copyWithCount(remaining);
        }
        return Arrays.stream(required).allMatch(value -> value == 0);
    }
    private boolean tooDamaged(ItemStack toMatch) {
        return requireUndamaged && toMatch.isDamaged();
    }

    @Menu(title = "Item editor", dimensions = { 4, 9 })
    public static class ItemActionEditor extends InventoryMenuPage {
        private ItemAction base;
        private Consumer<NPCShopAction> callback;
        private MenuContext ctx;
        private boolean unavailable;

        public ItemActionEditor() {
        }

        public ItemActionEditor(ItemAction base, Consumer<NPCShopAction> callback) {
            this.base = base;
            this.callback = callback;
        }

        @Override
        public void initialise(MenuContext ctx) {
            this.ctx = ctx;
            unavailable = base.hasUnavailableItems();
            if (unavailable) {
                ctx.getSlot(0).setItemStack(new ItemStack(net.minecraft.world.item.Items.BARRIER),
                        Messaging.tr("citizens.items.unavailable"), Messaging.tr("citizens.items.unavailable-edit"));
                return;
            }
            for (int i = 0; i < 3 * 9; i++) {
                InventoryMenuSlot slot = ctx.getSlot(i);
                slot.clear();
                if (i < base.items.size()) {
                    slot.setItemStack(base.items.get(i).copy());
                }
                slot.setClickHandler(event -> {
                    event.setCurrentItem(event.getCursorNonNull());
                    event.setCancelled(true);
                });
            }
            InventoryMenuSlot undamaged = ctx.getSlot(3 * 9 + 1);
            undamaged.setItemStack(new ItemStack(net.minecraft.world.item.Items.ANVIL), "Must have no damage",
                    base.requireUndamaged ? "<green>On" : "<red>Off");
            undamaged.addClickHandler(InputMenus.toggler(res -> base.requireUndamaged = res, base.requireUndamaged));
            InventoryMenuSlot filter = ctx.getSlot(3 * 9 + 2);
            filter.setItemStack(new ItemStack(net.minecraft.world.item.Items.BOOK), "Component comparison filter",
                    Joiner.on("<br>").join(base.metaFilter));
            filter.addClickHandler(event -> ctx.getMenu()
                    .transition(InputMenus.stringSetter(() -> Joiner.on(',').join(base.metaFilter),
                            res -> base.metaFilter = res == null || res.isEmpty() ? new ArrayList<>()
                                    : Arrays.asList(res.split(",")))));
        }

        @Override
        public void onClose(ServerPlayer player) {
            if (unavailable) return;
            List<ItemStack> items = new ArrayList<>();
            for (int i = 0; i < 3 * 9; i++) {
                ItemStack stack = ctx.getSlot(i).getCurrentItem();
                if (!isEmpty(stack)) {
                    items.add(stack.copy());
                }
            }
            base.setItems(items);
            callback.accept(items.isEmpty() ? null : base);
        }
    }

    public static class ItemActionGUI implements GUI {
        @Override
        public boolean canUse(ServerPlayer entity) {
            return PermissionUtil.hasPermission(entity, "citizens.npc.shop.editor.actions.edit-item");
        }

        @Override
        public InventoryMenuPage createEditor(NPCShopAction previous, Consumer<NPCShopAction> callback) {
            return new ItemActionEditor(previous == null ? new ItemAction() : (ItemAction) previous, callback);
        }

        @Override
        public ItemStack createMenuItem(NPCShopAction previous) {
            return Util.createItem("minecraft:chest", "Item", previous == null ? null : previous.describe());
        }

        @Override
        public boolean manages(NPCShopAction action) {
            return action instanceof ItemAction;
        }
    }
}
