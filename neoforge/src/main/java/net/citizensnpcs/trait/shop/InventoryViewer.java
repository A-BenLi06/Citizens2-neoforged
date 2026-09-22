package net.citizensnpcs.trait.shop;

import java.util.ArrayList;
import java.util.List;

import com.google.common.primitives.Ints;

import net.citizensnpcs.api.gui.InputMenus;
import net.citizensnpcs.api.gui.InventoryMenuPage;
import net.citizensnpcs.api.gui.InventoryMenuSlot;
import net.citizensnpcs.api.gui.Menu;
import net.citizensnpcs.api.gui.MenuContext;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.StoredItemList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The shop's own stock: what it has to sell when it is not unlimited, editable by dragging items in and out.
 */
@Menu(title = "Item storage", dimensions = { 4, 9 })
public class InventoryViewer extends InventoryMenuPage {
    private MenuContext ctx;
    private NPCShopStorage storage;
    private boolean unavailable;

    public InventoryViewer() {
    }

    public InventoryViewer(NPCShopStorage storage) {
        this.storage = storage;
    }

    @Override
    public void initialise(MenuContext ctx) {
        this.ctx = ctx;
        List<ItemStack> inventory = storage.getInventory();
        unavailable = StoredItemList.hasUnavailable(inventory);
        if (unavailable) {
            ctx.getSlot(0).setItemStack(new ItemStack(Items.BARRIER), Messaging.tr("citizens.items.unavailable"),
                    Messaging.tr("citizens.items.unavailable-edit"));
            return;
        }
        for (int i = 0; i < 3 * 9; i++) {
            InventoryMenuSlot slot = ctx.getSlot(i);
            slot.clear();
            // the stock is meant to be rearranged by hand, so clicks here are the player's own
            slot.setClickHandler(evt -> evt.setCancelled(false));
            if (i < inventory.size()) {
                slot.setItemStack(inventory.get(i).copy());
            }
        }
        ctx.getSlot(3 * 9 + 1).setItemStack(new ItemStack(Items.BEACON), "Unlimited",
                storage.isUnlimited() ? "<green>On" : "<red>Off");
        ctx.getSlot(3 * 9 + 1).addClickHandler(InputMenus.toggler(storage::setUnlimited, storage.isUnlimited()));
        ctx.getSlot(3 * 9 + 2).setItemStack(new ItemStack(Items.COMPARATOR), "Inventory size limit",
                storage.getInventorySizeLimit() == -1 ? "<green>Unlimited"
                        : "<yellow>" + storage.getInventorySizeLimit());
        ctx.getSlot(3 * 9 + 2).addClickHandler(evt -> ctx.getMenu().transition(
                InputMenus.filteredStringSetter(() -> Integer.toString(storage.getInventorySizeLimit()), s -> {
                    Integer limit = Ints.tryParse(s);
                    if (limit == null)
                        return false;
                    storage.setInventorySizeLimit(limit);
                    return true;
                })));
    }

    @Override
    public void onClose(ServerPlayer player) {
        if (unavailable) return;
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 3 * 9; i++) {
            ItemStack stack = ctx.getSlot(i).getCurrentItem();
            if (stack != null && !stack.isEmpty()) {
                items.add(stack.copy());
            }
        }
        storage.setInventory(items);
    }
}
