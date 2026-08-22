package net.citizensnpcs.api.gui;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Defines a slot that, when clicked, moves to another page. The page it came from is pushed onto a stack and returned to
 * when the new page closes.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.FIELD, ElementType.CONSTRUCTOR, ElementType.METHOD })
@Repeatable(MenuTransitions.class)
public @interface MenuTransition {
    /**
     * Which click types trigger the transition (empty = all).
     */
    MenuClickType[] filter() default {};

    /**
     * For use with patterns: the character in the pattern string this transition fills.
     */
    char pat() default '0';

    /**
     * The position of the slot as {@code {row, column}}.
     */
    int[] pos() default { 0, 0 };

    /**
     * The page to move to.
     */
    Class<? extends InventoryMenuPage> value();
}
