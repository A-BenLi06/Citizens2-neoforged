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
    public static final int TASKS_PER_PAGE = 9;

    @Command(aliases = { "citizens" }, modifiers = { "quest" }, usage = "quest <list [after ID]|details <ID>|status <ID>|submit <ID>>",
            desc = "citizens.commands.quest.description", min = 2, max = 3, strictArguments = true)
    public void task(CommandContext args, CommandSourceStack source, NPC npc) throws CommandException {
        try {
            if (args.getString(1).equalsIgnoreCase("list")) {
                presentTasks(source, FtbQuestTasks.discover(source.getPlayer(), args.argsLength() == 3 ? args.getString(2) : null, TASKS_PER_PAGE));
                return;
            }
            if (args.argsLength() != 3) throw new IllegalArgumentException("Task ID is required");
            if (args.getString(1).equalsIgnoreCase("details")) {
                presentDetails(source, FtbQuestTasks.describe(source.getPlayer(), args.getString(2))
                        .orElseThrow(() -> new IllegalArgumentException("Task details are hidden")));
                return;
            }
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

    public static void presentTasks(CommandSourceStack source, FtbQuestTasks.Page page) {
        if (page.tasks().isEmpty()) Messaging.sendTr(source, "citizens.commands.quest.list.empty");
        else for (var task : page.tasks()) presentTask(source, task);
        if (page.nextAfterId() != null) Messaging.sendTr(source, "citizens.commands.quest.list.next", page.nextAfterId());
    }

    public static void presentTask(CommandSourceStack source, FtbQuestTasks.TaskView task) {
        var title = net.minecraft.network.chat.Component.Serializer.fromJson(task.titleJson(), source.getServer().registryAccess());
        Messaging.sendTr(source, "citizens.commands.quest.list.entry", task.taskId(), title.getString(),
                Messaging.tr("citizens.commands.quest.kind." + task.kind().name().toLowerCase(java.util.Locale.ROOT)),
                task.state().progress(), task.state().maximum(),
                Messaging.tr(task.state().available() ? "citizens.commands.quest.available" : "citizens.commands.quest.unavailable"));
    }

    public static void presentDetails(CommandSourceStack source, FtbQuestTasks.TaskView task) {
        presentTask(source, task);
        var questTitle = net.minecraft.network.chat.Component.Serializer.fromJson(task.questTitleJson(), source.getServer().registryAccess());
        Messaging.sendTr(source, "citizens.commands.quest.details.quest", task.questId(), questTitle.getString());
        for (var dependency : task.prerequisites().visible()) presentDependency(source, dependency);
        if (task.prerequisites().hiddenCount() > 0)
            Messaging.sendTr(source, "citizens.commands.quest.details.hidden", task.prerequisites().hiddenCount());
        task.order().previous().ifPresent(dependency -> presentDependency(source, dependency));
        if (task.order().previousHidden()) Messaging.sendTr(source, "citizens.commands.quest.details.previous-hidden");
        if (task.cycle().repeatable()) Messaging.sendTr(source, "citizens.commands.quest.details.repeatable", task.cycle().completedCycles());
        for (var reward : task.rewards()) {
            String status = switch (reward.status()) {
                case "CAN_CLAIM" -> "ready";
                case "CLAIMED" -> "claimed";
                case "CANT_CLAIM" -> "unavailable";
                default -> "unknown";
            };
            Messaging.sendTr(source, "citizens.commands.quest.details.reward", reward.rewardId(),
                    Messaging.tr("citizens.commands.quest.claim." + status),
                    Messaging.tr("citizens.commands.quest.claim." + reward.scope().name().toLowerCase(java.util.Locale.ROOT)));
        }
    }

    private static void presentDependency(CommandSourceStack source, FtbQuestTasks.Dependency dependency) {
        var title = net.minecraft.network.chat.Component.Serializer.fromJson(dependency.titleJson(), source.getServer().registryAccess());
        Messaging.sendTr(source, "citizens.commands.quest.details.dependency", dependency.id(), title.getString(),
                Messaging.tr(dependency.completed() ? "citizens.commands.quest.completed" : "citizens.commands.quest.incomplete"));
    }
}
