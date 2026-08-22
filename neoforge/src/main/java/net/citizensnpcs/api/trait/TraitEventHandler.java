package net.citizensnpcs.api.trait;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.function.Function;

import net.citizensnpcs.api.npc.NPC;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;

/**
 * Marks a {@link Trait} method as an event handler. {@link TraitInfo#registerListener()} wires every annotated method
 * to the NeoForge event bus, dispatching only to the trait instance attached to the NPC the event concerns.
 * <p>
 * Upstream nests Bukkit's {@code @EventHandler} as the annotation's {@code value()} to carry priority and
 * cancellation preference. NeoForge has no equivalent method-level annotation to nest, so those two settings are
 * declared directly here instead.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface TraitEventHandler {
    /** Whether to run even when an earlier listener cancelled the event. Mirrors Bukkit's inverted ignoreCancelled. */
    boolean receiveCanceled() default false;

    Class<? extends NPCEventExtractor> processor() default NPCEventExtractor.class;

    EventPriority priority() default EventPriority.NORMAL;

    public interface NPCEventExtractor extends Function<Event, NPC> {
    }
}
