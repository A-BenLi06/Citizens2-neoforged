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
import net.minecraft.world.SimpleContainer;
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

        ShopMerchantMenu(int id, Inventory inventory, Merchant merchant, NPCShop shop, NPCShopStorage storage,
                Map<Integer, NPCShopItem> trades) {
            super(id, inventory, merchant);
            this.shop = shop;
            this.storage = storage;
            this.trades = trades;
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {
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

            // the two payment slots stand in for an inventory the cost can be taken from, exactly as a chest shop would
            // use the player's own, and are written back once the transaction has run
            SimpleContainer payment = new SimpleContainer(2);
            payment.setItem(0, getSlot(PAYMENT1_SLOT).getItem());
            payment.setItem(1, getSlot(PAYMENT2_SLOT).getItem());
            InventoryMultiplexer multiplexer = new InventoryMultiplexer(buyer.getInventory(), payment);
            trades.get(selectedTrade).onClick(shop, storage, buyer, multiplexer, shiftClick,
                    lastClickedTrade == selectedTrade);
            getSlot(PAYMENT1_SLOT).set(payment.getItem(0));
            getSlot(PAYMENT2_SLOT).set(payment.getItem(1));
            lastClickedTrade = selectedTrade;
            broadcastFullState();
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
}
