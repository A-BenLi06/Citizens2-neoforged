package net.citizensnpcs.trait.shop;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * Shows a shop as vanilla's villager trading screen instead of a chest.
 * <p>
 * The offers exist so the client can draw the trades; the purchase itself still goes through
 * {@link NPCShopItem#onClick}, so costs, results, limits and messages behave exactly as they do in a chest shop. That is
 * why the result slot is intercepted rather than left to vanilla's trade machinery.
 * <p>
 * Upstream reaches the same place through Bukkit's {@code Merchant} plus a reflected {@code getView()} to tell one open
 * merchant window from another, and works out which trade is selected by comparing result items — which it gives up on
 * when two trades sell the same thing. A menu subclass is told the selected index directly, so that ambiguity is gone.
 */
public class NPCTraderShopViewer {
    private NPCTraderShopViewer() {
    }

    public static void open(NPCShop shop, NPCShopStorage storage, ServerPlayer player) {
        if (!shop.isActive() || !storage.isActive()) return;
        Map<Integer, NPCShopItem> trades = new HashMap<>();
        MerchantOffers offers = new MerchantOffers();
        for (NPCShopPage page : shop.getPages()) {
            for (NPCShopItem item : page.getItems()) {
                ItemStack result = item.getDisplayItem(player);
                if (result == null || result.isEmpty()) {
                    continue;
                }
                ItemCost[] costs = costsOf(item);
                if (costs.length == 0) {
                    // a trade needs something to pay with, and only item costs can be shown on this screen
                    continue;
                }
                trades.put(offers.size(), item);
                offers.add(new MerchantOffer(costs[0], costs.length > 1 ? Optional.of(costs[1]) : Optional.empty(),
                        result.copy(), Integer.MAX_VALUE, 0, 0));
            }
        }
        if (offers.isEmpty()) {
            Messaging.sendError(player.createCommandSourceStack(), "No tradeable items in this shop");
            return;
        }
        ShopMerchant merchant = new ShopMerchant(offers);
        Component title = Component
                .literal(shop.getTitle().isEmpty() ? "Shop" : Messaging.parseComponents(shop.getTitle()));
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, who) -> new ShopMerchantMenu(id, inventory, merchant, shop, storage, trades), title));
        if (player.containerMenu instanceof ShopMerchantMenu menu) {
            player.sendMerchantOffers(menu.containerId, offers, 0, 0, false, false);
        }
    }

    /** At most two item costs, since a vanilla trade has two payment slots. */
    private static ItemCost[] costsOf(NPCShopItem item) {
        ItemCost[] costs = new ItemCost[2];
        int found = 0;
        for (NPCShopAction action : item.getCost()) {
            if (!(action instanceof ItemAction items)) {
                continue;
            }
            for (ItemStack stack : items.items) {
                if (found == 2) {
                    break;
                }
                // with a component filter set only the named components matter, and ItemAction checks those on click, so
                // the client is told to match the item alone. Without a filter the trade is exact, and so is the cost -
                // which is also how the item appears in the screen's payment slot.
                costs[found++] = items.metaFilter.isEmpty()
                        ? new ItemCost(stack.getItemHolder(), stack.getCount(),
                                DataComponentPredicate.allOf(stack.getComponents()), stack.copy())
                        : new ItemCost(stack.getItem(), stack.getCount());
            }
        }
        ItemCost[] trimmed = new ItemCost[found];
        System.arraycopy(costs, 0, trimmed, 0, found);
        return trimmed;
    }

    /** A merchant with no entity behind it: it exists only to carry the offers to the client. */
    private static class ShopMerchant implements Merchant {
        private MerchantOffers offers;
        private Player trading;

        ShopMerchant(MerchantOffers offers) {
            this.offers = offers;
        }

        @Override
        public SoundEvent getNotifyTradeSound() {
            return SoundEvents.VILLAGER_YES;
        }

        @Override
        public MerchantOffers getOffers() {
            return offers;
        }

        @Override
        public Player getTradingPlayer() {
            return trading;
        }

        @Override
        public int getVillagerXp() {
            return 0;
        }

        @Override
        public boolean isClientSide() {
            return false;
        }

        @Override
        public void notifyTrade(MerchantOffer offer) {
        }

        @Override
        public void notifyTradeUpdated(ItemStack stack) {
        }

        @Override
        public void overrideOffers(MerchantOffers offers) {
            this.offers = offers;
        }

        @Override
        public void overrideXp(int xp) {
        }

        @Override
        public void setTradingPlayer(Player player) {
            trading = player;
        }

        @Override
        public boolean showProgressBar() {
            return false;
        }
    }

    /** A merchant menu whose result slot runs the shop's own transaction instead of vanilla's trade. */
    private static class ShopMerchantMenu extends MerchantMenu {
        private int lastClickedTrade = -1;
        private int selectedTrade = -1;
        private final NPCShop shop;
        private final NPCShopStorage storage;
        private final Map<Integer, NPCShopItem> trades;
        private final Player owner;
        private final Runnable detach;
        private final Runnable detachStock;
        private boolean invalidated;

        ShopMerchantMenu(int id, Inventory inventory, Merchant merchant, NPCShop shop, NPCShopStorage storage,
                Map<Integer, NPCShopItem> trades) {
            super(id, inventory, merchant);
            this.shop = shop;
            this.storage = storage;
            this.trades = trades;
            owner = inventory.player;
            merchant.setTradingPlayer(owner);
            detach = shop.trackSession(this, ShopMerchantMenu::dispose);
            try { detachStock = storage.trackSession(this, ShopMerchantMenu::dispose); }
            catch (RuntimeException | Error failure) { detach.run(); throw failure; }
        }

        private void dispose() {
            invalidated = true;
            try { if (owner.containerMenu == this) owner.closeContainer(); }
            finally { detach.run(); detachStock.run(); }
        }

        @Override public void removed(Player player) {
            invalidated = true;
            try { super.removed(player); }
            finally { detach.run(); detachStock.run(); }
        }

        @Override public boolean stillValid(Player player) {
            return !invalidated && shop.isActive() && storage.isActive() && super.stillValid(player);
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {
            if (invalidated || !shop.isActive() || !storage.isActive() || player != owner || player.containerMenu != this) return;
            if (slotId != RESULT_SLOT || !(player instanceof ServerPlayer buyer)) {
                super.clicked(slotId, button, clickType, player);
                return;
            }
            boolean shiftClick = clickType == ClickType.QUICK_MOVE;
            if (!shiftClick && !getCarried().isEmpty())
                // already holding something, so the result would have nowhere to go
                return;
            if (selectedTrade == -1 || !trades.containsKey(selectedTrade))
                return;

            // Costs update the real payment slots before action/notification callbacks can close this menu. The
            // preview result slot is excluded: it is not another item owned by the player.
            PaymentView payment = new PaymentView(this, getSlot(PAYMENT1_SLOT).container, PAYMENT1_SLOT, PAYMENT2_SLOT);
            InventoryMultiplexer multiplexer = new InventoryMultiplexer(buyer.getInventory(), payment);
            try {
                trades.get(selectedTrade).onClick(shop, storage, buyer, multiplexer, shiftClick,
                        lastClickedTrade == selectedTrade, payment::finish);
            } finally { payment.finish(); }
            lastClickedTrade = selectedTrade;
            if (!invalidated && buyer.containerMenu == this) broadcastFullState();
        }

        @Override
        public void setSelectionHint(int index) {
            super.setSelectionHint(index);
            if (index != selectedTrade) {
                // a different trade means any click-to-confirm starts again
                lastClickedTrade = -1;
            }
            selectedTrade = index;
        }
    }

    private static final class PaymentView implements Container {
        private final ShopMerchantMenu menu;
        private final Container source;
        private final int first, size;
        private final ItemStack[] escrow;
        PaymentView(ShopMerchantMenu menu, Container source, int first, int last) {
            this.menu = menu;
            this.source = source;
            this.first = first;
            size = last - first + 1;
            escrow = new ItemStack[size];
            java.util.Arrays.fill(escrow, ItemStack.EMPTY);
        }
        private boolean closed() { return menu.invalidated || menu.owner.containerMenu != menu; }
        void finish() {
            // Closing has already returned native inputs. Only new items staged after that closure remain here.
            for (int i = 0; i < escrow.length; i++) {
                ItemStack stack = escrow[i];
                escrow[i] = ItemStack.EMPTY;
                if (!stack.isEmpty()) menu.owner.getInventory().placeItemBackInInventory(stack);
            }
        }
        private int index(int slot) {
            if (slot < 0 || slot >= getContainerSize()) throw new IndexOutOfBoundsException(slot);
            return first + slot;
        }
        @Override public int getContainerSize() { return size; }
        @Override public boolean isEmpty() {
            for (int i = 0; i < getContainerSize(); i++) if (!getItem(i).isEmpty()) return false;
            return true;
        }
        @Override public ItemStack getItem(int slot) { int mapped = index(slot); return closed() ? escrow[slot] : source.getItem(mapped); }
        @Override public ItemStack removeItem(int slot, int count) {
            int mapped = index(slot);
            if (!closed()) return source.removeItem(mapped, count);
            ItemStack result = escrow[slot].split(count);
            if (escrow[slot].isEmpty()) escrow[slot] = ItemStack.EMPTY;
            return result;
        }
        @Override public ItemStack removeItemNoUpdate(int slot) {
            int mapped = index(slot);
            if (!closed()) return source.removeItemNoUpdate(mapped);
            ItemStack result = escrow[slot]; escrow[slot] = ItemStack.EMPTY; return result;
        }
        @Override public void setItem(int slot, ItemStack stack) {
            int mapped = index(slot);
            if (closed()) escrow[slot] = stack;
            else source.setItem(mapped, stack);
        }
        @Override public int getMaxStackSize() { return source.getMaxStackSize(); }
        @Override public void setChanged() { if (!closed()) source.setChanged(); }
        @Override public boolean stillValid(Player player) { return menu.stillValid(player); }
        @Override public boolean canPlaceItem(int slot, ItemStack stack) { return source.canPlaceItem(index(slot), stack); }
        @Override public void clearContent() {
            for (int i = 0; i < getContainerSize(); i++) setItem(i, ItemStack.EMPTY);
        }
    }
}
