package net.citizensnpcs.editor;

import net.citizensnpcs.api.gui.CitizensInventoryClickEvent;
import net.citizensnpcs.api.gui.ClickHandler;
import net.citizensnpcs.api.gui.InjectContext;
import net.citizensnpcs.api.gui.InventoryAction;
import net.citizensnpcs.api.gui.InventoryMenuPage;
import net.citizensnpcs.api.gui.InventoryMenuSlot;
import net.citizensnpcs.api.gui.InventoryType;
import net.citizensnpcs.api.gui.Menu;
import net.citizensnpcs.api.gui.MenuContext;
import net.citizensnpcs.api.gui.MenuPattern;
import net.citizensnpcs.api.gui.MenuSlot;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.trait.Saddle;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Saddling a pig or a strider: one slot that takes a saddle and nothing else. */
@Menu(title = "NPC Equipment", type = InventoryType.HOPPER, dimensions = { 1, 5 })
@MenuSlot(slot = { 0, 0 }, material = "minecraft:saddle", amount = 1, lore = "Place a saddle here ->")
@MenuPattern(
        offset = { 0, 2 },
        slots = { @MenuSlot(pat = 'x', material = "minecraft:barrier", title = "<4>Unused") },
        value = "xxx")
public class SaddleEquipperGUI extends InventoryMenuPage {
    @InjectContext
    private NPC npc;
    @MenuSlot(slot = { 0, 1 })
    private InventoryMenuSlot saddle;

    @Override
    public void initialise(MenuContext ctx) {
        if (npc.getOrAddTrait(Saddle.class).useSaddle()) {
            saddle.setItemStack(new ItemStack(Items.SADDLE, 1));
        }
    }

    @ClickHandler(slot = { 0, 1 }, filter = { InventoryAction.PICKUP_ALL, InventoryAction.PLACE_ALL })
    public void setSaddle(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
        if (event.getAction() == InventoryAction.PICKUP_ALL && event.getCursor() != null) {
            event.setCancelled(true);
            return;
        }
        if (event.getAction() == InventoryAction.PLACE_ALL
                && (event.getCurrentItem() != null || !event.getCursorNonNull().is(Items.SADDLE))) {
            event.setCancelled(true);
            return;
        }
        npc.getOrAddTrait(Saddle.class).toggle();
    }
}
