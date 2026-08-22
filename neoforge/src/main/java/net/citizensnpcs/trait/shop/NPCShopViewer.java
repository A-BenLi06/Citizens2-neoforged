package net.citizensnpcs.trait.shop;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.gui.InventoryMenuPage;
import net.citizensnpcs.api.gui.InventoryMenuSlot;
import net.citizensnpcs.api.gui.InventoryType;
import net.citizensnpcs.api.gui.Menu;
import net.citizensnpcs.api.gui.MenuContext;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.Placeholders;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;

/**
 * What a customer sees: one page of the shop at a time, with the navigation slots the shop type defines.
 */
@Menu(title = "Shop", type = InventoryType.CHEST, dimensions = { 5, 9 })
public class NPCShopViewer extends InventoryMenuPage {
    private MenuContext ctx;
    private int currentPage = 0;
    private NPCShopItem lastClickedItem;
    private final ServerPlayer player;
    private final NPCShop shop;
    private final NPCShopStorage storage;

    public NPCShopViewer(NPCShop shop, NPCShopStorage storage, ServerPlayer player) {
        this.shop = shop;
        this.player = player;
        this.storage = storage;
    }

    public void changePage(int newPage) {
        currentPage = newPage;
        NPCShopPage page = shop.getPages().get(currentPage);
        if (page.getTitle() != null && !page.getTitle().isEmpty()) {
            // a tick later, because the title change resends the open-screen packet and the client is still opening it
            CitizensAPI.getScheduler().runEntityTaskLater(player,
                    () -> ctx.setTitle(Placeholders.replace(page.getTitle(), player)), 1);
        }
        for (int i = 0; i < ctx.getSize(); i++) {
            ctx.getSlot(i).clear();
            NPCShopItem item = page.getItem(i);
            if (item == null)
                continue;
            ctx.getSlot(i).setItemStack(item.getDisplayItem(player));
            ctx.getSlot(i).setClickHandler(evt -> {
                evt.setCancelled(true);
                ServerPlayer clicker = evt.getWhoClicked() == null ? player : evt.getWhoClicked();
                item.onClick(shop, storage, clicker, new InventoryMultiplexer(clicker.getInventory()),
                        evt.isShiftClick(), lastClickedItem == item);
                lastClickedItem = item;
            });
        }
        InventoryMenuSlot prev = ctx.getSlot(shop.getShopType().getPrevSlotIndex());
        InventoryMenuSlot next = ctx.getSlot(shop.getShopType().getNextSlotIndex());
        if (currentPage > 0) {
            prev.clear();
            prev.setItemStack(page.getPreviousPageItem(player, shop.getShopType().getPrevSlotIndex()),
                    "Previous page (" + newPage + ")");
            prev.setClickHandler(evt -> {
                evt.setCancelled(true);
                changePage(currentPage - 1);
            });
        }
        if (currentPage + 1 < shop.getPages().size()) {
            next.clear();
            next.setItemStack(page.getNextPageItem(player, shop.getShopType().getNextSlotIndex()),
                    "Next page (" + (newPage + 2) + ")");
            next.setClickHandler(evt -> {
                evt.setCancelled(true);
                changePage(currentPage + 1);
            });
        }
    }

    @Override
    public Container createContainer(String title) {
        return new SimpleContainer(shop.getShopType().getInventorySize());
    }

    @Override
    public void initialise(MenuContext ctx) {
        this.ctx = ctx;
        if (!shop.getTitle().isEmpty()) {
            ctx.setTitle(Messaging.parseComponents(Placeholders.replace(shop.getTitle(), player)));
        }
        changePage(currentPage);
    }
}
