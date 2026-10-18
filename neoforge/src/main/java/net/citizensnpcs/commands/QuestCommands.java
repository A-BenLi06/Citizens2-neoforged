package net.citizensnpcs.commands;

import net.citizensnpcs.api.command.Command;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.command.Requirements;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.FtbQuestTasks;
import net.minecraft.commands.CommandSourceStack;

@Requirements
public final class QuestCommands {
    @Command(aliases = { "citizens" }, modifiers = { "quest" }, usage = "quest <status|submit> <task ID>",
            desc = "citizens.commands.quest.description", min = 3, max = 3, strictArguments = true)
    public void task(CommandContext args, CommandSourceStack source, NPC npc) throws CommandException {
        try {
            var state = switch (args.getString(1).toLowerCase(java.util.Locale.ROOT)) {
                case "status" -> FtbQuestTasks.query(source.getPlayer(), args.getString(2));
                case "submit" -> FtbQuestTasks.submit(source.getPlayer(), args.getString(2), false);
                default -> throw new IllegalArgumentException("Unknown quest operation");
            };
            Messaging.sendTr(source, "citizens.commands.quest.status", state.taskId(), state.progress(), state.maximum(),
                    state.teamId());
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(QuestCommands.class).warn("FTB task request failed for {}", source.getTextName(), failure);
            throw new CommandException("citizens.commands.quest.failed");
        }
    }
}
