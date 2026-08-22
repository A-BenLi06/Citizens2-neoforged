package net.citizensnpcs.trait.shop;

import com.google.common.primitives.Doubles;

import net.citizensnpcs.api.command.CommandMessages;
import net.citizensnpcs.api.gui.CitizensInventoryClickEvent;
import net.citizensnpcs.api.gui.InputMenus;
import net.citizensnpcs.api.gui.InputMenus.Choice;
import net.citizensnpcs.api.gui.InventoryMenuPage;
import net.citizensnpcs.api.gui.InventoryMenuSlot;
import net.citizensnpcs.api.gui.InventoryType;
import net.citizensnpcs.api.gui.Menu;
import net.citizensnpcs.api.gui.MenuContext;
import net.citizensnpcs.api.gui.MenuSlot;
import net.citizensnpcs.api.util.EconomyProvider;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.trait.ShopTrait;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The top-level shop editor: permissions, title, type, right-click behaviour, balance and stock.
 */
@Menu(title = "NPC Shop Editor", type = InventoryType.CHEST, dimensions = { 2, 9 })
public class NPCShopSettings extends InventoryMenuPage {
    private MenuContext ctx;
    private final NPCShop shop;
    private final NPCShopStorage storage;
    private final ShopTrait trait;

    public NPCShopSettings(ShopTrait trait, NPCShop shop) {
        this.trait = trait;
        this.shop = shop;
        this.storage = trait != null && trait.getStorage() != null ? trait.getStorage() : shop.getStorage();
    }

    @Override
    public void initialise(MenuContext ctx) {
        this.ctx = ctx;
        ctx.getSlot(0).setDescription("<white>Edit permission required to view shop<br>" + shop.getRequiredPermission());
        ctx.getSlot(4).setDescription("<white>Edit shop title<br>" + shop.getTitle());
        if (trait != null) {
            ctx.getSlot(6).setDescription(
                    "<white>Show shop on right click<br>" + shop.getName().equals(trait.getRightClickShop()));
            ctx.getSlot(8).setDescription("<white>Set shop type<br>" + shop.getShopType());
        }
        if (EconomyProvider.getProvider() != null) {
            ctx.getSlot(1 * 9 + 3).setItemStack(new ItemStack(Items.EMERALD, 1));
            ctx.getSlot(1 * 9 + 3).setDescription("<white>Shop balance: " + storage.getBalance()
                    + "<br>Click to deposit<br>Shift click to withdraw");
        }
    }

    @MenuSlot(slot = { 1, 3 })
    public void onEditBalance(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
        EconomyProvider economy = EconomyProvider.getProvider();
        ServerPlayer player = event.getWhoClicked();
        if (economy == null || player == null)
            return;
        if (event.isShiftClick()) {
            ctx.getMenu().transition(InputMenus
                    .filteredStringSetter("Withdraw amount (max: " + storage.getBalance() + ")", () -> "", s -> {
                        Double amount = Doubles.tryParse(s);
                        if (amount == null || amount < 0 || amount > storage.getBalance())
                            return false;
                        if (!economy.deposit(player, amount))
                            return false;
                        storage.setBalance(storage.getBalance() - amount);
                        return true;
                    }));
            return;
        }
        double balance = economy.getBalance(player);
        ctx.getMenu().transition(InputMenus.filteredStringSetter("Deposit amount (max: " + balance + ")", () -> "",
                s -> {
                    Double amount = Doubles.tryParse(s);
                    if (amount == null || amount < 0)
                        return false;
                    if (!economy.withdraw(player, amount))
                        return false;
                    storage.setBalance(storage.getBalance() + amount);
                    return true;
                }));
    }

    @MenuSlot(slot = { 0, 2 }, material = "minecraft:feather", amount = 1, title = "<white>Edit shop items")
    public void onEditItems(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
        ctx.getMenu().transition(new NPCShopContentsEditor(shop));
    }

    @MenuSlot(slot = { 0, 0 }, material = "minecraft:oak_sign", amount = 1)
    public void onPermissionChange(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
        ctx.getMenu().transition(InputMenus.stringSetter(shop::getRequiredPermission, shop::setPermission));
    }

    @MenuSlot(slot = { 0, 8 }, material = "minecraft:chest", amount = 1, title = "<white>Set shop type")
    public void onSetInventoryType(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
        if (!hasPermission(event, "citizens.npc.shop.editor.set-shop-type"))
            return;
        ctx.getMenu().transition(InputMenus.picker("Set shop type",
                (Choice<ShopType> choice) -> shop.setShopType(choice.getValue()),
                Choice.of(ShopType.DEFAULT, "minecraft:chest", "Default (5x9 chest)",
                        shop.getShopType() == ShopType.DEFAULT),
                Choice.of(ShopType.CHEST_4X9, "minecraft:chest", "4x9 chest",
                        shop.getShopType() == ShopType.CHEST_4X9),
                Choice.of(ShopType.CHEST_3X9, "minecraft:chest", "3x9 chest",
                        shop.getShopType() == ShopType.CHEST_3X9),
                Choice.of(ShopType.CHEST_2X9, "minecraft:chest", "2x9 chest",
                        shop.getShopType() == ShopType.CHEST_2X9),
                Choice.of(ShopType.CHEST_1X9, "minecraft:chest", "1x9 chest",
                        shop.getShopType() == ShopType.CHEST_1X9),
                Choice.of(ShopType.TRADER, "minecraft:emerald", "Trader", shop.getShopType() == ShopType.TRADER)));
    }

    @MenuSlot(slot = { 0, 4 }, material = "minecraft:name_tag", amount = 1)
    public void onSetTitle(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
        ctx.getMenu().transition(InputMenus.stringSetter(shop::getTitle, shop::setTitle));
    }

    @MenuSlot(slot = { 0, 6 }, material = "minecraft:command_block", amount = 1)
    public void onToggleRightClick(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
        if (!hasPermission(event, "citizens.npc.shop.editor.show-on-right-click"))
            return;
        event.setCancelled(true);
        if (trait == null)
            return;
        trait.setRightClickShop(shop.getName().equals(trait.getRightClickShop()) ? null : shop.getName());
        ctx.getSlot(6)
                .setDescription("<white>Show shop on right click<br>" + shop.getName().equals(trait.getRightClickShop()));
    }

    @MenuSlot(slot = { 1, 1 }, material = "minecraft:ender_chest", amount = 1, title = "<white>View inventory")
    public void onViewItemStorage(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
        ctx.getMenu().transition(storage.createInventoryViewer(event.getWhoClicked()));
    }

    private boolean hasPermission(CitizensInventoryClickEvent event, String permission) {
        ServerPlayer player = event.getWhoClicked();
        if (player == null)
            return false;
        if (PermissionUtil.hasPermission(player, "citizens.admin") || PermissionUtil.hasPermission(player, permission))
            return true;
        event.setCancelled(true);
        Messaging.sendTr(player.createCommandSourceStack(), CommandMessages.NO_PERMISSION);
        return false;
    }
}
