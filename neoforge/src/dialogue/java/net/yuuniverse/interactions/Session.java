package net.yuuniverse.interactions;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;

/**
 * One conversation in progress, for one player.
 * <p>
 * The pacing is the old plugin's: a line is shown, its {@code actions} run, it stays for {@code time} seconds, then its
 * {@code last_actions} run and the next line follows. When the lines run out the node's options are offered and the
 * session waits for a choice; a node with neither lines left nor options is finished.
 * <p>
 * Options are ordinary clickable chat components rather than the packet trickery the old plugin needed ProtocolLib for -
 * the same choice, reached the simple way, and it works in any client.
 */
public class Session {
    private final Conversation conversation;
    private ServerPlayer player;
    private final Entity npc;
    private final Engine engine;
    private final DialogueBossBar bossBar;
    private final DialogueHologram hologram;
    private final DialogueActionBar actionBar;
    private DialogueWriter writer;

    private Conversation.Node node;
    private List<Conversation.Line> lineOrder;
    private int lineIndex;
    private int ticksOnLine;
    private Conversation.Line current;
    private Conversation.Line completedLine;
    private boolean awaitingChoice;
    private List<Conversation.Option> offered = new ArrayList<>();
    private List<Conversation.Option> inlineOptions = List.of();
    private boolean inlineLine;
    private String choiceView;
    private static final java.util.regex.Pattern OPTION_MARKER = java.util.regex.Pattern.compile("%option_([^%]*)%");
    private boolean finished;
    private Conversation.Option pendingChoice;
    private boolean skipRequested;
    private int selectedOption;
    private long selectionDelay;

    public Session(Engine engine, Conversation conversation, Conversation.Node node, ServerPlayer player, Entity npc) {
        this.engine = engine;
        this.conversation = conversation;
        this.node = node;
        this.player = player;
        this.npc = npc;
        bossBar = new DialogueBossBar(player, engine.settings().bossBar());
        hologram = new DialogueHologram(player, npc);
        actionBar = new DialogueActionBar(player);
        lineOrder = node.orderedLines(player.getRandom());
        DialogueMovement.begin(this);
        DialogueCommands.begin(this);
        DialogueInventory.begin(this);
        DialogueSelection.begin(this);
    }

    public boolean isFinished() {
        return finished;
    }

    boolean permitsCommand(String command) {
        return engine.settings().permitsCommand(command);
    }

    boolean permitsInventoryInteract() { return engine.settings().allowInventoryInteract(); }

    boolean usesSelection(SelectionSettings.Mode mode) {
        return !finished && conversation.blockMovement && engine.settings().selection().enabled()
                && engine.settings().selection().mode() == mode;
    }

    private boolean selectable() {
        return !finished && awaitingChoice && selectionEnabled();
    }

    private boolean selectionEnabled() { return conversation.blockMovement && engine.settings().selection().enabled(); }

    boolean cycleSelection(int direction, long now) {
        if (!selectable() || direction == 0 || now < selectionDelay) return false;
        selectionDelay = now + SelectionSettings.REPEAT_DELAY_MILLIS;
        int next = selectedOption + Integer.signum(direction);
        if (engine.settings().selection().wrap()) next = Math.floorMod(next, offered.size());
        else next = Math.max(0, Math.min(offered.size() - 1, next));
        if (next == selectedOption) return false;
        selectedOption = next;
        // Legacy redraw clears the prior selection display; rendering must not execute dialogue actions again.
        for (int i = 0; i < 13; i++) player.sendSystemMessage(Component.empty());
        if (completedLine != null && !renderLine(completedLine)) return false;
        if (!inlineLine) renderOptions();
        return true;
    }

    boolean confirmSelection() { return selectable() && choose(selectedOption + 1); }

    /** Queues a single line completion; rewards run on tick, outside the command dispatch queue. */
    public boolean skipDialogue(boolean npcClick) {
        if (finished || awaitingChoice || current == null || skipRequested) return false;
        if (npcClick ? !engine.settings().skipDialogueOnNpcClick() : !current.canBeSkipped()) return false;
        skipRequested = true;
        return true;
    }

    public ServerPlayer player() {
        return player;
    }

    public Conversation conversation() {
        return conversation;
    }

    /** Called every tick while the session is running. */
    public void tick() {
        advanceSession();
        if (!finished) actionBar.tick(engine.settings().actionBar(), engine.messages(), conversation.name, awaitingChoice);
    }

    private void advanceSession() {
        if (finished)
            return;
        if (player.isRemoved()) {
            ServerPlayer replacement = player.getServer().getPlayerList().getPlayer(player.getUUID());
            if (replacement == null || replacement == player || replacement.isRemoved()) {
                end(false);
                return;
            }
            // Bukkit's Player wrapper follows respawn; NeoForge replaces ServerPlayer. Keep the same conversation
            // and clocks, then validate the new player before sending any display back to the connection.
            rebind(replacement);
        }
        if (npc != null && (npc.isRemoved() || npc.level() != player.level()
                || conversation.isOutsideEndRadius(npc.distanceToSqr(player)))) {
            // walked away: the old plugin ends the conversation rather than talking to nobody
            end(false);
            return;
        }
        if (conversation.slowEffect) {
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 2, false, false, false));
        }
        bossBar.refresh(engine.settings().bossBar(), engine.messages(), conversation.name);
        hologram.refreshViewer();
        if (pendingChoice != null) {
            Conversation.Option selected = pendingChoice;
            pendingChoice = null;
            Conversation.Node next = conversation.node(selected.startConversation);
            if (selected.startConversation != null && next == null) {
                org.slf4j.LoggerFactory.getLogger("interactions").error("{} / {} has an option referencing missing node {}",
                        conversation.source, node.key, selected.startConversation);
                end(false);
                return;
            }
            if (!Conditions.all(selected.requires, player, engine.progress())
                    || !engine.actions().runAll(selected.actions, player, npcName())) {
                end(false);
                return;
            }
            if (next == null) {
                end(true);
                return;
            }
            enterNode(next);
        }
        if (awaitingChoice)
            return;
        if (current == null) {
            if (!advance()) {
                finishNode();
            }
            return;
        }
        boolean complete = skipRequested || (current.time != -1 && ++ticksOnLine >= Math.max(1, (int) Math.round(current.time * 20)));
        bossBar.elapsed(ticksOnLine);
        if (complete) {
            skipRequested = false;
            writer = null;
            if (!engine.actions().runAll(current.lastActions, player, npcName())) {
                end(false);
                return;
            }
            completedLine = current;
            current = null;
            ticksOnLine = 0;
        } else if (writer != null) {
            List<Component> frame = writer.tick();
            if (frame != null) sendChat(current, frame, true);
        }
    }

    /** @return true when a line was shown, false when this node has no lines left */
    private boolean advance() {
        while (lineIndex < lineOrder.size()) {
            Conversation.Line line = lineOrder.get(lineIndex++);
            if (redirectConditional(line)) return true;
            if (!Conditions.all(line.requires, player, engine.progress())) {
                continue;
            }
            show(line);
            if (node.randomDialogue) {
                // one line of the run, not all of them
                lineIndex = lineOrder.size();
            }
            return true;
        }
        return false;
    }

    /** Redirect before text/actions; empty requirements are ignored by the legacy conditional scheduler. */
    private boolean redirectConditional(Conversation.Line line) {
        for (Conversation.Conditional redirect : line.conditional) {
            if (redirect.requires.isEmpty() || !Conditions.all(redirect.requires, player, engine.progress())) continue;
            Conversation.Node target = conversation.node(redirect.startConversation);
            if (target == null) {
                org.slf4j.LoggerFactory.getLogger("interactions").error("{} / {} / {} has a missing conditional target {}",
                        conversation.source, node.key, line.key, redirect.startConversation);
                end(false);
            } else {
                enterNode(target);
            }
            return true;
        }
        return false;
    }

    private void show(Conversation.Line line) {
        // The old scheduler uses routing only after the last sequential line (or its one random line).
        if ((node.randomDialogue || lineIndex == lineOrder.size()) && !validRoute(line)) {
            end(false);
            return;
        }
        List<String> wholeLine = new ArrayList<>(line.actions);
        wholeLine.addAll(line.lastActions);
        if (!engine.actions().validateAll(wholeLine, player, npcName())) {
            end(false);
            return;
        }
        current = line;
        ticksOnLine = 0;
        inlineLine = node.optionsInDialogue && (node.randomDialogue || lineIndex == lineOrder.size());
        inlineOptions = inlineLine ? eligible(optionsAfter(line)) : List.of();
        choiceView = inlineLine ? java.util.UUID.randomUUID().toString() : null;
        if (!renderLine(line, true)) return;
        bossBar.line(line.time);
        bossBar.refresh(engine.settings().bossBar(), engine.messages(), conversation.name);
        if (!engine.actions().runAll(line.actions, player, npcName())) {
            end(false);
            return;
        }
        if (line.saveToPlayer) {
            engine.progress().markSeen(player.getUUID(), player.getGameProfile().getName(),
                    progressKey(node.key, line.key));
        }
    }

    private boolean renderLine(Conversation.Line line) {
        return renderLine(line, false);
    }

    private boolean renderLine(Conversation.Line line, boolean animate) {
        List<Component> rendered = new ArrayList<>();
        List<Component> floating = new ArrayList<>();
        List<Component> controls = new ArrayList<>();
        try {
            for (String raw : line.textOrEmpty()) {
                if (!raw.startsWith("json:") && !visibleOptionRow(Text.placeholders(raw, player))) continue;
                rendered.add(renderText(raw, false, controls));
                if (conversation.hologram.enabled()) {
                    floating.add(renderText(raw.replace("{centered}", ""), true, new ArrayList<>()));
                }
            }
            hologram.show(floating, conversation.hologram);
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger("interactions").error("Could not render {} / {} / {}",
                    conversation.source, node.key, line.key, failure);
            end(false);
            return false;
        }
        if (engine.settings().useEmptySpaces()) player.sendSystemMessage(Component.empty());
        if (animate && engine.settings().writeDialogues().enabled() && !rendered.isEmpty()) {
            writer = new DialogueWriter(rendered, engine.settings().writeDialogues(), controls);
            List<Component> frame = writer.tick();
            if (frame != null) sendChat(line, frame, true);
        } else {
            sendChat(line, rendered, false);
        }
        return true;
    }

    private void sendChat(Conversation.Line line, List<Component> rendered, boolean clear) {
        // The original chat animation replaces its visible frame by sending nineteen empty chat lines.
        if (clear) for (int i = 0; i < 19; i++) player.sendSystemMessage(Component.empty());
        if (line.showName && !conversation.name.isEmpty()) {
            Component heading = engine.messages().speakerName(conversation.name);
            if (!heading.getString().isEmpty()) player.sendSystemMessage(heading);
        }
        rendered.forEach(player::sendSystemMessage);
    }

    private Component renderText(String raw, boolean floating, List<Component> controls) {
        if (floating) raw = raw.replace("%next%", "");
        if (raw.startsWith("json:")) return Text.json(raw.substring("json:".length()), player);
        String text = Text.placeholders(raw, player);
        return Text.legacy(text, marker -> {
            Component control;
            if (marker.equals("%next%")) {
                control = floating ? Component.empty() : engine.messages().nextLabel().copy().withStyle(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/interactions skipdialogue"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, engine.messages().nextTooltip())));
            } else if (OPTION_MARKER.matcher(marker).matches()) {
                int index = optionIndex(marker);
                control = index < inlineOptions.size() ? optionLabel(inlineOptions, index, !floating, choiceView) : Component.empty();
            } else return null;
            controls.add(control);
            return control;
        });
    }

    private boolean visibleOptionRow(String text) {
        var matcher = OPTION_MARKER.matcher(text);
        boolean marker = false, visible = false;
        while (matcher.find()) { marker = true; visible |= optionIndex(matcher.group()) < inlineOptions.size(); }
        return !marker || visible;
    }

    private static int optionIndex(String marker) {
        int number = Integer.parseInt(marker.substring("%option_".length(), marker.length() - 1));
        if (number < 1) throw new IllegalArgumentException("Inline option numbers must be positive: " + marker);
        return number - 1;
    }

    private List<Conversation.Option> eligible(List<Conversation.Option> options) {
        return options.stream().filter(option -> Conditions.all(option.requires, player, engine.progress())).toList();
    }

    private List<Conversation.Option> optionsAfter(Conversation.Line line) {
        if (line.startOptions != null) return conversation.node(line.startOptions).options;
        return line.startConversation == null ? node.options : List.of();
    }

    private String progressKey(String nodeKey, String lineKey) {
        String file = conversation.source.endsWith(".yml")
                ? conversation.source.substring(0, conversation.source.length() - 4)
                : conversation.source;
        return file + "." + nodeKey + "." + lineKey;
    }

    private void enterNode(Conversation.Node next) {
        node = next;
        lineOrder = node.orderedLines(player.getRandom());
        lineIndex = 0;
        current = null;
        writer = null;
        inlineLine = false;
        inlineOptions = List.of();
        choiceView = null;
        selectedOption = 0;
        completedLine = null;
        ticksOnLine = 0;
    }

    private boolean validRoute(Conversation.Line line) {
        String target = line.startOptions != null ? line.startOptions : line.startConversation;
        if (target == null || conversation.node(target) != null) return true;
        org.slf4j.LoggerFactory.getLogger("interactions").error("{} / {} / {} references missing dialogue node {}",
                conversation.source, node.key, line.key, target);
        return false;
    }

    private void finishNode() {
        if (completedLine != null) {
            if (!validRoute(completedLine)) {
                end(false);
                return;
            }
            if (completedLine.startOptions != null) {
                offerOptionsOrEnd(conversation.node(completedLine.startOptions).options);
                return;
            }
            if (completedLine.startConversation != null) {
                enterNode(conversation.node(completedLine.startConversation));
                return;
            }
        }
        offerOptionsOrEnd(node.options);
    }

    private void offerOptionsOrEnd(List<Conversation.Option> options) {
        offered = eligible(options);
        if (offered.isEmpty()) {
            end(true);
            return;
        }
        awaitingChoice = true;
        bossBar.options();
        bossBar.refresh(engine.settings().bossBar(), engine.messages(), conversation.name);
        selectedOption = 0;
        selectionDelay = 0;
        inlineLine = node.optionsInDialogue && completedLine != null;
        inlineOptions = inlineLine ? offered : List.of();
        choiceView = inlineLine ? java.util.UUID.randomUUID().toString() : null;
        if (inlineLine) {
            // Refresh after completion actions/requirements. Preview controls cannot select a different filtered option.
            for (int i = 0; i < 13; i++) player.sendSystemMessage(Component.empty());
            renderLine(completedLine);
        } else renderOptions();
    }

    private Component optionLabel(List<Conversation.Option> options, int index, boolean interactive, String view) {
        Conversation.Option option = options.get(index);
        int number = index + 1;
        MutableComponent message = (selectionEnabled()
                ? engine.messages().selectableLabel(number, option.text, index == selectedOption, player)
                : engine.messages().optionLabel(number, option.text, player)).copy();
        if (interactive && !selectionEnabled() && engine.settings().clickableOptions()) {
            String command = "/interactions choose " + number + (view == null ? "" : " " + view);
            message.withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, engine.messages().optionTooltip(number, player))));
        }
        return message;
    }

    private void renderOptions() {
        if (engine.settings().useEmptySpaces()) player.sendSystemMessage(Component.empty());
        List<Component> rendered = new ArrayList<>();
        for (int i = 0; i < offered.size(); i++) {
            rendered.add(optionLabel(offered, i, true, null));
        }
        List<String> layout = engine.messages().optionsMainFormat();
        if (layout != null) {
            for (String line : layout) {
                // Legacy layout lines containing the marker expand to the whole option list.
                if (line.contains("%options%")) rendered.forEach(player::sendSystemMessage);
                else player.sendSystemMessage(Text.legacy(Text.placeholders(line, player)));
            }
        } else {
            rendered.forEach(player::sendSystemMessage);
            player.sendSystemMessage((selectable()
                    ? Component.translatableWithFallback("interactions.options.prompt.selectable",
                            "Change the selected option, then sneak to choose.")
                    : engine.settings().clickableOptions()
                    ? Component.translatableWithFallback("interactions.options.prompt", "Click an option or enter its number in chat.")
                    : Component.translatableWithFallback("interactions.options.prompt.typed", "Enter an option number in chat."))
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)));
        }
    }

    /** @return true while the session is waiting for the player to pick an option */
    public boolean isAwaitingChoice() {
        return awaitingChoice;
    }

    public int offeredCount() {
        return offered.size();
    }

    /**
     * Picks an option from something the player typed.
     * <p>
     * The old plugin let a player select with movement and confirm by sneaking, which needed ProtocolLib. Typing is what
     * a player reaches for when that is gone, and their message would otherwise land in public chat as an unrelated
     * remark - so a bare number, or enough of the option's own words to identify it, counts as the choice.
     *
     * @return true when the text picked an option
     */
    public boolean chooseByText(String typed) {
        if (!awaitingChoice || typed == null)
            return false;
        String text = typed.trim();
        if (text.isEmpty())
            return false;
        try {
            return choose(Integer.parseInt(text));
        } catch (NumberFormatException ignored) {
            // not a number, so try to match the option text itself
        }
        String needle = text.toLowerCase(java.util.Locale.ROOT);
        int match = -1;
        for (int i = 0; i < offered.size(); i++) {
            String plain = Text.plain(Text.legacy(Text.placeholders(offered.get(i).text, player)))
                    .toLowerCase(java.util.Locale.ROOT);
            if (plain.contains(needle)) {
                if (match >= 0)
                    return false;
                match = i;
            }
        }
        return match >= 0 && choose(match + 1);
    }

    /** @return true when the choice was valid */
    public boolean choose(int oneBased) {
        return choose(oneBased, null);
    }

    /** A view token binds inline clicks to the displayed, filtered option list; typed choices need no token. */
    public boolean choose(int oneBased, String view) {
        if (finished || !awaitingChoice || oneBased < 1 || oneBased > offered.size()
                || view != null && !view.equals(choiceView))
            return false;
        Conversation.Option option = offered.get(oneBased - 1);
        if (!Conditions.all(option.requires, player, engine.progress()))
            return false;
        awaitingChoice = false;
        choiceView = null;
        // Execute from tick, outside the /interactions choose command's execution queue.
        pendingChoice = option;
        return true;
    }

    public void end(boolean completed) {
        if (finished)
            return;
        finished = true;
        if (player.isRemoved() && player.getServer() != null) {
            ServerPlayer replacement = player.getServer().getPlayerList().getPlayer(player.getUUID());
            if (replacement != null && replacement != player && !replacement.isRemoved()) rebind(replacement);
        }
        writer = null;
        try {
            actionBar.close();
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger("interactions").warn("Could not clear dialogue action bar", failure);
        }
        try {
            bossBar.close();
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger("interactions").warn("Could not remove dialogue boss bar", failure);
        }
        try {
            hologram.close();
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger("interactions").warn("Could not remove dialogue hologram", failure);
        }
        DialogueMovement.end(this);
        DialogueCommands.end(this);
        DialogueInventory.end(this);
        DialogueSelection.end(this);
        skipRequested = false;
        pendingChoice = null;
        awaitingChoice = false;
        inlineOptions = List.of();
        choiceView = null;
        if (conversation.slowEffect) {
            player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        }
        if (completed && conversation.saveProgress) {
            engine.progress().markSeen(player.getUUID(), player.getGameProfile().getName(),
                    progressKey(node.key, "completed"));
        }
        if (!completed && !node.interruptActions.isEmpty()) {
            // End ownership first: interrupt commands may reenter the controller or teleport the player.
            try {
                engine.actions().runAll(List.copyOf(node.interruptActions), player, npcName());
            } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger("interactions").error("Could not execute interrupt actions for {} / {}",
                        conversation.source, node.key, failure);
            }
        }
    }

    private Component npcName() {
        return Text.legacy(conversation.name);
    }

    private void rebind(ServerPlayer replacement) {
        player = replacement;
        bossBar.rebind(replacement);
        hologram.rebind(replacement);
        actionBar.rebind(replacement);
    }

    /** What a session needs from the mod, kept as an interface so the session is testable on its own. */
    public interface Engine {
        default DialogueSettings settings() { return DialogueSettings.DEFAULT; }
        default DialogueMessages messages() { return DialogueMessages.DEFAULT; }

        Actions actions();

        ProgressStore progress();
    }
}
