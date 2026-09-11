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
    private final ServerPlayer player;
    private final Entity npc;
    private final Engine engine;

    private Conversation.Node node;
    private List<Conversation.Line> lineOrder;
    private int lineIndex;
    private int ticksOnLine;
    private Conversation.Line current;
    private Conversation.Line completedLine;
    private boolean awaitingChoice;
    private List<Conversation.Option> offered = new ArrayList<>();
    private boolean finished;
    private Conversation.Option pendingChoice;
    private boolean skipRequested;

    public Session(Engine engine, Conversation conversation, Conversation.Node node, ServerPlayer player, Entity npc) {
        this.engine = engine;
        this.conversation = conversation;
        this.node = node;
        this.player = player;
        this.npc = npc;
        lineOrder = node.orderedLines(player.getRandom());
        DialogueMovement.begin(this);
        DialogueCommands.begin(this);
        DialogueInventory.begin(this);
    }

    public boolean isFinished() {
        return finished;
    }

    boolean permitsCommand(String command) {
        return engine.settings().permitsCommand(command);
    }

    boolean permitsInventoryInteract() { return engine.settings().allowInventoryInteract(); }

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
        if (finished)
            return;
        if (npc != null && (npc.isRemoved() || npc.level() != player.level()
                || conversation.isOutsideEndRadius(npc.distanceToSqr(player)))) {
            // walked away: the old plugin ends the conversation rather than talking to nobody
            end(false);
            return;
        }
        if (conversation.slowEffect) {
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 2, false, false, false));
        }
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
        if (skipRequested || (current.time != -1 && ++ticksOnLine >= Math.max(1, (int) Math.round(current.time * 20)))) {
            skipRequested = false;
            if (!engine.actions().runAll(current.lastActions, player, npcName())) {
                end(false);
                return;
            }
            completedLine = current;
            current = null;
            ticksOnLine = 0;
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
        for (String raw : line.textOrEmpty()) {
            String text = Text.placeholders(raw, player);
            MutableComponent message = Component.empty();
            if (line.showName && !conversation.name.isEmpty()) {
                message.append(Text.legacy(conversation.name)).append(Component.literal(": ")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
            }
            int nextMarker = text.indexOf("%next%");
            if (nextMarker >= 0) {
                message.append(Text.legacy(text.substring(0, nextMarker)));
                message.append(engine.messages().nextLabel().copy().withStyle(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/interactions skipdialogue"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, engine.messages().nextTooltip()))));
            } else {
                message.append(Text.legacy(text));
            }
            player.sendSystemMessage(message);
        }
        if (!engine.actions().runAll(line.actions, player, npcName())) {
            end(false);
            return;
        }
        if (line.saveToPlayer) {
            engine.progress().markSeen(player.getUUID(), player.getGameProfile().getName(),
                    progressKey(node.key, line.key));
        }
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
        offered = new ArrayList<>();
        for (Conversation.Option option : options) {
            if (Conditions.all(option.requires, player, engine.progress())) {
                offered.add(option);
            }
        }
        if (offered.isEmpty()) {
            end(true);
            return;
        }
        awaitingChoice = true;
        player.sendSystemMessage(Component.empty());
        for (int i = 0; i < offered.size(); i++) {
            Conversation.Option option = offered.get(i);
            final int number = i + 1;
            String command = "/interactions choose " + number;
            player.sendSystemMessage(Component.literal(" [" + (i + 1) + "] ")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD))
                    .append(Text.legacy(Text.placeholders(option.text, player)))
                    .withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("点击选择,或直接在聊天里输入 " + number)))
                            .withUnderlined(true)));
        }
        player.sendSystemMessage(Component.literal(" (点击上面的选项,或在聊天里输入编号)")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)));
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
            String plain = Text.plain(Text.legacy(offered.get(i).text)).toLowerCase(java.util.Locale.ROOT);
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
        if (!awaitingChoice || oneBased < 1 || oneBased > offered.size())
            return false;
        Conversation.Option option = offered.get(oneBased - 1);
        if (!Conditions.all(option.requires, player, engine.progress()))
            return false;
        awaitingChoice = false;
        // Execute from tick, outside the /interactions choose command's execution queue.
        pendingChoice = option;
        return true;
    }

    public void end(boolean completed) {
        if (finished)
            return;
        finished = true;
        DialogueMovement.end(this);
        DialogueCommands.end(this);
        DialogueInventory.end(this);
        skipRequested = false;
        pendingChoice = null;
        awaitingChoice = false;
        if (conversation.slowEffect) {
            player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        }
        if (completed && conversation.saveProgress) {
            engine.progress().markSeen(player.getUUID(), player.getGameProfile().getName(),
                    progressKey(node.key, "completed"));
        }
    }

    private Component npcName() {
        return Text.legacy(conversation.name);
    }

    /** What a session needs from the mod, kept as an interface so the session is testable on its own. */
    public interface Engine {
        default DialogueSettings settings() { return DialogueSettings.DEFAULT; }
        default DialogueMessages messages() { return DialogueMessages.DEFAULT; }

        Actions actions();

        ProgressStore progress();
    }
}
