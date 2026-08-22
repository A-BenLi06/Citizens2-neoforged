package net.citizensnpcs.api.gui;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;

/**
 * One page of a menu. Subclasses describe their slots with annotations and fill them in {@link #initialise(MenuContext)};
 * fields marked {@link InjectContext} are populated before that runs.
 * <p>
 * A page must have a no-argument constructor to be reachable by {@link MenuTransition}, which builds it reflectively. A
 * page constructed by hand and passed to {@link InventoryMenu#create(InventoryMenuPage)} may take whatever it likes.
 *
 * @see InventoryMenu
 */
public abstract class InventoryMenuPage implements Runnable {
    /**
     * Overriding this lets a page decide its own container size at runtime rather than through the fixed
     * {@code @Menu(dimensions = ...)} — a chooser sized to the number of choices, for instance.
     *
     * @return a container to use, or null to let the {@link Menu} annotation decide
     */
    public Container createContainer(String title) {
        return null;
    }

    public abstract void initialise(MenuContext ctx);

    public void onClick(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
    }

    public void onClose(ServerPlayer player) {
    }

    @Override
    public void run() {
    }
}
