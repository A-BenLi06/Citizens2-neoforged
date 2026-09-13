package net.citizensnpcs.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.common.primitives.Ints;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.command.CommandMessages;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.command.exception.CommandUsageException;
import net.citizensnpcs.api.command.exception.ServerCommandException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.util.ChatPrompt;
import net.citizensnpcs.api.util.ChatPromptSession;
import net.citizensnpcs.api.util.ChatPrompts;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.Messages;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * Resolves the NPC a command argument refers to, asking the sender which one when a name is ambiguous.
 * <p>
 * Commands like {@code /npc despawn} take an id, a uuid <em>or</em> a name; a name can match several NPCs, and this asks
 * the sender to pick by id rather than silently acting on whichever the registry happened to iterate first.
 * <p>
 * Upstream builds this on Bukkit's Conversation API as a {@code NumericPrompt}. That has no NeoForge counterpart, so it
 * runs on {@link ChatPrompts} instead — the same machinery the port's other chat-driven editors use. The input is parsed
 * here rather than by the framework, which is the one thing {@code NumericPrompt} did for free.
 * <p>
 * A console sender cannot be asked a question, so for it an ambiguous name is a plain error naming the candidates instead
 * of a prompt that could never be answered.
 */
public class NPCCommandSelector implements ChatPrompt {
    private final Callback callback;
    private final List<NPC> choices;
    private final CommandSourceStack sender;

    public NPCCommandSelector(Callback callback, CommandSourceStack sender, List<NPC> possible) {
        this.callback = callback;
        this.sender = sender;
        this.choices = List.copyOf(possible);
    }

    @Override
    public ChatPrompt acceptInput(ChatPromptSession session, String input) {
        Integer id = Ints.tryParse(input.trim());
        if (id == null) {
            Messaging.sendErrorTr(sender, CommandMessages.INVALID_NUMBER);
            return this;
        }
        NPC choice = choices.stream().filter(npc -> npc.getId() == id.intValue()).findFirst().orElse(null);
        if (choice == null) {
            Messaging.sendErrorTr(sender, Messages.SELECTION_PROMPT_INVALID_CHOICE, id);
            return this;
        }
        NPC target = choice.getOwningRegistry().getByUniqueId(choice.getUniqueId());
        if (target == null || target.getId() != choice.getId()) {
            Messaging.sendErrorTr(sender, Messages.NPC_NOT_FOUND);
            return this;
        }
        try {
            callback.run(target);
        } catch (ServerCommandException ex) {
            Messaging.sendErrorTr(sender, CommandMessages.MUST_BE_INGAME);
        } catch (CommandUsageException ex) {
            Messaging.sendError(sender, ex.getMessage());
            Messaging.sendError(sender, ex.getUsage());
        } catch (CommandException ex) {
            Messaging.sendError(sender, Messaging.tryTranslate(ex.getMessage()));
        }
        return null;
    }

    @Override
    public String getPromptText(ChatPromptSession session) {
        StringBuilder text = new StringBuilder(Messaging.tr(Messages.SELECTION_PROMPT));
        for (NPC npc : choices) {
            text.append("<br>    - ").append(npc.getId()).append(" (").append(npc.getName()).append(')');
        }
        Messaging.send(sender, text.toString());
        return "";
    }

    public static interface Callback {
        void run(NPC npc) throws CommandException;
    }

    /**
     * Runs {@code callback} against the NPC {@code raw} names, prompting when a name matches more than one.
     *
     * @param raw
     *            an NPC id, a uuid, or a name
     */
    public static void startWithCallback(Callback callback, NPCRegistry registry, CommandSourceStack sender,
            CommandContext args, String raw) throws CommandException {
        UUID uuid;
        try { uuid = UUID.fromString(raw); }
        catch (IllegalArgumentException notUuid) { uuid = null; }
        if (uuid != null) {
            callback.run(registry.getByUniqueIdGlobal(uuid));
            return;
        }
        Integer id = Ints.tryParse(raw);
        if (id != null) {
            callback.run(id < 0 ? null : registry.getById(id));
            return;
        }
        double range = args.hasValueFlag("range") ? Math.abs(args.getFlagDouble("range")) : -1;
        Location from = range > 0 ? args.getSenderLocation() : null;
        List<NPC> possible = new ArrayList<>();
        for (NPC test : registry) {
            if (!test.getName().equalsIgnoreCase(raw)) {
                continue;
            }
            if (range > 0 && test.isSpawned() && from != null
                    && (from.getWorld() != test.getStoredLocation().getWorld()
                            || from.distance(test.getStoredLocation()) > range)) {
                continue;
            }
            possible.add(test);
        }
        if (possible.isEmpty()) {
            callback.run(null);
            return;
        }
        if (possible.size() == 1) {
            callback.run(possible.get(0));
            return;
        }
        ServerPlayer player = sender.getPlayer();
        if (player == null) {
            // nothing can answer a prompt on the console's behalf, so say what the candidates are and stop
            StringBuilder ids = new StringBuilder();
            for (NPC each : possible) {
                ids.append(ids.length() == 0 ? "" : ", ").append(each.getId());
            }
            throw new CommandException(Messages.SELECTION_PROMPT_INVALID_CHOICE, ids.toString());
        }
        ChatPrompts.begin(player, new NPCCommandSelector(callback, sender, possible)).withEscapeSequences("exit");
    }
}
