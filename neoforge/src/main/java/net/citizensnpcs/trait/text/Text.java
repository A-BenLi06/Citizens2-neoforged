package net.citizensnpcs.trait.text;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Pattern;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.ai.speech.SpeechContext;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitEventHandler;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Durations;
import net.citizensnpcs.api.util.EntityUtil;
import net.citizensnpcs.api.util.Paginator;
import net.citizensnpcs.api.util.Placeholders;
import net.citizensnpcs.trait.HologramTrait;
import net.citizensnpcs.util.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * The lines an NPC says — on right-click, or to players who walk close by.
 * <p>
 * The chat formatting itself lives in {@code CitizensNPC.speak}, driven by the {@code npc.chat.format.*} settings, so
 * what a player sees is byte-for-byte what upstream produces for the same config.
 * <p>
 * Two pieces are not here yet and are noted rather than silently dropped:
 * <ul>
 * <li>{@code getEditor()} — the in-game {@code /npc text} editor is built on Bukkit's Conversations API, which has no
 * Minecraft equivalent and needs rewriting on top of chat input capture. Every other way of editing text (the command
 * with explicit arguments) works.</li>
 * <li>{@code <item:…>} lines inside speech bubbles show as literal text, since {@link HologramTrait} does not render the
 * inline item syntax yet.</li>
 * </ul>
 */
@TraitName("text")
public class Text extends Trait {
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private int currentIndex;
    @Persist
    private int delay = -1;
    @Persist("talkitem")
    private String itemInHandPattern = "default";
    @Persist("random-talker")
    private boolean randomTalker = Setting.DEFAULT_RANDOM_TALKER.asBoolean();
    private double range = Setting.DEFAULT_TALK_CLOSE_RANGE.asDouble();
    @Persist("realistic-looking")
    private boolean realisticLooker = Setting.DEFAULT_REALISTIC_LOOKING.asBoolean();
    @Persist("send-text-to-chat")
    private boolean sendTextToChat = true;
    @Persist("speech-bubble-duration")
    private int speechBubbleDuration = Setting.DEFAULT_TEXT_SPEECH_BUBBLE_DURATION.asTicks();
    @Persist("speech-bubbles")
    private boolean speechBubbles;
    @Persist("talk-close")
    private boolean talkClose = Setting.DEFAULT_TALK_CLOSE.asBoolean();
    @Persist
    private final List<String> text = new ArrayList<>();

    public Text() {
        super("text");
    }

    /**
     * Adds a piece of text that will be said by the NPC.
     */
    public void add(String string) {
        text.add(string);
    }

    /**
     * Edit the text at a given index to a new text.
     */
    public void edit(int index, String newText) {
        text.set(index, newText);
    }

    String getPageText(int page) {
        Paginator paginator = new Paginator().header("Current Texts");
        for (int i = 0; i < text.size(); i++) {
            paginator.addLine("<green>" + i + " <gray>- <yellow>" + text.get(i));
        }
        return paginator.getPageText(page);
    }

    /**
     * @return The list of all texts
     */
    public List<String> getTexts() {
        return text;
    }

    /**
     * @return whether there is text at a certain index
     */
    public boolean hasIndex(int index) {
        return index >= 0 && text.size() > index;
    }

    public boolean hasPage(int page) {
        return new Paginator(text.size()).hasPage(page);
    }

    public boolean isRandomTalker() {
        return randomTalker;
    }

    public double getRange() {
        return range;
    }

    @Override
    public void load(DataKey key) {
        range = key.getDouble("range", Setting.DEFAULT_TALK_CLOSE_RANGE.asDouble());
    }

    @TraitEventHandler
    private void onRightClick(NPCRightClickEvent event) {
        if (text.isEmpty())
            return;
        String localPattern = "default".equals(itemInHandPattern) ? Setting.TALK_ITEM.asString() : itemInHandPattern;
        if (Util.matchesItemInHand(event.getClicker(), localPattern) && !shouldTalkClose()) {
            talk(event.getClicker());
            event.setDelayedCancellation(true);
        }
    }

    @Override
    public void onSpawn() {
        if (text.isEmpty()) {
            text.addAll(Setting.DEFAULT_TEXT.asList());
        }
    }

    /**
     * Remove text at a given index.
     */
    public void remove(int index) {
        text.remove(index);
    }

    @Override
    public void run() {
        if (!npc.isSpawned() || !talkClose || text.isEmpty())
            return;
        for (ServerPlayer player : EntityUtil.getNearbyVisiblePlayers(npc.getEntity(), range)) {
            talk(player);
        }
    }

    @Override
    public void save(DataKey key) {
        key.removeKey("range");
        if (range != Setting.DEFAULT_TALK_CLOSE_RANGE.asDouble()) {
            key.setDouble("range", range);
        }
    }

    public boolean sendPage(CommandSourceStack sender, int page) {
        Paginator paginator = new Paginator().header("Current Texts").enablePageSwitcher("/npc text page $page");
        for (int i = 0; i < text.size(); i++) {
            paginator.addLine(text.get(i) + " <green>(<click:suggest_command:edit " + i
                    + " ><yellow>edit</click>) (<hover:show_text:Remove this text><click:run_command:/npc text remove "
                    + i + "><red>-</click></hover>)");
        }
        return paginator.sendPage(sender, page);
    }

    private boolean sendText(ServerPlayer player) {
        if (text.isEmpty())
            return false;

        int index;
        if (randomTalker) {
            index = RANDOM.nextInt(text.size());
        } else {
            if (currentIndex > text.size() - 1) {
                currentIndex = 0;
            }
            index = currentIndex++;
        }
        if (speechBubbles) {
            HologramTrait trait = npc.getOrAddTrait(HologramTrait.class);
            String replaced = Placeholders.replace(text.get(index), player.createCommandSourceStack(), npc);
            for (String line : NEWLINE_PATTERN.split(replaced)) {
                trait.addTemporaryLine(line, speechBubbleDuration);
            }
        }
        if (sendTextToChat) {
            SpeechContext context = new SpeechContext(text.get(index), player);
            context.setTalker(npc.getEntity());
            npc.speak(context);
        }
        return true;
    }

    public boolean sendTextToChat() {
        return sendTextToChat;
    }

    /**
     * Set the text delay between messages, in ticks.
     */
    public void setDelay(int delay) {
        this.delay = delay;
    }

    /**
     * Sets the item in hand pattern required to talk to NPCs, if enabled.
     */
    public void setItemInHandPattern(String pattern) {
        itemInHandPattern = pattern;
    }

    /**
     * Set the range in blocks before text will be sent.
     */
    public void setRange(double range) {
        this.range = range;
    }

    public void setSpeechBubbleDuration(Duration duration) {
        speechBubbleDuration = Durations.toTicks(duration);
    }

    public int getSpeechBubbleDuration() {
        return speechBubbleDuration;
    }

    /**
     * @return Whether talking close is enabled.
     */
    public boolean shouldTalkClose() {
        return talkClose;
    }

    private void talk(ServerPlayer player) {
        Long cooldown = cooldowns.get(player.getUUID());
        if (cooldown != null) {
            if (System.currentTimeMillis() < cooldown)
                return;
            cooldowns.remove(player.getUUID());
        }
        sendText(player);

        int min = Setting.DEFAULT_TEXT_DELAY_MIN.asTicks();
        int max = Setting.DEFAULT_TEXT_DELAY_MAX.asTicks();
        int delay = this.delay;
        if (delay == -1) {
            // guard the bound: a config where max <= min would make nextInt throw
            delay = max > min ? min + Util.getFastRandom().nextInt(max - min) : min;
        }
        if (delay <= 0)
            return;
        cooldowns.put(player.getUUID(), System.currentTimeMillis() + delay * 50L);
    }

    /**
     * Toggles talking at random intervals.
     */
    public boolean toggleRandomTalker() {
        return randomTalker = !randomTalker;
    }

    /**
     * Toggles requiring line of sight before talking.
     */
    public boolean toggleRealisticLooking() {
        return realisticLooker = !realisticLooker;
    }

    /**
     * Toggles sending text through chat
     */
    public boolean toggleSendTextToChat() {
        return sendTextToChat = !sendTextToChat;
    }

    /**
     * Toggles using speech bubbles instead of messages.
     */
    public boolean toggleSpeechBubbles() {
        return speechBubbles = !speechBubbles;
    }

    /**
     * Toggles talking to nearby Players.
     */
    public boolean toggleTalkClose() {
        return talkClose = !talkClose;
    }

    public boolean useRealisticLooking() {
        return realisticLooker;
    }

    public boolean useSpeechBubbles() {
        return speechBubbles;
    }

    private static final Pattern NEWLINE_PATTERN = Pattern.compile("<br>|\n");
    private static final Random RANDOM = Util.getFastRandom();
}
