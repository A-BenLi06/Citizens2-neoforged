package net.citizensnpcs.commands;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.Messages;

class TemplateCommandValidationTest {
    @Test void invalidNamesFailAsCommandsBeforeAccessingPluginOrNpc() {
        var commands = new TemplateCommands(null);
        for (String name : java.util.List.of("", "audit:", "bad name")) {
            var failure = assertThrows(CommandException.class, () -> commands.generate(null, null, null, name));
            assertEquals(Messaging.tryTranslate(Messages.TEMPLATE_MISSING), failure.getMessage());
        }
    }
}
