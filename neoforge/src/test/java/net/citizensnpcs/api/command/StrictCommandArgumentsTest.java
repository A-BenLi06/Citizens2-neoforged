package net.citizensnpcs.api.command;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import net.citizensnpcs.api.command.exception.CommandException;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class StrictCommandArgumentsTest {
    public enum Choice { FIRST, SECOND }
    private static int calls;
    private static Choice lastChoice;

    public static class Fixture {
        @Command(aliases = "strictaudit", modifiers = "set", desc = "", strictArguments = true)
        public static void set(CommandContext args, CommandSourceStack source,
                @Flag({"choice", "alias"}) Choice choice, @Flag("enabled") Boolean enabled,
                @Flag("number") Integer number, @Flag("fraction") Float fraction) { calls++; lastChoice = choice; }
    }

    private static CommandSourceStack source() {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, 4, "Audit",
                Component.literal("Audit"), null, null);
    }

    @Test void rejectsInvalidTypesBeforeTheCommandBody() {
        var manager = new CommandManager(); manager.register(Fixture.class); calls = 0;
        for (String[] arguments : List.of(
                new String[] {"set", "--choice", "missing"},
                new String[] {"set", "--enabled", "not-a-boolean"},
                new String[] {"set", "--number", "wrong"},
                new String[] {"set", "--fraction", "NaN"},
                new String[] {"set", "--fraction", "Infinity"},
                new String[] {"set", "--unknown", "value"})) {
            CommandSourceStack source = source();
            assertThrows(CommandException.class, () -> manager.execute("strictaudit", arguments, source, source));
        }
        assertEquals(0, calls);
    }

    @Test void namedFlagAliasesRemainValid() throws CommandException {
        var manager = new CommandManager(); manager.register(Fixture.class); calls = 0;
        CommandSourceStack source = source();
        manager.execute("strictaudit", new String[] {"set", "--alias", "SECOND", "--enabled", "true", "--number", "7"}, source, source);
        assertEquals(1, calls); assertEquals(Choice.SECOND, lastChoice);
    }
}
