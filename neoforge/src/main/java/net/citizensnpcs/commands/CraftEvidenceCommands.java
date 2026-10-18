package net.citizensnpcs.commands;

import net.citizensnpcs.api.command.Command;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.command.Requirements;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.CraftQuestEvidence;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;

@Requirements
public final class CraftEvidenceCommands {
    @Command(aliases = { "citizens" }, modifiers = { "craftevidence" },
            usage = "craftevidence <bind <task ID> <recipe ID>|list|review|reconcile|resolve <entry ID> <accept|reject> <reason>>",
            desc = "citizens.commands.craftevidence.description", min = 2, permission = "citizens.admin")
    public void evidence(CommandContext args, CommandSourceStack source, NPC npc) throws CommandException {
        try {
            var service = CraftQuestEvidence.current(source.getServer());
            switch (args.getString(1).toLowerCase(java.util.Locale.ROOT)) {
                case "bind" -> {
                    require(args, 4);
                    var binding = service.bind(source, args.getString(2), ResourceLocation.parse(args.getString(3)));
                    Messaging.sendTr(source, "citizens.commands.craftevidence.bound", binding.taskId(), binding.recipeId(), binding.epoch());
                }
                case "list" -> {
                    require(args, 2);
                    for (var binding : service.bindings(source)) Messaging.sendTr(source, "citizens.commands.craftevidence.bound",
                            binding.taskId(), binding.recipeId(), binding.epoch());
                }
                case "review" -> {
                    require(args, 2);
                    for (var entry : service.entries(source)) if (entry.state() == net.citizensnpcs.util.QuestEvidenceJournal.State.REVIEW
                            || entry.state() == net.citizensnpcs.util.QuestEvidenceJournal.State.CONFIRMED)
                        Messaging.sendTr(source, "citizens.commands.craftevidence.entry", entry.id(), entry.admission().taskId(), entry.admission().actorId(),
                                entry.admission().units(), Messaging.tr("citizens.commands.craftevidence.state." + entry.state().name().toLowerCase(java.util.Locale.ROOT)));
                }
                case "reconcile" -> { require(args, 2); Messaging.sendTr(source, "citizens.commands.craftevidence.reconciled", service.reconcile(source)); }
                case "resolve" -> {
                    if (args.argsLength() < 5) throw new IllegalArgumentException("A review explanation is required");
                    boolean accept = switch (args.getString(3).toLowerCase(java.util.Locale.ROOT)) {
                        case "accept" -> true; case "reject" -> false;
                        default -> throw new IllegalArgumentException("Expected accept or reject");
                    };
                    var entry = service.resolve(source, args.getString(2), accept, args.getJoinedStrings(4));
                    Messaging.sendTr(source, "citizens.commands.craftevidence.resolved", entry.id());
                }
                default -> throw new IllegalArgumentException("Unknown craft evidence operation");
            }
        } catch (java.io.IOException | RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(CraftEvidenceCommands.class).warn("Craft evidence request failed for {}", source.getTextName(), failure);
            throw new CommandException("citizens.commands.craftevidence.failed");
        }
    }
    private static void require(CommandContext args, int length) {
        if (args.argsLength() != length) throw new IllegalArgumentException("Unexpected craft evidence arguments");
    }
}
