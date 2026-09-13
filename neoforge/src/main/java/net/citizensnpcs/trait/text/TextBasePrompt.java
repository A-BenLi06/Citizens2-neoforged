package net.citizensnpcs.trait.text;

import java.time.DateTimeException;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.citizensnpcs.api.command.CommandMessages;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Owner;
import net.citizensnpcs.api.util.ChatPrompt;
import net.citizensnpcs.api.util.ChatPromptSession;
import net.citizensnpcs.api.util.Durations;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.util.Messages;
import net.minecraft.commands.CommandSourceStack;

/** The original text-editor operations, with validation shared by chat and direct command input. */
public final class TextBasePrompt implements ChatPrompt {
    private static final Pattern INPUT = Pattern.compile("^\\s*(\\S+)(?:\\s(.*))?$", Pattern.DOTALL);
    private static final Pattern BUBBLE_DURATION = Pattern.compile("^\\s*speech\\s+bubbles\\s+duration(?:\\s+(.*))?$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private final Text text;

    public TextBasePrompt(Text text) { this.text = text; }

    @Override
    public ChatPrompt acceptInput(ChatPromptSession session, String input) {
        try { applyInput(session, input); }
        catch (CommandException invalid) { Messaging.sendError(session.getPlayer().createCommandSourceStack(), invalid.getMessage()); }
        return session.isEnded() ? null : this;
    }

    public void applyInput(ChatPromptSession session, String original) throws CommandException {
        checkTarget(session);
        CommandSourceStack sender = session.getPlayer().createCommandSourceStack();
        String control = original.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (control.equals("send text to chat")) {
            text.toggleSendTextToChat(); return;
        } else if (control.equals("realistic looking")) {
            text.toggleRealisticLooking(); return;
        } else if (control.equals("speech bubbles")) {
            text.toggleSpeechBubbles(); return;
        } else if (control.equals("talk close")) {
            text.toggleTalkClose(); return;
        }
        Matcher duration = BUBBLE_DURATION.matcher(original);
        if (duration.matches()) {
            Duration parsed = parseBubbleDuration(duration.group(1));
            text.setSpeechBubbleDuration(parsed);
            Messaging.sendTr(sender, Messages.SPEECH_BUBBLES_DURATION_SET, parsed);
            return;
        }
        Parts input = parts(original);
        switch (input.word.toLowerCase(Locale.ROOT)) {
            case "add" -> {
                text.add(input.tail);
                Messaging.sendTr(sender, "citizens.editors.text.added-entry", input.tail);
            }
            case "edit" -> {
                Parts entry = parts(input.tail);
                int index = index(entry.word);
                text.edit(index, entry.tail);
                Messaging.sendTr(sender, "citizens.editors.text.updated-entry", index);
            }
            case "remove" -> {
                int index = index(input.tail.trim());
                text.remove(index);
                Messaging.sendTr(sender, "citizens.editors.text.removed-entry", index);
            }
            case "page" -> {
                int page;
                try { page = Integer.parseInt(input.tail.trim()); }
                catch (NumberFormatException invalid) { throw new CommandException(Messages.TEXT_EDITOR_INVALID_PAGE); }
                if (!text.hasPage(page)) throw new CommandException(Messages.TEXT_EDITOR_INVALID_PAGE);
                session.setSessionData("page", page);
            }
            case "delay" -> {
                int delay;
                try { delay = Integer.parseInt(input.tail.trim()); }
                catch (NumberFormatException invalid) { throw new CommandException(Messages.TEXT_EDITOR_INVALID_DELAY); }
                if (delay < -1) throw new CommandException(Messages.TEXT_EDITOR_INVALID_DELAY);
                text.setDelay(delay);
                Messaging.sendTr(sender, Messages.TEXT_EDITOR_DELAY_SET, delay);
            }
            case "random" -> { requireNoTail(input); text.toggleRandomTalker(); }
            case "close" -> { requireNoTail(input); text.toggleTalkClose(); }
            case "range" -> {
                double range;
                try { range = Double.parseDouble(input.tail.trim()); }
                catch (NumberFormatException invalid) { throw new CommandException(Messages.TEXT_EDITOR_INVALID_RANGE); }
                if (!Double.isFinite(range)) throw new CommandException(Messages.TEXT_EDITOR_INVALID_RANGE);
                range = Math.max(0, range);
                text.setRange(range);
                Messaging.sendTr(sender, Messages.TEXT_EDITOR_RANGE_SET, range);
            }
            case "item" -> {
                String pattern = input.tail.trim();
                if (pattern.isEmpty()) throw new CommandException(Messages.TEXT_EDITOR_MISSING_ITEM_PATTERN);
                if (pattern.equalsIgnoreCase("default")) pattern = "default";
                text.setItemInHandPattern(pattern);
                Messaging.sendTr(sender, Messages.TEXT_EDITOR_SET_ITEM, pattern);
            }
            default -> throw new CommandException(Messages.TEXT_EDITOR_INVALID_EDIT_TYPE);
        }
    }

    private void checkTarget(ChatPromptSession session) throws CommandException {
        NPC npc = text.getNPC();
        if (npc == null || npc.getOwningRegistry().getByUniqueId(npc.getUniqueId()) != npc
                || npc.getTraitNullable(Text.class) != text) {
            session.end();
            throw new CommandException("citizens.editors.text.target-unavailable");
        }
        if (!PermissionUtil.hasPermission(session.getPlayer(), TextEditor.PERMISSION)) {
            session.end();
            throw new CommandException(CommandMessages.NO_PERMISSION);
        }
        CommandSourceStack sender = session.getPlayer().createCommandSourceStack();
        if (!PermissionUtil.hasPermission(sender, "citizens.admin") && !npc.getOrAddTrait(Owner.class).isOwnedBy(sender)) {
            session.end();
            throw new CommandException(CommandMessages.MUST_BE_OWNER);
        }
    }

    @Override
    public String getPromptText(ChatPromptSession session) {
        try { checkTarget(session); }
        catch (CommandException invalid) {
            Messaging.sendError(session.getPlayer().createCommandSourceStack(), invalid.getMessage());
            return "";
        }
        CommandSourceStack sender = session.getPlayer().createCommandSourceStack();
        Messaging.sendTr(sender, Messages.TEXT_EDITOR_START_PROMPT, color(text.shouldTalkClose()), color(text.isRandomTalker()),
                color(text.useSpeechBubbles()), color(text.useRealisticLooking()), color(text.sendTextToChat()));
        int page = (int) session.getSessionData("page", 1);
        if (!text.hasPage(page)) { page = 1; session.setSessionData("page", page); }
        text.sendPage(sender, page);
        return "";
    }

    static Duration parseBubbleDuration(String input) throws CommandException {
        if (input == null || input.isBlank()) throw new CommandException(Messages.INVALID_SPEECH_BUBBLES_DURATION);
        try {
            Duration duration = Durations.parse(input.trim(), null);
            if (duration.isNegative() || duration.toMillis() / 50 > Integer.MAX_VALUE)
                throw new CommandException(Messages.INVALID_SPEECH_BUBBLES_DURATION);
            return duration;
        } catch (DateTimeException | NumberFormatException | ArithmeticException invalid) {
            throw new CommandException(Messages.INVALID_SPEECH_BUBBLES_DURATION);
        }
    }

    private int index(String value) throws CommandException {
        try {
            int index = Integer.parseInt(value);
            if (text.hasIndex(index)) return index;
        } catch (NumberFormatException invalid) { }
        throw new CommandException(Messages.TEXT_EDITOR_INVALID_INDEX, value);
    }

    private static void requireNoTail(Parts input) throws CommandException {
        if (!input.tail.isBlank()) throw new CommandException(Messages.TEXT_EDITOR_INVALID_EDIT_TYPE);
    }

    static Parts parts(String input) {
        Matcher matcher = INPUT.matcher(input);
        return matcher.matches() ? new Parts(matcher.group(1), matcher.group(2) == null ? "" : matcher.group(2)) : new Parts("", "");
    }
    static record Parts(String word, String tail) { }
    private static String color(boolean enabled) { return enabled ? "<green>" : "<red>"; }
}
