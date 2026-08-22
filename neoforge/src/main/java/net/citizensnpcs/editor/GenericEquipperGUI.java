package net.citizensnpcs.editor;

import java.util.function.Predicate;

import net.citizensnpcs.api.gui.CitizensInventoryClickEvent;
import net.citizensnpcs.api.gui.InjectContext;
import net.citizensnpcs.api.gui.InventoryMenuPage;
import net.citizensnpcs.api.gui.InventoryType;
import net.citizensnpcs.api.gui.Menu;
import net.citizensnpcs.api.gui.MenuContext;
import net.citizensnpcs.api.gui.MenuPattern;
import net.citizensnpcs.api.gui.MenuSlot;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot;
import net.citizensnpcs.util.Util;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The equipment menu: a row of labels, a row of worn items beneath each, and a third row for the cosmetic override that
 * only onlookers see.
 * <p>
 * The label row uses {@code compatMaterial} upstream — a list of item names to try in order, so one annotation works
 * across Minecraft versions that renamed or lacked an item. There is one Minecraft version here, so each label names its
 * item directly.
 */
@Menu(title = "NPC Equipment", type = InventoryType.CHEST, dimensions = { 3, 9 })
@MenuSlot(slot = { 0, 0 }, material = "minecraft:diamond_sword", lore = "Place in hand item below", amount = 1)
@MenuSlot(slot = { 0, 1 }, material = "minecraft:shield", lore = "Place offhand item below", amount = 1)
@MenuSlot(slot = { 0, 2 }, material = "minecraft:diamond_helmet", lore = "Place helmet below", amount = 1)
@MenuSlot(slot = { 0, 3 }, material = "minecraft:diamond_chestplate", lore = "Place chestplate below", amount = 1)
@MenuSlot(slot = { 0, 4 }, material = "minecraft:diamond_leggings", lore = "Place leggings below", amount = 1)
@MenuSlot(slot = { 0, 5 }, material = "minecraft:diamond_boots", lore = "Place boots below", amount = 1)
@MenuSlot(slot = { 0, 6 }, material = "minecraft:elytra", lore = "Place body item below", amount = 1)
@MenuSlot(slot = { 0, 7 }, material = "minecraft:saddle", lore = "Place saddle item below", amount = 1)
@MenuPattern(
        offset = { 0, 8 },
        slots = { @MenuSlot(pat = 'x', material = "minecraft:barrier", title = "<4>Unused") },
        value = "x\nx\nx")
public class GenericEquipperGUI extends InventoryMenuPage {
    @InjectContext
    private NPC npc;

    @Override
    public void initialise(MenuContext ctx) {
        Equipment trait = npc.getOrAddTrait(Equipment.class);
        for (int i = 0; i < SLOTS.length; i++) {
            EquipmentSlot slot = SLOTS[i];
            ctx.getSlot(9 + i).setItemStack(trait.get(slot));
            if (trait.getCosmetic(slot) != null) {
                ctx.getSlot(18 + i).setItemStack(trait.getCosmetic(slot));
            } else {
                ctx.getSlot(18 + i).setItemStack(noCosmetic());
            }
            Predicate<ItemStack> filter = filterFor(slot);
            ctx.getSlot(9 + i).addClickHandler(event -> set(slot, event, filter));
            ctx.getSlot(18 + i).addClickHandler(event -> setCosmetic(slot, event, filter));
        }
    }

    /**
     * Armour slots only accept what would actually be worn there. Asking the entity itself means armour from another mod
     * is judged correctly, which upstream's name-suffix test cannot manage.
     */
    private Predicate<ItemStack> filterFor(EquipmentSlot slot) {
        switch (slot) {
            case BOOTS:
            case LEGGINGS:
                return stack -> npc.getEntity() instanceof LivingEntity living
                        && Util.isEquippable(living, stack, slot);
            case CHESTPLATE:
                return stack -> stack.is(Items.ELYTRA) || npc.getEntity() instanceof LivingEntity living
                        && Util.isEquippable(living, stack, slot);
            default:
                return stack -> true;
        }
    }

    private static ItemStack noCosmetic() {
        return Util.createItem("minecraft:barrier", "No cosmetic",
                "Click to enable cosmetic for this equipment");
    }

    private void set(EquipmentSlot slot, CitizensInventoryClickEvent event, Predicate<ItemStack> filter) {
        ItemStack result = event.getResultItemNonNull();
        if (event.isCancelled() || !result.isEmpty() && !filter.test(result)) {
            event.setCancelled(true);
            return;
        }
        npc.getOrAddTrait(Equipment.class).set(slot, result);
    }

    /**
     * The cosmetic row cycles through three states with an empty cursor: showing a real item, showing "no cosmetic", and
     * cleared. With an item on the cursor it simply sets that as the cosmetic.
     */
    private void setCosmetic(EquipmentSlot slot, CitizensInventoryClickEvent event, Predicate<ItemStack> filter) {
        if (event.getCursorNonNull().isEmpty()) {
            if (event.getCurrentItemNonNull().is(Items.BARRIER)) {
                event.setCurrentItem(ItemStack.EMPTY);
                npc.getOrAddTrait(Equipment.class).setCosmetic(slot, ItemStack.EMPTY);
            } else if (event.getCurrentItem() == null) {
                event.setCurrentItem(noCosmetic());
                npc.getOrAddTrait(Equipment.class).setCosmetic(slot, null);
            } else {
                npc.getOrAddTrait(Equipment.class).setCosmetic(slot, ItemStack.EMPTY);
                return;
            }
            event.setCancelled(true);
            return;
        }
        ItemStack result = event.getResultItemNonNull();
        if (event.isCancelled() || !result.isEmpty() && !filter.test(result)) {
            event.setCancelled(true);
            return;
        }
        npc.getOrAddTrait(Equipment.class).setCosmetic(slot, result);
    }

    private static final EquipmentSlot[] SLOTS = { EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HELMET,
            EquipmentSlot.CHESTPLATE, EquipmentSlot.LEGGINGS, EquipmentSlot.BOOTS, EquipmentSlot.BODY,
            EquipmentSlot.SADDLE };
}
