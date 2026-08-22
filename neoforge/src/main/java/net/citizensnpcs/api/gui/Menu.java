package net.citizensnpcs.api.gui;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Defines a GUI inventory menu. Placed at the class level of an {@link InventoryMenuPage}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE })
public @interface Menu {
    /**
     * The dimensions of the menu as {@code {rows, columns}}, if the {@link #type()} allows a choice.
     */
    int[] dimensions() default { 3, 3 };

    /**
     * The click types to allow by default. Empty = all allowed.
     */
    MenuClickType[] filter() default {};

    /**
     * The menu title. Passed through the text parser, so colour and formatting tags work.
     */
    String title() default "";

    /**
     * The container type to open as.
     */
    InventoryType type() default InventoryType.CHEST;
}
