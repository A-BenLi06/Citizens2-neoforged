package net.citizensnpcs.api.command;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Marks a method as a command body. Unchanged from upstream: the whole point of keeping this framework is that the
 * hundred-odd annotated command methods carry over as they are.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Command {
    /** Root-level aliases, so {@code {"npc", "npc2"}} matches both /npc and /npc2. */
    String[] aliases();

    /** Short description shown with the usage and in help. A translation key. */
    String desc();

    /** Single-character switches this command accepts, or {@code *} for any. */
    String flags() default "";

    /** Longer description shown in help in addition to {@link #desc()}. A translation key. */
    String help() default "";

    /** Most arguments accepted; -1 for unlimited. */
    int max() default -1;

    /** Fewest arguments accepted. */
    int min() default 0;

    /** The sub-command words this method answers to, or {@code *} for any. */
    String[] modifiers() default "";

    /** Whether to expand placeholders in the input before parsing. */
    boolean parsePlaceholders() default false;

    /** Permission the sender must hold. */
    String permission() default "";

    /** Whether at least one flag or value flag is required. */
    boolean requiresFlags() default false;

    /** Reject unknown value flags and invalid typed inputs before executing an annotated command. */
    boolean strictArguments() default false;

    /** Usage line shown when the command is used wrongly. */
    String usage() default "";

    /** Value flags this command accepts, without the leading dashes. */
    String[] valueFlags() default {};
}
