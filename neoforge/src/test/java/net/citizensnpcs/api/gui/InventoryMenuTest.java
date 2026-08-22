package net.citizensnpcs.api.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Covers the parts of the menu framework that need no player: building a page out of its annotations, expanding a
 * pattern, injecting context, and routing a click to the right handler.
 * <p>
 * Presenting a menu needs a {@code ServerPlayer} and a live connection, so that half is exercised on a real server
 * instead. Everything here is what upstream drives by reflection, and is therefore the part most likely to break
 * silently.
 */
public class InventoryMenuTest {
    @BeforeAll
    public static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
    }

    @Test
    public void classLevelSlotsAreBuiltFromAnnotations() {
        InventoryMenu menu = InventoryMenu.create(new TestPage());
        MenuContext ctx = TestPage.lastContext;

        assertEquals(18, ctx.getSize());
        ItemStack sword = ctx.getSlot(0).getCurrentItemNonNull();
        assertTrue(sword.is(Items.DIAMOND_SWORD));
        assertEquals("Weapon", sword.get(DataComponents.CUSTOM_NAME).getString());
        assertEquals(List.of("Put a sword here"),
                sword.get(DataComponents.LORE).lines().stream().map(line -> line.getString()).toList());
        assertNotNull(menu);
    }

    @Test
    public void fieldSlotIsInjected() {
        InventoryMenu.create(new TestPage());
        assertNotNull(TestPage.lastInjectedSlot, "an @MenuSlot field should be assigned its concrete slot");
        assertEquals(1, TestPage.lastInjectedSlot.getIndex());
    }

    @Test
    public void patternFillsEveryRowAtTheOffset() {
        InventoryMenu.create(new TestPage());
        MenuContext ctx = TestPage.lastContext;
        for (int index : new int[] { 7, 8, 16, 17 }) {
            assertTrue(ctx.getSlot(index).getCurrentItemNonNull().is(Items.BARRIER),
                    "pattern slot " + index + " should be filled");
        }
        assertTrue(ctx.getSlot(6).getCurrentItemNonNull().isEmpty(),
                "the pattern should not spill left of its offset");
    }

    @Test
    public void contextIsInjectedByFieldName() {
        Map<String, Object> context = new HashMap<>();
        context.put("greeting", "hello");
        InventoryMenu.createWithContext(TestPage.class, context);
        assertEquals("hello", TestPage.lastGreeting);
    }

    @Test
    public void clickHandlerRunsForItsOwnSlot() {
        InventoryMenu menu = InventoryMenu.create(new TestPage());
        TestPage.clicks = 0;

        menu.handleClick(click(0, InventoryAction.PICKUP_ALL));
        assertEquals(1, TestPage.clicks);

        menu.handleClick(click(3, InventoryAction.PICKUP_ALL));
        assertEquals(1, TestPage.clicks, "a click on another slot must not reach this handler");
    }

    @Test
    public void clickHandlerFilterRejectsOtherActions() {
        InventoryMenu menu = InventoryMenu.create(new TestPage());
        TestPage.filteredClicks = 0;

        menu.handleClick(click(1, InventoryAction.PLACE_ALL));
        assertEquals(1, TestPage.filteredClicks);

        CitizensInventoryClickEvent rejected = click(1, InventoryAction.PICKUP_HALF);
        menu.handleClick(rejected);
        assertEquals(1, TestPage.filteredClicks, "an action outside the filter should not invoke the handler");
        assertTrue(rejected.isCancelled(), "an action outside the filter should be cancelled");
    }

    @Test
    public void slotWithoutAnyHandlerIsLockedByDefault() {
        InventoryMenu menu = InventoryMenu.create(new TestPage());
        CitizensInventoryClickEvent event = click(7, InventoryAction.PICKUP_ALL);
        menu.handleClick(event);
        assertTrue(event.isCancelled(), "a decoration slot should not be removable");
    }

    @Test
    public void clickingATransitionMovesToTheNextPage() {
        InventoryMenu menu = InventoryMenu.create(new TestPage());
        SecondPage.initialised = false;

        CitizensInventoryClickEvent event = click(2, InventoryAction.PICKUP_ALL);
        menu.handleClick(event);

        assertTrue(event.isCancelled(), "a transition consumes the click");
        assertTrue(SecondPage.initialised, "the next page should have been initialised");
    }

    @Test
    public void resultItemPredictsWhatTheSlotWillHold() {
        ItemStack inSlot = new ItemStack(Items.STONE, 4);
        ItemStack onCursor = new ItemStack(Items.STONE, 3);
        CitizensInventoryClickEvent place = new CitizensInventoryClickEvent(0, MenuClickType.LEFT,
                InventoryAction.PLACE_ALL, inSlot, onCursor, -1, List.of(), -1);
        assertEquals(7, place.getResultItemNonNull().getCount());

        CitizensInventoryClickEvent take = new CitizensInventoryClickEvent(0, MenuClickType.LEFT,
                InventoryAction.PICKUP_ALL, inSlot, ItemStack.EMPTY, -1, List.of(), -1);
        assertNull(take.getResultItem(), "taking the whole stack empties the slot");
    }

    @Test
    public void percentageHandlerClampsAndStepsByModifier() {
        PercentageSlotHandler handler = new PercentageSlotHandler(value -> "now " + value, 50);
        ItemStack current = new ItemStack(Items.PAPER, 1);

        handler.accept(clickWith(current, MenuClickType.LEFT));
        assertEquals(60, handler.getPercentage());

        handler.accept(clickWith(current, MenuClickType.SHIFT_LEFT));
        assertEquals(61, handler.getPercentage(), "shift should step by one");

        for (int i = 0; i < 10; i++) {
            handler.accept(clickWith(current, MenuClickType.LEFT));
        }
        assertEquals(100, handler.getPercentage(), "should clamp at 100");

        for (int i = 0; i < 20; i++) {
            handler.accept(clickWith(current, MenuClickType.RIGHT));
        }
        assertEquals(0, handler.getPercentage(), "should clamp at 0");
    }

    @Test
    public void booleanHandlerTogglesAndRewritesItsLore() {
        InputMenus.BooleanSlotHandler handler = new InputMenus.BooleanSlotHandler(value -> value ? "on" : "off", false);
        ItemStack current = new ItemStack(Items.PAPER, 1);

        CitizensInventoryClickEvent event = clickWith(current, MenuClickType.LEFT);
        handler.accept(event);
        assertTrue(handler.getValue());
        assertTrue(event.isCancelled());
        assertEquals(List.of("on"),
                current.get(DataComponents.LORE).lines().stream().map(line -> line.getString()).toList());

        handler.accept(clickWith(current, MenuClickType.LEFT));
        assertFalse(handler.getValue());
    }

    @Test
    public void clickTypesWidenFromVanilla() {
        assertEquals(MenuClickType.LEFT, MenuClickType.of(net.minecraft.world.inventory.ClickType.PICKUP, 0));
        assertEquals(MenuClickType.RIGHT, MenuClickType.of(net.minecraft.world.inventory.ClickType.PICKUP, 1));
        assertEquals(MenuClickType.SHIFT_RIGHT,
                MenuClickType.of(net.minecraft.world.inventory.ClickType.QUICK_MOVE, 1));
        assertEquals(MenuClickType.SWAP_OFFHAND, MenuClickType.of(net.minecraft.world.inventory.ClickType.SWAP, 40));
        assertEquals(MenuClickType.NUMBER_KEY, MenuClickType.of(net.minecraft.world.inventory.ClickType.SWAP, 3));
        assertTrue(MenuClickType.SHIFT_LEFT.isShiftClick());
        assertTrue(MenuClickType.RIGHT.isRightClick());
    }

    @Test
    public void chestSizeRoundsUpToWholeRows() {
        assertEquals(18, InventoryType.CHEST.getSize(new int[] { 2, 9 }));
        assertEquals(9, InventoryType.CHEST.getSize(new int[] { 1, 4 }));
        assertEquals(54, InventoryType.CHEST.getSize(new int[] { 9, 9 }));
        assertEquals(5, InventoryType.HOPPER.getSize(new int[] { 1, 5 }));
    }

    private static CitizensInventoryClickEvent click(int slot, InventoryAction action) {
        return new CitizensInventoryClickEvent(slot, MenuClickType.LEFT, action, ItemStack.EMPTY, ItemStack.EMPTY, -1,
                List.of(), -1);
    }

    private static CitizensInventoryClickEvent clickWith(ItemStack current, MenuClickType type) {
        return new CitizensInventoryClickEvent(0, type, InventoryAction.PICKUP_ALL, current, ItemStack.EMPTY, -1,
                List.of(), -1);
    }
}
