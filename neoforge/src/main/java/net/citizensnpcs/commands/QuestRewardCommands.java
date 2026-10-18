package net.citizensnpcs.commands;

import java.math.BigDecimal;
import java.util.Locale;
import net.citizensnpcs.api.command.*;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.FtbMonetaryRewards;
import net.minecraft.commands.CommandSourceStack;

@Requirements
public final class QuestRewardCommands {
    @Command(aliases = { "citizens" }, modifiers = { "questreward" },
            usage = "questreward <bind <reward ID> <currency ID> <amount>|list|review|reconcile|resolve <claim ID> <accept|reject> <reason>>",
            desc = "citizens.commands.questreward.description", min = 2, permission = "citizens.admin")
    public void reward(CommandContext args, CommandSourceStack source, NPC npc) throws CommandException {
        try {
            var service = FtbMonetaryRewards.current(source.getServer());
            switch (args.getString(1).toLowerCase(Locale.ROOT)) {
                case "bind" -> {
                    require(args, 5); var binding = service.bind(source, args.getString(2), args.getString(3), new BigDecimal(args.getString(4)));
                    Messaging.sendTr(source, "citizens.commands.questreward.bound", binding.rewardId(), binding.currencyId(),
                            BigDecimal.valueOf(binding.amount(), binding.currencyScale()).toPlainString());
                }
                case "list" -> {
                    require(args, 2); for (var binding : service.bindings(source)) Messaging.sendTr(source, "citizens.commands.questreward.bound",
                            binding.rewardId(), binding.currencyId(), BigDecimal.valueOf(binding.amount(), binding.currencyScale()).toPlainString());
                }
                case "review" -> {
                    require(args, 2); for (var claim : service.claims(source)) Messaging.sendTr(source, "citizens.commands.questreward.entry",
                            claim.id(), claim.intent().binding().rewardId(), claim.intent().beneficiaryId(),
                            Messaging.tr("citizens.commands.questreward.state." + claim.state().name().toLowerCase(Locale.ROOT)));
                }
                case "reconcile" -> { require(args, 2); Messaging.sendTr(source, "citizens.commands.questreward.reconciled", service.reconcile(source)); }
                case "resolve" -> {
                    if (args.argsLength() < 5) throw new IllegalArgumentException("A review reason is required");
                    boolean accept = switch (args.getString(3).toLowerCase(Locale.ROOT)) {
                        case "accept" -> true; case "reject" -> false; default -> throw new IllegalArgumentException("Expected accept or reject");
                    };
                    var claim = service.resolve(source, args.getString(2), accept, args.getJoinedStrings(4));
                    Messaging.sendTr(source, "citizens.commands.questreward.resolved", claim.id());
                }
                default -> throw new IllegalArgumentException("Unknown reward operation");
            }
        } catch (java.io.IOException | RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(QuestRewardCommands.class).warn("Monetary reward request failed for {}", source.getTextName(), failure);
            throw new CommandException("citizens.commands.questreward.failed");
        }
    }
    private static void require(CommandContext args, int length) { if (args.argsLength() != length) throw new IllegalArgumentException("Unexpected reward arguments"); }
}
