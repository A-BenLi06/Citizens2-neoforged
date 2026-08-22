package net.citizensnpcs.trait.shop;

import java.util.function.Consumer;

import net.citizensnpcs.api.gui.CitizensInventoryClickEvent;
import net.citizensnpcs.api.gui.InventoryMenuPage;
import net.citizensnpcs.api.gui.InventoryMenuSlot;
import net.citizensnpcs.api.gui.InventoryType;
import net.citizensnpcs.api.gui.Menu;
import net.citizensnpcs.api.gui.MenuContext;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Lays out what the shop sells: one slot per item, page by page.
 * <p>
 * Shift-clicking an item picks it up for copying; the navigation slots double as page buttons and, on shift-click, as
 * editors for the item sitting in them.
 */
@Menu(title = "NPC Shop Contents Editor", type = InventoryType.CHEST, dimensions = { 5, 9 })
public class NPCShopContentsEditor extends InventoryMenuPage {
    private NPCShopItem copying;
    private MenuContext ctx;
    private int page = 0;
    private final NPCShop shop;

    public NPCShopContentsEditor(NPCShop shop) {
        this.shop = shop;
    }

    public void changePage(int newPage) {
        page = newPage;
        ctx.setTitle("NPC Shop Contents Editor (" + (newPage + 1) + "/" + (shop.getPages().size() + 1) + ")");
        NPCShopPage shopPage = shop.getOrCreatePage(page);
        for (int i = 0; i < ctx.getSize(); i++) {
            InventoryMenuSlot slot = ctx.getSlot(i);
            slot.clear();
            if (shopPage.getItem(i) != null) {
                slot.setItemStack(shopPage.getItem(i).getDisplayItem(null));
            }
            int idx = i;
            slot.setClickHandler(evt -> {
                NPCShopItem display = shopPage.getItem(idx);
                if (display != null && evt.isShiftClick() && evt.getCursorNonNull().isEmpty()
                        && display.display != null) {
                    copying = display.clone();
                    if (!evt.isRightClick()) {
                        shopPage.setItem(idx, null);
                        slot.setItemStack(ItemStack.EMPTY);
                    }
                    evt.setCursor(display.getDisplayItem(null));
                    evt.setCancelled(true);
                    return;
                }
                if (display == null) {
                    if (copying != null && !evt.getCursorNonNull().isEmpty() && ItemStack
                            .isSameItemSameComponents(evt.getCursorNonNull(), copying.getDisplayItem(null))) {
                        shopPage.setItem(idx, copying);
                        slot.setItemStack(copying.getDisplayItem(null));
                        copying = null;
                        return;
                    }
                    display = new NPCShopItem();
                    if (!evt.getCursorNonNull().isEmpty()) {
                        display.setDisplayItem(evt.getCursor());
                    }
                }
                ctx.clearSlots();
                ctx.getMenu().transition(new NPCShopItemEditor(display, modified -> {
                    if (modified == null) {
                        shopPage.removeItem(idx);
                    } else {
                        shopPage.setItem(idx, modified);
                    }
                }));
            });
        }
        InventoryMenuSlot prev = ctx.getSlot(shop.getShopType().getPrevSlotIndex());
        InventoryMenuSlot edit = ctx.getSlot(shop.getShopType().getEditSlotIndex());
        InventoryMenuSlot next = ctx.getSlot(shop.getShopType().getNextSlotIndex());
        if (page > 0) {
            prev.setItemStack(shopPage.getNextPageItem(null, shop.getShopType().getPrevSlotIndex()),
                    "Previous page (" + newPage + ")");
            Consumer<CitizensInventoryClickEvent> prevItemEditor = prev.getClickHandlers().get(0);
            prev.setClickHandler(evt -> {
                if (evt.isShiftClick()) {
                    prevItemEditor.accept(evt);
                    return;
                }
                evt.setCancelled(true);
                changePage(page - 1);
            });
        }
        next.setItemStack(shopPage.getNextPageItem(null, shop.getShopType().getNextSlotIndex()),
                page + 1 >= shop.getPages().size() ? "New page" : "Next page (" + (newPage + 2) + ")");
        Consumer<CitizensInventoryClickEvent> nextItemEditor = next.getClickHandlers().get(0);
        next.setClickHandler(evt -> {
            if (evt.isShiftClick()) {
                nextItemEditor.accept(evt);
                return;
            }
            evt.setCancelled(true);
            changePage(page + 1);
        });

        Consumer<CitizensInventoryClickEvent> editPageItem = edit.getClickHandlers().get(0);
        edit.setItemStack(new ItemStack(Items.BOOK), "Edit page");
        edit.setClickHandler(evt -> {
            if (evt.isShiftClick()) {
                editPageItem.accept(evt);
                return;
            }
            ctx.getMenu().transition(new NPCShopPageSettings(shop.getOrCreatePage(page)));
        });
    }

    @Override
    public Container createContainer(String title) {
        return new SimpleContainer(shop.getShopType().getInventorySize());
    }

    @Override
    public void initialise(MenuContext ctx) {
        this.ctx = ctx;
        if (ctx.data().containsKey("removePage")) {
            int index = (int) ctx.data().remove("removePage");
            shop.removePage(index);
            page = Math.max(page - 1, 0);
        }
        changePage(page);
    }
}
