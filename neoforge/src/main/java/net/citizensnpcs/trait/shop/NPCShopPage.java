package net.citizensnpcs.trait.shop;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import net.citizensnpcs.api.persistence.Persist;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * One page of a shop: what sits in each slot, and an optional title shown while the page is open.
 */
public class NPCShopPage {
    @Persist("$key")
    int index;
    @Persist(keyType = Integer.class, reify = true)
    private final Map<Integer, NPCShopItem> items = new HashMap<>();
    @Persist
    String title;

    private NPCShopPage() {
    }

    public NPCShopPage(int page) {
        index = page;
    }

    public int getIndex() {
        return index;
    }

    public NPCShopItem getItem(int idx) {
        return items.get(idx);
    }

    public Collection<NPCShopItem> getItems() {
        return items.values();
    }

    Map<Integer, NPCShopItem> getItemMap() {
        return items;
    }

    /** The item to draw in a navigation slot: whatever the shop put there, or a plain feather. */
    public ItemStack getNextPageItem(ServerPlayer player, int idx) {
        return items.containsKey(idx) ? items.get(idx).getDisplayItem(player) : new ItemStack(Items.FEATHER, 1);
    }

    public ItemStack getPreviousPageItem(ServerPlayer player, int idx) {
        return items.containsKey(idx) ? items.get(idx).getDisplayItem(player) : new ItemStack(Items.FEATHER, 1);
    }

    public String getTitle() {
        return title;
    }

    public void removeItem(int idx) {
        items.remove(idx);
    }

    public void setItem(int idx, NPCShopItem modified) {
        if (modified == null) {
            items.remove(idx);
        } else {
            items.put(idx, modified);
        }
    }
}
