package net.citizensnpcs.api.gui;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Defines a block of slots and/or transitions by drawing them, so that a border or a grid of buttons does not need one
 * annotation per slot. Each character of {@link #value()} is looked up in {@link #slots()} and {@link #transitions()} by
 * their {@code pat()}; a newline starts the next row.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.FIELD, ElementType.CONSTRUCTOR, ElementType.METHOD })
@Repeatable(MenuPatterns.class)
public @interface MenuPattern {
    /**
     * The {@code {row, column}} the pattern starts at.
     */
    int[] offset();

    MenuSlot[] slots() default {};

    MenuTransition[] transitions() default {};

    /**
     * The pattern itself. Any character not named by a slot or transition is left alone.
     */
    String value();
}
