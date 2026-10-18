package net.citizensnpcs.commands;

import java.util.Locale;
import java.util.UUID;
import net.citizensnpcs.api.command.*;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.CommerceQuestTasks;
import net.citizensnpcs.util.CommerceTaskJournal.*;
import net.minecraft.commands.CommandSourceStack;

@Requirements
public final class CommerceTaskCommands {
    @Command(aliases = { "citizens" }, modifiers = { "commercetask" },
            usage = "commercetask <bind <task ID> <system|coordinate> <shop ID> <offer ID|-> <buy|sell> <items|trades|minor_units> <currency ID|-> <settlement|delivered>|list|review|reconcile|resolve <entry ID> <team UUID> <cycle> <accept|reject> <reason>>",
            desc = "citizens.commands.commercetask.description", min = 2, permission = "citizens.admin")
    public void commerce(CommandContext args, CommandSourceStack source, NPC npc) throws CommandException {
        try {
            var service = CommerceQuestTasks.current(source.getServer());
            switch (args.getString(1).toLowerCase(Locale.ROOT)) {
                case "bind" -> {
                    require(args, 10);
                    Action action = switch (args.getString(6).toLowerCase(Locale.ROOT)) { case "buy" -> Action.BUY_FROM_SHOP; case "sell" -> Action.SELL_TO_SHOP; default -> throw new IllegalArgumentException("Unknown commerce action"); };
                    boolean delivery = switch (args.getString(9).toLowerCase(Locale.ROOT)) { case "settlement" -> false; case "delivered" -> true; default -> throw new IllegalArgumentException("Unknown outcome policy"); };
                    var binding = service.bind(source, args.getString(2), Shop.valueOf(args.getString(3).toUpperCase(Locale.ROOT)), args.getString(4),
                            args.getString(5).equals("-") ? "" : args.getString(5), action, Measure.valueOf(args.getString(7).toUpperCase(Locale.ROOT)),
                            args.getString(8).equals("-") ? null : args.getString(8), delivery);
                    Messaging.sendTr(source, "citizens.commands.commercetask.bound", binding.taskId(), binding.shopId(), binding.itemKey());
                }
                case "list" -> { require(args, 2); for (var binding : service.bindings(source)) Messaging.sendTr(source, "citizens.commands.commercetask.bound", binding.taskId(), binding.shopId(), binding.itemKey()); }
                case "review" -> { require(args, 2); for (var entry : service.entries(source)) Messaging.sendTr(source, "citizens.commands.commercetask.entry", entry.id(), entry.binding().taskId(), entry.request().actorId(),
                        Messaging.tr("citizens.commands.commercetask.state." + entry.state().name().toLowerCase(Locale.ROOT))); }
                case "reconcile" -> { require(args, 2); Messaging.sendTr(source, "citizens.commands.commercetask.reconciled", service.reconcile(source)); }
                case "resolve" -> {
                    if (args.argsLength() < 7) throw new IllegalArgumentException("A review explanation is required");
                    boolean accept = switch (args.getString(5).toLowerCase(Locale.ROOT)) { case "accept" -> true; case "reject" -> false; default -> throw new IllegalArgumentException("Expected accept or reject"); };
                    var entry = service.resolve(source, args.getString(2), UUID.fromString(args.getString(3)), args.getInteger(4), accept, args.getJoinedStrings(6));
                    Messaging.sendTr(source, "citizens.commands.commercetask.resolved", entry.id());
                }
                default -> throw new IllegalArgumentException("Unknown commerce task operation");
            }
        } catch (java.io.IOException | RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(CommerceTaskCommands.class).warn("Commerce task request failed for {}", source.getTextName(), failure);
            throw new CommandException("citizens.commands.commercetask.failed");
        }
    }
    private static void require(CommandContext args, int length) { if (args.argsLength() != length) throw new IllegalArgumentException("Unexpected commerce task arguments"); }
}
