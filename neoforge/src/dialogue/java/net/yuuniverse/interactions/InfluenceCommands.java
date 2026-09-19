package net.yuuniverse.interactions;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import net.citizensnpcs.api.util.PermissionUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;

final class InfluenceCommands {
    static void register(LiteralArgumentBuilder<CommandSourceStack> root, Influence influence,
            ConversationLibrary conversations) {
        var command = Commands.literal("influence")
                .requires(source -> PermissionUtil.hasPermission(source, "interactions.admin"));
        for (String verb : java.util.List.of("get", "set", "add", "remove")) {
            // Greedy strings use the vanilla wire type while our parser accepts legacy Unicode filenames.
            var target = Commands.argument("parameters", StringArgumentType.greedyString())
                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                            conversations.ids().stream().map(StringArgumentType::escapeIfRequired), builder))
                    .executes(new Executor(influence, conversations, verb));
            command.then(Commands.literal(verb).then(Commands.argument("player", EntityArgument.player()).then(target)));
        }
        root.then(command);
    }

    static final class Executor implements com.mojang.brigadier.Command<CommandSourceStack> {
        private final Influence influence;
        private final ConversationLibrary conversations;
        private final String verb;

        Executor(Influence influence, ConversationLibrary conversations, String verb) {
            this.influence = influence; this.conversations = conversations; this.verb = verb;
        }

        /** Checks grammar/targets only; an action batch can change influence before this command executes. */
        void validate(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
            EntityArgument.getPlayer(context, "player");
            parameters(context, conversations, verb);
        }

        @Override public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
            return execute(context, influence, conversations, verb);
        }
    }

    private static Parameters parameters(CommandContext<CommandSourceStack> context,
            ConversationLibrary conversations, String verb) throws CommandSyntaxException {
        Parameters parameters;
        try { parameters = parse(StringArgumentType.getString(context, "parameters"), verb); }
        catch (IllegalArgumentException | CommandSyntaxException failure) {
            throw error(Component.translatableWithFallback("interactions.influence.arguments",
                    "Use a conversation filename followed by an integer amount; add/remove require a positive amount. Use get without an amount."));
        }
        String conversation = parameters.conversation();
        if (conversations.byId(conversation) == null) {
            throw error(Component.translatableWithFallback("interactions.influence.unknown_conversation",
                    "Unknown conversation: %s", conversation));
        }
        return parameters;
    }

    private static int execute(CommandContext<CommandSourceStack> context, Influence influence,
            ConversationLibrary conversations, String verb) throws CommandSyntaxException {
        var source = context.getSource();
        var player = EntityArgument.getPlayer(context, "player");
        Parameters parameters = parameters(context, conversations, verb);
        String conversation = parameters.conversation();
        try {
            int result = verb.equals("get") ? influence.get(player, conversation)
                    : influence.change(player, conversation, Influence.Operation.valueOf(verb.toUpperCase(java.util.Locale.ROOT)),
                            parameters.amount());
            source.sendSuccess(() -> Component.translatableWithFallback("interactions.influence.value",
                    "%s has %s influence with %s.", player.getDisplayName(), result, conversation), !verb.equals("get"));
            return 1;
        } catch (ArithmeticException failure) {
            throw error(Component.translatableWithFallback("interactions.influence.overflow",
                    "Influence must remain between %s and %s.", Integer.MIN_VALUE, Integer.MAX_VALUE));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            org.slf4j.LoggerFactory.getLogger("interactions").error("Could not access influence for {} / {}",
                    player.getUUID(), conversation, failure);
            throw error(Component.translatableWithFallback("interactions.influence.unavailable",
                    "This player's influence is unavailable. Check the server log and player data."));
        }
    }

    private static CommandSyntaxException error(Component message) {
        return new SimpleCommandExceptionType(message).create();
    }

    record Parameters(String conversation, int amount) { }

    static Parameters parse(String raw, String verb) throws CommandSyntaxException {
        StringReader reader = new StringReader(raw.strip());
        String conversation = argument(reader);
        int amount = verb.equals("get") ? 0 : ActionArguments.integer(argument(reader),
                verb.equals("set") ? Integer.MIN_VALUE : 1, Integer.MAX_VALUE);
        reader.skipWhitespace();
        if (reader.canRead()) throw new IllegalArgumentException("Unexpected influence arguments");
        return new Parameters(conversation, amount);
    }

    private static String argument(StringReader reader) throws CommandSyntaxException {
        reader.skipWhitespace();
        if (!reader.canRead()) throw new IllegalArgumentException("Missing influence argument");
        if (StringReader.isQuotedStringStart(reader.peek())) {
            String value = reader.readQuotedString();
            if (reader.canRead() && !Character.isWhitespace(reader.peek()))
                throw new IllegalArgumentException("Missing argument separator");
            return value;
        }
        int start = reader.getCursor();
        while (reader.canRead() && !Character.isWhitespace(reader.peek())) reader.skip();
        return reader.getString().substring(start, reader.getCursor());
    }
}
