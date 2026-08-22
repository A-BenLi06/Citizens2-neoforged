package net.citizensnpcs.api.gui;

/** The page {@link TestPage} transitions to. */
@Menu(title = "Second", type = InventoryType.HOPPER, dimensions = { 1, 5 })
public class SecondPage extends InventoryMenuPage {
    public SecondPage() {
    }

    @Override
    public void initialise(MenuContext ctx) {
        initialised = true;
    }

    static boolean initialised;
}
