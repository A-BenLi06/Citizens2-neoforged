package net.yuuniverse.interactions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One conversation file, in the shape the Bukkit Interactions plugin wrote it.
 * <p>
 * The field names below are that plugin's, deliberately: the point is that an existing server's 146 conversation files
 * are read unedited, so nothing has to be converted and nothing can be lost in a conversion. Anything the file does not
 * set keeps the default the old plugin used.
 */
public class Conversation {
    /** Conversation nodes keyed by their name ({@code conversation1}, …). Insertion order is the file's order. */
    public final Map<String, Node> nodes = new LinkedHashMap<>();
    public String name = "";
    /** NPC ids this conversation starts on, parsed out of {@code starts_with: - NPC with id 57}. */
    public final List<Integer> npcIds = new ArrayList<>();
    /**
     * NPC names it starts on, from the {@code - NPC named 伍德} form. 15 of the 146 migrated files use this instead of an
     * id, so both have to work; a name is matched when the NPC is clicked rather than resolved at load, because a name
     * can be changed and several NPCs may share one.
     */
    public final List<String> npcNames = new ArrayList<>();
    public boolean blockMovement;
    public boolean slowEffect;
    public boolean saveProgress;
    public boolean requiresPermission;
    /** Seconds. 0 means "only when clicked", which is how every migrated file is written. */
    public double startRadius;
    public double endRadius = 5;
    public int cooldownSeconds;
    public boolean canBeStartedOnAir;
    /** The file this came from, for diagnostics. */
    public String source = "";

    public Node first() {
        return nodes.isEmpty() ? null : nodes.values().iterator().next();
    }

    public Node node(String key) {
        return key == null ? null : nodes.get(key);
    }

    /** One {@code conversationN}: a run of lines, then optionally a set of choices. */
    public static class Node {
        public final String key;
        public final List<Line> lines = new ArrayList<>();
        public final List<Option> options = new ArrayList<>();
        /** {@code random_dialogue: true} plays one line of the run rather than all of them in order. */
        public boolean randomDialogue;

        public Node(String key) {
            this.key = key;
        }
    }

    /** One {@code dialogueN}: the text shown, how long it stays, and what happens around it. */
    public static class Line {
        /** The file's own key for this line ({@code dialogue1}), which progress entries are spelled with. */
        public String key = "";
        public final List<String> text = new ArrayList<>();
        /** Seconds the line is shown before the next one. */
        public double time = 2;
        public boolean showName = true;
        /** Run when the line is shown. */
        public final List<String> actions = new ArrayList<>();
        /** Run when the line finishes - the old plugin's {@code last_actions}. */
        public final List<String> lastActions = new ArrayList<>();
        /** Remembered per player, so {@code %interactions_has_dialogue_…%} and progress work. */
        public boolean saveToPlayer;
        /** All must hold for the line to play; an empty list always plays. */
        public final List<String> requires = new ArrayList<>();
        /** {@code conditional_dialogue}: alternatives tried in order, first one whose requires hold wins. */
        public final List<Line> conditional = new ArrayList<>();

        public List<String> textOrEmpty() {
            return text.isEmpty() ? Collections.emptyList() : text;
        }
    }

    /** One {@code optionN}: a clickable choice that jumps to another node. */
    public static class Option {
        public String text = "";
        public String startConversation;
        public final List<String> requires = new ArrayList<>();
        public final List<String> actions = new ArrayList<>();
    }
}
