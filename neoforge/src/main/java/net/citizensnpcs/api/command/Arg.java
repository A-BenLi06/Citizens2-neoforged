package net.citizensnpcs.api.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import com.google.common.base.Splitter;

import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.npc.NPC;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;

/**
 * Binds a command method parameter to a positional argument.
 * <p>
 * Upstream also ships an {@code OptionalKeyedCompletions} provider that reflects a Bukkit class name and reads either its
 * enum constants or its Bukkit registry. Neither shape exists here, so it is replaced by {@link RegistryCompletions} and
 * {@link EnumCompletions} — both are given what to read directly, which is type-safe and needs no reflection.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.PARAMETER })
public @interface Arg {
    String[] completions() default {};

    Class<? extends CompletionsProvider> completionsProvider() default CompletionsProvider.Identity.class;

    String defValue() default "";

    Class<? extends FlagValidator<?>> validator() default FlagValidator.Identity.class;

    int value();

    /** Supplies tab completions for an argument or flag. Implementations need a no-argument constructor. */
    public static interface CompletionsProvider {
        Collection<String> getCompletions(CommandContext args, CommandSourceStack sender, NPC npc);

        /** Offers nothing, which is the default. */
        public static class Identity implements CompletionsProvider {
            @Override
            public Collection<String> getCompletions(CommandContext args, CommandSourceStack sender, NPC npc) {
                return Collections.emptyList();
            }
        }
    }

    /**
     * Completions from a registry, so values added by other mods are offered too. Subclasses pass the registry they read.
     */
    public static abstract class RegistryCompletions<T> implements CompletionsProvider {
        private final Registry<T> registry;

        protected RegistryCompletions(Registry<T> registry) {
            this.registry = registry;
        }

        @Override
        public Collection<String> getCompletions(CommandContext args, CommandSourceStack sender, NPC npc) {
            return registry.keySet().stream().map(id -> id.getPath()).collect(Collectors.toList());
        }
    }

    /** Completions from an enum, by constant name. */
    public static abstract class EnumCompletions<E extends Enum<E>> implements CompletionsProvider {
        private final Collection<String> names;

        protected EnumCompletions(Class<E> type) {
            names = java.util.Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.toList());
        }

        @Override
        public Collection<String> getCompletions(CommandContext args, CommandSourceStack sender, NPC npc) {
            return names;
        }
    }

    /** Turns a flag or argument string into the parameter type, rejecting it with a message when it will not convert. */
    public static interface FlagValidator<T> {
        T validate(CommandContext args, CommandSourceStack sender, NPC npc, String input) throws CommandException;

        /** Passes the input through unchanged, which is the default. */
        public static class Identity implements FlagValidator<String> {
            @Override
            public String validate(CommandContext args, CommandSourceStack sender, NPC npc, String input)
                    throws CommandException {
                return input;
            }
        }
    }

    /** Parses {@code 1,2,3} into a float array. */
    public static class FloatArrayFlagValidator implements FlagValidator<float[]> {
        @Override
        public float[] validate(CommandContext args, CommandSourceStack sender, NPC npc, String input)
                throws CommandException {
            List<Float> list = Splitter.on(',').splitToStream(input).map(Float::parseFloat)
                    .collect(Collectors.toList());
            float[] arr = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                arr[i] = list.get(i);
            }
            return arr;
        }
    }
}
