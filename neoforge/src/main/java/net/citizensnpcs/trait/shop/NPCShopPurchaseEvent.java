package net.citizensnpcs.trait.shop;

import net.citizensnpcs.api.event.CitizensEvent;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fired after a shop item has been paid for and handed over.
 * Purchase limits and native inventory settlement are complete, and transaction ownership is released before dispatch.
 * Listener failures are reported without reversing the committed purchase.
 */
public class NPCShopPurchaseEvent extends CitizensEvent {
    private final NPCShopItem item;
    private final ServerPlayer player;
    private final NPCShop shop;

    public NPCShopPurchaseEvent(ServerPlayer player, NPCShop shop, NPCShopItem item) {
        this.player = player;
        this.shop = shop;
        this.item = item;
    }

    public NPCShopItem getItem() {
        return item;
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    public NPCShop getShop() {
        return shop;
    }
}
