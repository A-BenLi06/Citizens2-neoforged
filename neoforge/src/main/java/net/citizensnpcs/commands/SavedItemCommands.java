package net.citizensnpcs.commands;

import net.citizensnpcs.api.command.Command;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.command.Requirements;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.util.RSItemEditorItems;
import net.minecraft.commands.CommandSourceStack;

@Requirements
public final class SavedItemCommands {
    @Command(aliases = { "citizens" }, modifiers = { "saveditem" }, usage = "saveditem <shared|personal> <ID> <amount>",
            desc = "citizens.commands.saveditem.description", min = 4, max = 4, strictArguments = true)
    public void give(CommandContext args, CommandSourceStack source, NPC npc) throws CommandException {
        try {
            var scope = RSItemEditorItems.Scope.valueOf(args.getString(1).toUpperCase(java.util.Locale.ROOT));
            RSItemEditorItems.give(source, source.getPlayer(), new RSItemEditorItems.Reference(scope, args.getString(2)),
                    Integer.parseInt(args.getString(3)), false);
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(SavedItemCommands.class).warn("Saved-item grant failed for {}", source.getTextName(), failure);
            throw new CommandException("citizens.commands.saveditem.failed");
        }
    }
}
