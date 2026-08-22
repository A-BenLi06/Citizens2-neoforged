package net.citizensnpcs.trait.shop;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.common.base.Joiner;

import net.citizensnpcs.api.command.CommandMessages;
import net.citizensnpcs.api.gui.CitizensInventoryClickEvent;
import net.citizensnpcs.api.gui.ClickHandler;
import net.citizensnpcs.api.gui.InputMenus;
import net.citizensnpcs.api.gui.InventoryMenuPage;
import net.citizensnpcs.api.gui.InventoryMenuPattern;
import net.citizensnpcs.api.gui.InventoryMenuSlot;
import net.citizensnpcs.api.gui.InventoryType;
import net.citizensnpcs.api.gui.Menu;
import net.citizensnpcs.api.gui.MenuContext;
import net.citizensnpcs.api.gui.MenuItems;
import net.citizensnpcs.api.gui.MenuPattern;
import net.citizensnpcs.api.gui.MenuSlot;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.trait.shop.NPCShopAction.GUI;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Edits one shop item: its display stack, its costs and results, its purchase limits and its messages.
 * <p>
 * Upstream puts six of these buttons in four slots — the already-purchased message shares a slot with the clickable
 * expression, and the failed-purchase message shares one with the visibility expression — so whichever is set up second
 * silently replaces the first, and two features cannot be reached at all. Each button gets its own slot here, and each
 * handler updates its own description rather than a neighbour's.
 */
@Menu(title = "NPC Shop Item Editor", type = InventoryType.CHEST, dimensions = { 6, 9 })
public class NPCShopItemEditor extends InventoryMenuPage {
    @MenuPattern(
            offset = { 0, 6 },
            slots = { @MenuSlot(pat = 'x', material = "minecraft:air") },
            value = "xxx\nxxx\nxxx")
    private InventoryMenuPattern actionItems;
    private NPCShopItem base;
    private final Consumer<NPCShopItem> callback;
    @MenuPattern(
            offset = { 0, 0 },
            slots = { @MenuSlot(pat = 'x', material = "minecraft:air") },
            value = "xxx\nxxx\nxxx")
    private InventoryMenuPattern costItems;
    private MenuContext ctx;
    private final NPCShopItem modified;

    public NPCShopItemEditor(NPCShopItem item, Consumer<NPCShopItem> consumer) {
        base = item;
        modified = base.clone();
        callback = consumer;
    }

    private ItemStack editTitle(ItemStack item, Function<String, String> transform) {
        String name = MenuItems.getDisplayName(item);
        MenuItems.setDisplayName(item, transform.apply(name == null ? "" : name));
        return item;
    }

    /** A slot showing a message setting, whose description is refreshed once the new message has been typed in chat. */
    private void messageSlot(int index, String material, String title, String prompt, Consumer<String> setter,
            Supplier<String> reader) {
        InventoryMenuSlot slot = ctx.getSlot(index);
        slot.setItemStack(MenuItems.byId(material, 1), title, reader.get() == null ? "Unset" : reader.get());
        slot.setClickHandler(event -> {
            ServerPlayer player = event.getWhoClicked();
            if (player == null)
                return;
            event.setCancelled(true);
            InputMenus.runChatStringSetter(ctx.getMenu(), player, prompt + "<br>[[" + reader.get(), s -> {
                setter.accept(s == null || s.isEmpty() ? null : s);
                slot.setDescription(reader.get() == null ? "Unset" : reader.get());
            });
        });
    }

    @Override
    public void initialise(MenuContext ctx) {
        this.ctx = ctx;
        if (modified.display != null) {
            ctx.getSlot(9 * 4 + 4).setItemStack(modified.getDisplayItem(null));
        }
        ctx.getSlot(9 * 4 + 7).setItemStack(new ItemStack(Items.APPLE), "Reset purchase history",
                modified.purchases.size() + " purchases");
        ctx.getSlot(9 * 4 + 7).setClickHandler(e -> {
            modified.resetPurchaseHistory();
            ctx.getSlot(9 * 4 + 7).setDescription(modified.purchases.size() + " purchases");
        });

        ctx.getSlot(9 * 3 + 6).setItemStack(new ItemStack(Items.EGG), "Number of purchases limit per player",
                limitDescription(modified.timesPurchasable));
        ctx.getSlot(9 * 3 + 6).setClickHandler(e -> ctx.getMenu()
                .transition(InputMenus.filteredStringSetter(() -> String.valueOf(modified.timesPurchasable), s -> {
                    try {
                        modified.timesPurchasable = Integer.parseInt(s.trim());
                    } catch (NumberFormatException ex) {
                        // upstream lets this escape the click handler, which closes the whole menu
                        return false;
                    }
                    ctx.getSlot(9 * 3 + 6).setDescription(limitDescription(modified.timesPurchasable));
                    return true;
                })));
        ctx.getSlot(9 * 4 + 6).setItemStack(new ItemStack(Items.EGG), "Global number of purchases limit",
                limitDescription(modified.globalTimesPurchasable));
        ctx.getSlot(9 * 4 + 6).setClickHandler(e -> ctx.getMenu().transition(
                InputMenus.filteredStringSetter(() -> String.valueOf(modified.globalTimesPurchasable), s -> {
                    try {
                        modified.globalTimesPurchasable = Integer.parseInt(s.trim());
                    } catch (NumberFormatException ex) {
                        return false;
                    }
                    ctx.getSlot(9 * 4 + 6).setDescription(limitDescription(modified.globalTimesPurchasable));
                    return true;
                })));

        messageSlot(9 * 4 + 2, "minecraft:oak_sign", "Set already purchased message",
                "Enter the new already purchased message, currently:", s -> modified.alreadyPurchasedMessage = s,
                () -> modified.alreadyPurchasedMessage);
        messageSlot(9 * 3 + 3, "minecraft:green_wool", "Set successful click message",
                "Enter the new successful click message, currently:", s -> modified.resultMessage = s,
                () -> modified.resultMessage);
        messageSlot(9 * 3 + 2, "minecraft:red_wool", "Set unsuccessful click message",
                "Enter the new unsuccessful click message, currently:", s -> modified.costMessage = s,
                () -> modified.costMessage);
        messageSlot(9 * 3 + 5, "minecraft:feather", "Set click to confirm message",
                "Enter the new click to confirm message, currently:", s -> modified.clickToConfirmMessage = s,
                () -> modified.clickToConfirmMessage);
        // the two buttons upstream loses to slot collisions
        messageSlot(9 * 3 + 0, "minecraft:beacon", "Set visibility expression",
                "Enter the visibility expression, currently:", s -> modified.isVisibleExpression = s,
                () -> modified.isVisibleExpression);
        messageSlot(9 * 3 + 1, "minecraft:anvil", "Set clickable expression",
                "Enter the clickable expression, currently:", s -> modified.isClickableExpression = s,
                () -> modified.isClickableExpression);

        ctx.getSlot(9 * 3 + 4).setItemStack(new ItemStack(Items.REDSTONE),
                "Sell as many times as possible on shift click", "Currently: " + modified.maxRepeatsOnShiftClick);
        ctx.getSlot(9 * 3 + 4).setClickHandler(
                InputMenus.toggler(res -> modified.maxRepeatsOnShiftClick = res, modified.maxRepeatsOnShiftClick));

        int pos = 0;
        for (GUI template : NPCShopAction.getGUIs()) {
            if (template.createMenuItem(null) == null)
                continue;

            NPCShopAction oldCost = modified.getCost().stream().filter(template::manages).findFirst().orElse(null);
            costItems.getSlots().get(pos)
                    .setItemStack(editTitle(template.createMenuItem(oldCost), title -> title + " Cost"));
            costItems.getSlots().get(pos).setClickHandler(event -> {
                if (!canUse(template, event))
                    return;
                ctx.getMenu().transition(
                        template.createEditor(oldCost, cost -> modified.changeCost(template::manages, cost)));
            });

            NPCShopAction oldResult = modified.getResult().stream().filter(template::manages).findFirst().orElse(null);
            actionItems.getSlots().get(pos)
                    .setItemStack(editTitle(template.createMenuItem(oldResult), title -> title + " Result"));
            actionItems.getSlots().get(pos).setClickHandler(event -> {
                if (!canUse(template, event))
                    return;
                ctx.getMenu().transition(
                        template.createEditor(oldResult, result -> modified.changeResult(template::manages, result)));
            });
            pos++;
        }
    }

    private boolean canUse(GUI template, CitizensInventoryClickEvent event) {
        ServerPlayer player = event.getWhoClicked();
        if (player == null)
            return false;
        if (PermissionUtil.hasPermission(player, "citizens.admin") || template.canUse(player))
            return true;
        event.setCancelled(true);
        Messaging.sendTr(player.createCommandSourceStack(), CommandMessages.NO_PERMISSION);
        return false;
    }

    private static String limitDescription(int limit) {
        return "Times purchasable: " + limit + (limit == 0 ? " (no limit)" : "");
    }

    @MenuSlot(slot = { 5, 3 }, material = "minecraft:redstone_block", amount = 1, title = "<gray>Cancel")
    public void onCancel(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
        ctx.getMenu().transitionBack();
    }

    @Override
    public void onClose(ServerPlayer who) {
        if (base != null && base.display == null) {
            base = null;
        }
        callback.accept(base);
    }

    @MenuSlot(slot = { 4, 5 }, material = "minecraft:book", amount = 1, title = "<white>Set description")
    public void onEditDescription(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
        event.setCancelled(true);
        ServerPlayer player = event.getWhoClicked();
        if (modified.display == null || player == null)
            return;
        List<String> lore = MenuItems.getLore(modified.display);
        InputMenus.runChatStringSetter(ctx.getMenu(), player,
                "Type the new item description, currently:<br>[["
                        + (lore.isEmpty() ? "Unset" : Joiner.on("<br>").skipNulls().join(lore)),
                description -> MenuItems.setLore(modified.display,
                        description.isEmpty() ? new ArrayList<>() : Messaging.parseComponentsList(description)));
    }

    @MenuSlot(slot = { 4, 3 }, material = "minecraft:name_tag", amount = 1, title = "<white>Set name")
    public void onEditName(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
        event.setCancelled(true);
        if (modified.display == null)
            return;
        ctx.getMenu().transition(InputMenus.stringSetter(() -> MenuItems.getDisplayName(modified.display),
                name -> MenuItems.setDisplayName(modified.display, Messaging.parseComponents(name))));
    }

    @ClickHandler(slot = { 4, 4 })
    public void onModifyDisplayItem(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
        event.setCancelled(true);
        if (!event.getCursorNonNull().isEmpty()) {
            event.setCurrentItem(event.getCursor());
            modified.setDisplayItem(event.getCursor());
        } else {
            event.setCurrentItem(ItemStack.EMPTY);
            modified.setDisplayItem(null);
        }
    }

    @MenuSlot(slot = { 5, 4 }, material = "minecraft:tnt", amount = 1, title = "<red>Remove")
    public void onRemove(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
        base = null;
        ctx.getMenu().transitionBack();
    }

    @MenuSlot(slot = { 5, 5 }, material = "minecraft:emerald_block", amount = 1, title = "<green>Save")
    public void onSave(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
        base = modified;
        ctx.getMenu().transitionBack();
    }
}
