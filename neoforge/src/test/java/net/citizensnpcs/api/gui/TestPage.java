package net.citizensnpcs.api.gui;

/**
 * A page exercising every annotation the framework understands: a class-level slot, a field slot that gets injected, a
 * transition, a pattern, click handlers with and without an action filter, and an injected context value.
 * <p>
 * Results are recorded in static fields because the framework builds pages reflectively and the test never holds the
 * instance it made.
 */
@Menu(title = "Test", type = InventoryType.CHEST, dimensions = { 2, 9 })
@MenuSlot(slot = { 0, 0 }, material = "minecraft:diamond_sword", title = "Weapon", lore = "Put a sword here")
@MenuSlot(slot = { 0, 3 }, material = "minecraft:stone", title = "Unhandled")
@MenuTransition(pos = { 0, 2 }, value = SecondPage.class)
@MenuPattern(
        offset = { 0, 7 },
        slots = { @MenuSlot(pat = 'x', material = "minecraft:barrier", title = "Decoration") },
        value = "xx\nxx")
public class TestPage extends InventoryMenuPage {
    @InjectContext
    private String greeting;
    @MenuSlot(slot = { 0, 1 }, material = "minecraft:paper", title = "Filtered")
    private InventoryMenuSlot filtered;

    public TestPage() {
    }

    @ClickHandler(slot = { 0, 0 })
    public void onAnyClick(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
        clicks++;
        event.setCancelled(true);
    }

    @ClickHandler(slot = { 0, 1 }, filter = { InventoryAction.PLACE_ALL })
    public void onPlaceOnly(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
        filteredClicks++;
        event.setCancelled(true);
    }

    @Override
    public void initialise(MenuContext ctx) {
        lastContext = ctx;
        lastInjectedSlot = filtered;
        lastGreeting = greeting;
    }

    static int clicks;
    static int filteredClicks;
    static MenuContext lastContext;
    static String lastGreeting;
    static InventoryMenuSlot lastInjectedSlot;
}
