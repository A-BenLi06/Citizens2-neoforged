package net.citizensnpcs.commands;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.command.CommandManager;
import net.citizensnpcs.api.command.CommandMessages;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.command.exception.CommandUsageException;
import net.citizensnpcs.api.command.exception.ServerCommandException;
import net.citizensnpcs.api.command.exception.UnhandledCommandException;
import net.citizensnpcs.api.command.exception.WrappedCommandException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.TextParser;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * Bridges Brigadier to Citizens' own command dispatcher.
 * <p>
 * Each root command gets exactly two Brigadier nodes: the bare literal, and the literal followed by one greedy string.
 * Everything after the root is handed to {@link CommandManager} as raw text. That is deliberate — Citizens' grammar is
 * {@code /npc create Bob --at 1,2,3 --type zombie -bstu}, with optional flags in any order, which does not decompose into
 * Brigadier argument nodes without rewriting all several thousand lines of annotated command methods.
 * <p>
 * Tab completion still works properly: the greedy argument's suggestion provider re-offsets the builder to the start of
 * the word being typed, so the client only replaces that word rather than the whole line.
 */
public class CommandRegistry {
    private final CommandManager commands;

    public CommandRegistry(CommandManager commands) {
        this.commands = commands;
    }

    /**
     * Registers a Brigadier node for every root command the manager knows about.
     */
    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String root : commands.getRootCommands()) {
            LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(root)
                    .executes(ctx -> run(ctx.getSource(), root, new String[] { "help" }));
            node.then(Commands.argument("args", StringArgumentType.greedyString())
                    .suggests((ctx, builder) -> suggest(ctx, builder, root))
                    .executes(ctx -> run(ctx.getSource(), root,
                            splitArgs(StringArgumentType.getString(ctx, "args")))));
            dispatcher.register(node);
        }
    }

    private int run(CommandSourceStack source, String root, String[] args) throws CommandSyntaxException {
        NPC selected = CitizensAPI.getDefaultNPCSelector().getSelected(source);
        // command methods are (CommandContext, sender, NPC); the manager fills the context slot itself, so the sender
        // and the selected NPC are what it needs from here
        try {
            // executeSafe reports whether an invocation was handled, even when it failed. Brigadier needs the outcome.
            commands.execute(root, args, source, source, selected);
            return 1;
        } catch (CommandException failure) {
            String message = failure.getMessage();
            if (failure instanceof ServerCommandException) message = Messaging.tr(CommandMessages.MUST_BE_INGAME);
            else if (failure instanceof UnhandledCommandException) message = Messaging.tr(CommandMessages.UNKNOWN_COMMAND);
            else if (failure instanceof WrappedCommandException) {
                if (failure.getCause() instanceof NumberFormatException) message = Messaging.tr(CommandMessages.INVALID_NUMBER);
                else {
                    (failure.getCause() == null ? failure : failure.getCause()).printStackTrace();
                    message = Messaging.tr(CommandMessages.REPORT_ERROR);
                }
            } else if (failure instanceof CommandUsageException usage) {
                message = (message == null || message.isBlank() ? "" : message + "\n")
                        + java.util.Objects.toString(usage.getUsage(), "");
            }
            if (message == null || message.isBlank()) message = Messaging.tr(CommandMessages.REPORT_ERROR);
            throw new SimpleCommandExceptionType(TextParser.parse(Messaging.convertLegacyCodes(message))).create();
        }
    }

    private CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder,
            String root) {
        String remaining = builder.getRemaining();
        String[] parts = splitArgs(remaining);
        // the client replaces only the word being typed, so the builder is re-anchored to where that word starts
        int lastWordStart = remaining.length() - parts[parts.length - 1].length();
        SuggestionsBuilder offset = builder.createOffset(builder.getStart() + lastWordStart);
        List<String> completions = commands.onTabComplete(ctx.getSource(), root, parts);
        for (String completion : completions) {
            if (completion != null && !completion.isEmpty()) {
                offset.suggest(completion);
            }
        }
        return offset.buildFuture();
    }

    /**
     * Splits on single spaces, keeping a trailing empty element so that "typing the next word" is distinguishable from
     * "finished the previous one" — which is what tab completion needs to tell apart.
     */
    private static String[] splitArgs(String raw) {
        return raw.split(" ", -1);
    }
}
