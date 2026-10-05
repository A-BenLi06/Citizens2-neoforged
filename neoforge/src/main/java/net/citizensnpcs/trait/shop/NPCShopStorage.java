package net.citizensnpcs.trait.shop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import net.citizensnpcs.api.gui.InventoryMenuPage;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.util.StoredItemList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * What a shop itself holds: its till and its stock.
 * <p>
 * An unlimited shop ignores both — it neither runs out of goods nor out of money, which is what most NPC shops want.
 * Turning that off makes the shop trade against real stock, so it can be emptied and refilled.
 * <p>
 * Upstream nests this inside {@code ShopTrait}. It is a top-level class here to break the cycle between the trait and
 * {@link NPCShopAction}: every action needs the storage type, and the trait needs every action.
 */
public class NPCShopStorage {
    private boolean writing;
    private final ShopSessionOwner sessions = new ShopSessionOwner();

    public boolean isActive() { return sessions.isActive(); }
    public void requireIdle() { sessions.requireIdle(); }
    public Runnable suspendSessions() { return sessions.suspend(); }
    public void retire() { sessions.retire(); }
    <T> Runnable trackSession(T target, Consumer<T> close) { return sessions.track(target, close); }
    boolean beginTrade() { return sessions.beginTrade(); }
    void endTrade() { sessions.endTrade(); }
    @Persist
    private double balance;
    @Persist
    private List<ItemStack> inventory = new ArrayList<>();
    @Persist
    private int inventorySizeLimit = -1;
    @Persist
    private boolean unlimited = true;

    /** The page that shows and edits this stock. */
    public InventoryMenuPage createInventoryViewer(ServerPlayer whoClicked) {
        if (!isActive()) throw new IllegalStateException("Shop stock is unavailable");
        return new InventoryViewer(this);
    }

    public boolean canAdd(int n) {
        return !hasUnavailableItems() && (inventorySizeLimit == -1 || inventory.size() + n < inventorySizeLimit);
    }

    public boolean hasUnavailableItems() { return !unlimited && StoredItemList.hasUnavailable(inventory); }

    public double getBalance() {
        return balance;
    }

    /** The stock as a flat array, which is the shape {@link NPCShopAction}s work in. */
    public ItemStack[] getContents() {
        return inventory.toArray(new ItemStack[0]);
    }

    public List<ItemStack> getInventory() {
        return inventory;
    }

    public int getInventorySizeLimit() {
        return inventorySizeLimit;
    }

    public boolean isUnlimited() {
        return unlimited;
    }

    public void setBalance(double balance) {
        if (unlimited)
            return;
        this.balance = balance;
    }

    public void setInventory(List<ItemStack> items) {
        if (writing) throw new IllegalStateException("Shop stock transaction is already in progress");
        this.inventory = items;
    }

    public void transact(Consumer<ItemStack[]> action) {
        transact(action, 0);
    }

    /**
     * Runs an action over the stock and writes back what it leaves behind.
     *
     * @param additional
     *            how many items the action may want to add that will not fit in an existing stack. Upstream accepts this
     *            argument and then ignores it, so the array handed to the action has no free slots and anything that
     *            cannot merge into a partial stack is silently dropped - a player selling to a limited-stock shop loses
     *            the goods. The empty slots are really provided here.
     */
    public void transact(Consumer<ItemStack[]> action, int additional) {
        if (writing) throw new IllegalStateException("Shop stock transaction is already in progress");
        if (!isActive()) throw new IllegalStateException("Shop stock is unavailable");
        if (unlimited)
            return;
        if (hasUnavailableItems()) throw new IllegalStateException("Shop stock contains unavailable item definitions");
        writing = true;
        try {
            ItemStack[] items = new ItemStack[inventory.size() + Math.max(0, additional)];
            for (int i = 0; i < items.length; i++) {
                items[i] = i < inventory.size() ? inventory.get(i).copy() : ItemStack.EMPTY;
            }
            action.accept(items);
            inventory = Arrays.stream(items).filter(i -> i != null && !i.isEmpty()).map(ItemStack::copy).collect(Collectors.toList());
        } finally { writing = false; }
    }

    public void setInventorySizeLimit(int limit) {
        this.inventorySizeLimit = limit;
    }

    public void setUnlimited(boolean unlimited) {
        this.unlimited = unlimited;
    }
}
