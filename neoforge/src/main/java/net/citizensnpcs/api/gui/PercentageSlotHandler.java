package net.citizensnpcs.api.gui;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A slot that holds a percentage: left click raises it, right click lowers it, and holding shift moves by one instead of
 * ten. The lore is rewritten after each click to show the new value.
 */
public class PercentageSlotHandler implements Consumer<CitizensInventoryClickEvent> {
    private int percentage;
    private final Function<Integer, String> transformer;

    public PercentageSlotHandler(Function<Integer, String> transformer) {
        this(transformer, 100);
    }

    public PercentageSlotHandler(Function<Integer, String> transformer, int initialPercentage) {
        this.transformer = transformer;
        this.percentage = initialPercentage;
    }

    @Override
    public void accept(CitizensInventoryClickEvent event) {
        int dx = event.isShiftClick() ? 1 : 10;
        if (event.isRightClick()) {
            dx *= -1;
        }
        percentage = Math.max(0, Math.min(100, percentage + dx));
        event.setCurrentItemDescription(transformer.apply(percentage));
        event.setCancelled(true);
    }

    public int getPercentage() {
        return percentage;
    }
}
