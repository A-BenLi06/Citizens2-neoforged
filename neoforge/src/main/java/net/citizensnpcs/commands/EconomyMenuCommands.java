package net.citizensnpcs.commands;

import java.util.stream.IntStream;

import net.citizensnpcs.api.command.Command;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.command.Requirements;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.util.EconomyShopMenus;
import net.minecraft.commands.CommandSourceStack;

@Requirements
public final class EconomyMenuCommands {
    @Command(aliases = { "citizens" }, modifiers = { "economyshop" },
            usage = "economyshop <browser|shop|page|search|quantity|coordinate> <target fields>",
            desc = "citizens.commands.economyshop.description", min = 3, max = 5, strictArguments = true)
    public void open(CommandContext args, CommandSourceStack source, NPC npc) throws CommandException {
        try {
            var fields = IntStream.range(1, args.argsLength()).mapToObj(args::getString).toList();
            EconomyShopMenus.present(source.getPlayer(), EconomyShopMenus.parse(fields), false);
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(EconomyMenuCommands.class)
                    .warn("Economy menu request failed for {}", source.getTextName(), failure);
            throw new CommandException("citizens.commands.economyshop.failed");
        }
    }
}
