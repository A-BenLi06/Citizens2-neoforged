package net.citizensnpcs.commands;

import java.util.Locale;
import java.util.UUID;
import net.citizensnpcs.api.command.*;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.QuestDeliveryJournal;
import net.citizensnpcs.util.QuestDeliveryRecovery;
import net.minecraft.commands.CommandSourceStack;

@Requirements
public final class QuestDeliveryCommands {
    @Command(aliases = { "citizens" }, modifiers = { "questdelivery" },
            usage = "questdelivery <list|review|inspect <delivery UUID>|resolve <delivery UUID> <accept|reject> <reason>>",
            desc = "citizens.commands.questdelivery.description", min = 2, permission = "citizens.admin")
    public void delivery(CommandContext args, CommandSourceStack source, NPC npc) throws CommandException {
        try {
            var service = QuestDeliveryRecovery.current(source.getServer());
            String operation = args.getString(1).toLowerCase(Locale.ROOT);
            switch (operation) {
                case "list", "review" -> {
                    if (args.argsLength() != 2) throw new IllegalArgumentException("Unexpected arguments");
                    for (var entry : service.entries(source))
                        if (operation.equals("list") || QuestDeliveryJournal.pending(entry)) present(source, entry);
                }
                case "inspect" -> {
                    if (args.argsLength() != 3) throw new IllegalArgumentException("Delivery UUID is required");
                    UUID id = UUID.fromString(args.getString(2));
                    var entry = service.entries(source).stream().filter(value -> value.id().equals(id)).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Unknown delivery"));
                    present(source, entry);
                    Messaging.sendTr(source, "citizens.commands.questdelivery.details", entry.actorId(), entry.before().teamId(),
                            entry.before().progress(), entry.after() == null ? "?" : entry.after().progress(), entry.before().maximum(),
                            entry.before().cycle(), entry.after() == null ? "?" : entry.after().cycle());
                    if (entry.uncertainty() != null) Messaging.sendTr(source, "citizens.commands.questdelivery.uncertainty", entry.uncertainty());
                }
                case "resolve" -> {
                    if (args.argsLength() < 5 || !java.util.Set.of("accept", "reject").contains(args.getString(3)))
                        throw new IllegalArgumentException("Expected accept/reject and an explanation");
                    var entry = service.resolve(source, UUID.fromString(args.getString(2)), args.getString(3).equals("accept"), args.getJoinedStrings(4));
                    Messaging.sendTr(source, "citizens.commands.questdelivery.resolved", entry.id());
                }
                default -> throw new IllegalArgumentException("Unknown delivery recovery operation");
            }
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(QuestDeliveryCommands.class).warn("Quest delivery review failed for {}", source.getTextName(), failure);
            throw new CommandException("citizens.commands.questdelivery.failed");
        }
    }
    private static void present(CommandSourceStack source, QuestDeliveryJournal.Entry entry) {
        Messaging.sendTr(source, "citizens.commands.questdelivery.entry", entry.id(), entry.before().taskId(),
                Messaging.tr("citizens.commands.questdelivery.state." + entry.state().name().toLowerCase(Locale.ROOT)));
    }
}
