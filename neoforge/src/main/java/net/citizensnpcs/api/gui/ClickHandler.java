package net.citizensnpcs.api.gui;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as the click handler for a slot. The method must take an {@link InventoryMenuSlot} and a
 * {@link CitizensInventoryClickEvent}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD })
@Repeatable(ClickHandlers.class)
public @interface ClickHandler {
    /**
     * Only handle these actions. Empty = handle every click.
     */
    InventoryAction[] filter() default {};

    /**
     * The slot position to handle clicks for, as {@code {row, column}}.
     */
    int[] slot();
}
