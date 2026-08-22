package net.citizensnpcs.api.gui;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Defines a slot holding a particular display item. Can annotate an {@link InventoryMenuSlot} field, which is then
 * injected at runtime, or sit at the class/method level.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.FIELD, ElementType.CONSTRUCTOR, ElementType.METHOD })
@Repeatable(MenuSlots.class)
public @interface MenuSlot {
    /**
     * The stack size to display.
     */
    int amount() default 1;

    /**
     * The lore of the display item, newline-delimited.
     */
    String lore() default "EMPTY";

    /**
     * The item to display, as a registry id such as {@code minecraft:paper} or just {@code paper}.
     * <p>
     * Upstream takes a Bukkit {@code Material} enum constant here. An id string is used instead because Minecraft items
     * are registry objects rather than enum constants and an annotation cannot hold one — the same substitution the
     * command layer already makes for {@code @Requirements(types = ...)}. It also means an item added by another mod can
     * be named here, which an enum could never do.
     */
    String material() default "minecraft:air";

    /**
     * For use with patterns: the character in the pattern string this slot fills.
     */
    char pat() default '0';

    /**
     * The position of the slot as {@code {row, column}}.
     */
    int[] slot() default { 0, 0 };

    /**
     * The display name of the item.
     */
    String title() default "EMPTY";
}
