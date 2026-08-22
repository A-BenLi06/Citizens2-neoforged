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
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/**
 * The block an enderman carries. Only a block item is accepted, and only one at a time, because that is all the entity
 * can hold.
 */
@Menu(title = "NPC Equipment", type = InventoryType.HOPPER, dimensions = { 1, 5 })
@MenuSlot(slot = { 0, 0 }, material = "minecraft:ender_pearl", amount = 1, lore = "Place a block to hold here ->")
@MenuPattern(
        offset = { 0, 2 },
        slots = { @MenuSlot(pat = 'x', material = "minecraft:barrier", title = "<4>Unused") },
        value = "xxx")
public class EndermanEquipperGUI extends InventoryMenuPage {
    @MenuSlot(slot = { 0, 1 })
    private InventoryMenuSlot hand;
    @InjectContext
    private NPC npc;

    @Override
    public void initialise(MenuContext ctx) {
        if (!(npc.getEntity() instanceof EnderMan enderman))
            return;
        var carried = enderman.getCarriedBlock();
        if (carried == null)
            return;
        ItemStack stack = new ItemStack(carried.getBlock(), 1);
        if (!stack.isEmpty()) {
            hand.setItemStack(stack);
        }
    }

    @ClickHandler(slot = { 0, 1 }, filter = { InventoryAction.PICKUP_ALL, InventoryAction.PLACE_ALL })
    public void setHand(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
        if (!(npc.getEntity() instanceof EnderMan enderman)) {
            event.setCancelled(true);
            return;
        }
        if (event.getAction() == InventoryAction.PICKUP_ALL && event.getCursor() != null) {
            event.setCancelled(true);
            return;
        }
        if (event.getAction() == InventoryAction.PLACE_ALL && (event.getCurrentItem() != null
                || !(event.getCursorNonNull().getItem() instanceof BlockItem) || event.getCursorNonNull().getCount() > 1)) {
            event.setCancelled(true);
            return;
        }
        if (event.getAction() != InventoryAction.PLACE_ALL) {
            enderman.setCarriedBlock(null);
            return;
        }
        ItemStack result = event.getResultItemNonNull();
        enderman.setCarriedBlock(
                result.getItem() instanceof BlockItem block ? block.getBlock().defaultBlockState() : null);
    }
}
