package net.citizensnpcs.trait.shop;

import java.util.function.Consumer;

import net.citizensnpcs.Citizens;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.gui.InputMenus;
import net.citizensnpcs.api.gui.InventoryMenuPage;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.citizensnpcs.util.Util;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * Opens another shop, so one shop can act as a menu leading to others.
 */
public class OpenShopAction extends NPCShopAction {
    @Persist
    public String shopName;

    public OpenShopAction() {
    }

    public OpenShopAction(String shopName) {
        this.shopName = shopName;
    }

    @Override
    public String describe() {
        NPCShop shop = shops().getShop(shopName);
        return shop == null ? "Open shop (currently unset)" : "Open " + shop.getName();
    }

    @Override
    public int getMaxRepeats(Entity entity, InventoryMultiplexer inventory) {
        return -1;
    }

    @Override
    public Transaction grant(NPCShopStorage storage, Entity entity, InventoryMultiplexer inventory, int repeats) {
        return take(storage, entity, inventory, repeats);
    }

    @Override
    public Transaction take(NPCShopStorage storage, Entity entity, InventoryMultiplexer inventory, int repeats) {
        if (!(entity instanceof ServerPlayer player))
            return Transaction.fail();
        NPCShop shop = shops().getShop(shopName);
        // upstream dereferences the shop inside the possibility check, so a shop that has since been deleted throws on
        // every click instead of simply refusing
        if (shop == null)
            return Transaction.fail();
        return Transaction.create(() -> shop.canView(player), () -> {
            player.closeContainer();
            // a tick or two later: the client is still closing the old screen, and opening one inside the close would be
            // lost
            CitizensAPI.getScheduler().runEntityTaskLater(player, () -> shop.display(player), 2);
        }, () -> {
        });
    }

    private static StoredShops shops() {
        return Citizens.getInstance().getShops();
    }

    public static class OpenShopActionGUI implements GUI {
        @Override
        public boolean canUse(ServerPlayer entity) {
            return PermissionUtil.hasPermission(entity, "citizens.npc.shop.editor.actions.edit-open-shop");
        }

        @Override
        public InventoryMenuPage createEditor(NPCShopAction previous, Consumer<NPCShopAction> callback) {
            OpenShopAction action = previous == null ? new OpenShopAction() : (OpenShopAction) previous;
            return InputMenus.stringSetter(() -> action.shopName, s -> {
                if (s == null || s.isEmpty() || shops().getShop(s) == null) {
                    callback.accept(null);
                    return;
                }
                action.shopName = s;
                callback.accept(action);
            });
        }

        @Override
        public ItemStack createMenuItem(NPCShopAction previous) {
            return Util.createItem("minecraft:bookshelf", "Open Shop", previous == null ? null : previous.describe());
        }

        @Override
        public boolean manages(NPCShopAction action) {
            return action instanceof OpenShopAction;
        }
    }
}
