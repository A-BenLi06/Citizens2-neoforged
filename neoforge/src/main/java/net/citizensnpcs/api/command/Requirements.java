package net.citizensnpcs.api.command;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import net.citizensnpcs.api.trait.Trait;

/**
 * Preconditions a command declares, checked before its body runs.
 * <p>
 * The entity types are strings rather than an enum, because vanilla entity types are registry objects and an annotation
 * cannot hold one. Values may be a bare name ({@code "zombie"}) or a full id ({@code "minecraft:zombie"}), and types
 * added by other mods work. Upstream uses its enum's {@code UNKNOWN} constant to mean "any"; here an empty array means
 * the same thing, which also removes the need for a sentinel value.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Requirements {
    /** Entity types the NPC may look like, or empty for any. */
    String[] cosmeticTypes() default {};

    /** Entity types that are never allowed, whatever the other two lists say. */
    String[] excludedTypes() default {};

    /** Whether the NPC must be a living entity. */
    boolean livingEntity() default false;

    /** Whether the sender must own the NPC, unless they hold {@code citizens.admin}. */
    boolean ownership() default false;

    /** Whether an NPC must be selected. */
    boolean selected() default false;

    /** Traits the NPC must already have. */
    Class<? extends Trait>[] traits() default {};

    /** Entity types the NPC may actually be, or empty for any. */
    String[] types() default {};
}
