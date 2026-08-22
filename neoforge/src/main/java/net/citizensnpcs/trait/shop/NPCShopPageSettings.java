package net.citizensnpcs.trait.shop;

import net.citizensnpcs.api.gui.CitizensInventoryClickEvent;
import net.citizensnpcs.api.gui.InputMenus;
import net.citizensnpcs.api.gui.InventoryMenuPage;
import net.citizensnpcs.api.gui.InventoryMenuSlot;
import net.citizensnpcs.api.gui.InventoryType;
import net.citizensnpcs.api.gui.Menu;
import net.citizensnpcs.api.gui.MenuContext;
import net.citizensnpcs.api.gui.MenuSlot;

/**
 * Settings for one page of a shop: its title, and removing it.
 */
@Menu(title = "NPC Shop Page Editor", type = InventoryType.CHEST, dimensions = { 5, 9 })
public class NPCShopPageSettings extends InventoryMenuPage {
    private MenuContext ctx;
    private final NPCShopPage page;

    public NPCShopPageSettings(NPCShopPage page) {
        this.page = page;
    }

    @MenuSlot(slot = { 0, 4 }, material = "minecraft:feather", amount = 1)
    public void editPageTitle(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
        ctx.getMenu().transition(InputMenus.stringSetter(() -> page.title,
                newTitle -> page.title = newTitle == null || newTitle.isEmpty() ? null : newTitle));
    }

    @Override
    public void initialise(MenuContext ctx) {
        this.ctx = ctx;
        ctx.getSlot(4).setDescription("Set page title<br>Currently: " + page.title);
    }

    @MenuSlot(slot = { 4, 4 }, material = "minecraft:tnt", amount = 1, title = "<red>Remove page")
    public void removePage(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
        ctx.data().put("removePage", page.getIndex());
        ctx.getMenu().transitionBack();
    }
}
