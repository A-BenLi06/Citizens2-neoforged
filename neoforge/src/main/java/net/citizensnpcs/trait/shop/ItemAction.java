package net.citizensnpcs.trait.shop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.google.common.base.Joiner;

import net.citizensnpcs.api.gui.InputMenus;
import net.citizensnpcs.api.gui.InventoryMenuPage;
import net.citizensnpcs.api.gui.InventoryMenuSlot;
import net.citizensnpcs.api.gui.Menu;
import net.citizensnpcs.api.gui.MenuContext;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.util.ItemStorage;
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

    /** Whether there is room to hand over {@code items.size() * repeats} stacks. */
    private boolean canAccept(InventoryMultiplexer im, int repeats) {
        int free = 0;
        for (ItemStack stack : im.getInventory()) {
            if (isEmpty(stack)) {
                free++;
            }
        }
        return free >= items.size() * repeats;
    }

    @Override
    public String describe() {
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
        ItemStack[] inventory = im.getInventory();
        List<Integer> req = items.stream().map(ItemStack::getCount).collect(Collectors.toList());
        List<Integer> has = items.stream().map(i -> 0).collect(Collectors.toList());
        for (ItemStack toMatch : inventory) {
            if (isEmpty(toMatch) || tooDamaged(toMatch))
                continue;
            for (int j = 0; j < items.size(); j++) {
                if (!matches(items.get(j), toMatch))
                    continue;
                has.set(j, has.get(j) + toMatch.getCount());
                break;
            }
        }
        return IntStream.range(0, req.size()).map(i -> req.get(i) == 0 ? 0 : has.get(i) / req.get(i)).reduce(Math::min)
                .orElse(0);
    }

    /** Adds the items to the array, merging into partial stacks first and then filling empty slots. */
    private void giveItems(ItemStack[] inventory, int repeats) {
        for (int i = 0; i < repeats; i++) {
            List<ItemStack> toAdd = items.stream().map(ItemStack::copy).collect(Collectors.toList());
            for (int j = 0; j < inventory.length; j++) {
                if (toAdd.isEmpty())
                    return;
                if (isEmpty(inventory[j]))
                    continue;
                ItemStack last = toAdd.get(toAdd.size() - 1);
                if (!ItemStack.isSameItemSameComponents(inventory[j], last)
                        || inventory[j].getCount() >= inventory[j].getMaxStackSize())
                    continue;
                int diff = inventory[j].getMaxStackSize() - inventory[j].getCount();
                if (diff >= last.getCount()) {
                    inventory[j].setCount(inventory[j].getCount() + last.getCount());
                    toAdd.remove(toAdd.size() - 1);
                } else {
                    inventory[j].setCount(inventory[j].getCount() + diff);
                    last.setCount(last.getCount() - diff);
                }
            }
            for (int j = 0; j < inventory.length; j++) {
                if (toAdd.isEmpty())
                    break;
                if (isEmpty(inventory[j])) {
                    inventory[j] = toAdd.remove(toAdd.size() - 1);
                }
            }
        }
    }

    @Override
    public Transaction grant(NPCShopStorage storage, Entity entity, InventoryMultiplexer im, int repeats) {
        return Transaction.create(() -> (storage.isUnlimited() || takeItems(storage.getContents(), repeats, false))
                && canAccept(im, repeats), () -> {
                    storage.transact(inventory -> takeItems(inventory, repeats, true));
                    im.transact(inventory -> giveItems(inventory, repeats));
                }, () -> {
                    storage.transact(inventory -> giveItems(inventory, repeats), items.size() * repeats);
                    im.transact(inventory -> takeItems(inventory, repeats, true));
                });
    }

    @Override
    public Transaction take(NPCShopStorage storage, Entity entity, InventoryMultiplexer im, int repeats) {
        return Transaction.create(() -> (storage.isUnlimited() || storage.canAdd(items.size() * repeats))
                && takeItems(im.getInventory(), repeats, false), () -> {
                    storage.transact(inventory -> giveItems(inventory, repeats), items.size() * repeats);
                    im.transact(inventory -> takeItems(inventory, repeats, true));
                }, () -> {
                    storage.transact(inventory -> takeItems(inventory, repeats, true));
                    im.transact(inventory -> giveItems(inventory, repeats));
                });
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.isEmpty();
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
            metaMatches(item, item, metaFilter);
        }
    }

    private String stringify(ItemStack item) {
        return item.getCount() + " " + item.getHoverName().getString();
    }

    /** Removes the items from the array; returns whether the full amount was there. */
    private boolean takeItems(ItemStack[] contents, int repeats, boolean modify) {
        List<Integer> req = items.stream().map(i -> i.getCount() * repeats).collect(Collectors.toList());
        for (int i = 0; i < contents.length; i++) {
            ItemStack toMatch = contents[i];
            if (isEmpty(toMatch) || tooDamaged(toMatch))
                continue;
            toMatch = toMatch.copy();
            for (int j = 0; j < items.size(); j++) {
                if (toMatch == null)
                    break;
                if (req.get(j) <= 0 || !matches(items.get(j), toMatch))
                    continue;
                int remaining = req.get(j);
                int taken = Math.min(toMatch.getCount(), remaining);
                if (toMatch.getCount() == taken) {
                    toMatch = null;
                } else {
                    toMatch.setCount(toMatch.getCount() - taken);
                }
                if (modify) {
                    contents[i] = toMatch == null ? ItemStack.EMPTY : toMatch.copy();
                }
                req.set(j, remaining - taken);
            }
        }
        return req.stream().mapToInt(n -> n).sum() <= 0;
    }

    private boolean tooDamaged(ItemStack toMatch) {
        return requireUndamaged && toMatch.isDamaged();
    }

    @Menu(title = "Item editor", dimensions = { 4, 9 })
    public static class ItemActionEditor extends InventoryMenuPage {
        private ItemAction base;
        private Consumer<NPCShopAction> callback;
        private MenuContext ctx;

        public ItemActionEditor() {
        }

        public ItemActionEditor(ItemAction base, Consumer<NPCShopAction> callback) {
            this.base = base;
            this.callback = callback;
        }

        @Override
        public void initialise(MenuContext ctx) {
            this.ctx = ctx;
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
