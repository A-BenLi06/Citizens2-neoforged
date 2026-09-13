package net.yuuniverse.interactions;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Reads every {@code conversations/*.yml} the old plugin wrote and indexes them by the NPC they belong to.
 * <p>
 * SnakeYAML is used directly rather than through Citizens' {@code YamlStorage} because these files are not Citizens
 * data: they are somebody else's format, with lists of strings where Citizens would write numbered keys, and the whole
 * value of this loader is reading them exactly as they are.
 */
public final class ConversationLibrary {
    private static final Logger LOGGER = LoggerFactory.getLogger("interactions");
    /** {@code - NPC with id 57} - the only form the migrated files use, but the number is what matters. */
    private static final Pattern STARTS_WITH_ID = Pattern.compile("NPC\\s+with\\s+id\\s+(\\d+)",
            Pattern.CASE_INSENSITIVE);
    /** The other form 15 of the migrated files use: {@code - NPC named 伍德}. */
    private static final Pattern STARTS_WITH_NAME = Pattern.compile("NPC\\s+named\\s+(.+)",
            Pattern.CASE_INSENSITIVE);

    private final Map<Integer, Conversation> byNpcId = new HashMap<>();
    private final Map<String, Conversation> byNpcName = new HashMap<>();
    private final List<Conversation> all = new ArrayList<>();
    private int skipped;

    public void load(File folder) {
        byNpcId.clear();
        byNpcName.clear();
        all.clear();
        skipped = 0;
        File[] files = folder.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".yml"));
        if (files == null) {
            LOGGER.warn("No conversations folder at {} - no NPC will start a dialogue.", folder);
            return;
        }
        for (File file : files) {
            try {
                Conversation conversation = parse(file);
                if (conversation == null) {
                    skipped++;
                    continue;
                }
                all.add(conversation);
                for (String name : conversation.npcNames) {
                    byNpcName.put(name.toLowerCase(java.util.Locale.ROOT), conversation);
                }
                for (int id : conversation.npcIds) {
                    Conversation clash = byNpcId.put(id, conversation);
                    if (clash != null) {
                        LOGGER.warn("NPC {} is claimed by both {} and {}; the later file wins.", id, clash.source,
                                conversation.source);
                    }
                }
            } catch (Exception ex) {
                skipped++;
                LOGGER.error("Could not read conversation {}: {}", file.getName(), ex.toString());
            }
        }
        LOGGER.info("Loaded {} conversation(s) covering {} NPC id(s) and {} name(s){}.", all.size(), byNpcId.size(),
                byNpcName.size(),
                skipped == 0 ? "" : ", skipped " + skipped);
    }

    public Conversation forNpc(int id) {
        return byNpcId.get(id);
    }

    /** @return the conversation triggered by an NPC's name, for the {@code NPC named …} form */
    public Conversation forNpcName(String name) {
        return name == null ? null : byNpcName.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    public int size() {
        return all.size();
    }

    public int npcCount() {
        return byNpcId.size();
    }

    public int skipped() {
        return skipped;
    }

    @SuppressWarnings("unchecked")
    private Conversation parse(File file) throws Exception {
        Map<String, Object> root;
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(32 * 1024 * 1024);
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            Object loaded = new Yaml(options).load(reader);
            if (!(loaded instanceof Map))
                return null;
            root = (Map<String, Object>) loaded;
        }
        Conversation conversation = new Conversation();
        conversation.source = file.getName();
        conversation.name = str(root.get("name"), "");
        conversation.blockMovement = bool(root.get("block_movement"), false);
        conversation.slowEffect = bool(root.get("slow_effect"), false);
        conversation.saveProgress = bool(root.get("save_conversation_progress"), false);
        conversation.requiresPermission = bool(root.get("requires_permission"), false);
        conversation.startRadius = dbl(root.get("start_conversation_radius"), 0);
        conversation.endRadius = dbl(root.get("end_conversation_radius"), 5);
        conversation.cooldownSeconds = (int) dbl(root.get("cooldown"), 0);
        conversation.canBeStartedOnAir = bool(root.get("can_be_started_on_air"), false);
        conversation.hologram = HologramSettings.read(root.get("hologram_dialogues"));

        for (Object entry : list(root.get("starts_with"))) {
            Matcher matcher = STARTS_WITH_ID.matcher(String.valueOf(entry));
            if (matcher.find()) {
                conversation.npcIds.add(Integer.parseInt(matcher.group(1)));
            } else {
                Matcher named = STARTS_WITH_NAME.matcher(String.valueOf(entry));
                if (named.find()) {
                    conversation.npcNames.add(named.group(1).trim());
                } else {
                    LOGGER.warn("{}: cannot tell which NPC \"{}\" means; that trigger is ignored.", file.getName(),
                            entry);
                }
            }
        }

        Object nodes = root.get("conversation");
        if (nodes instanceof Map) {
            for (Map.Entry<String, Object> node : ((Map<String, Object>) nodes).entrySet()) {
                if (node.getValue() instanceof Map) {
                    conversation.nodes.put(node.getKey(),
                            parseNode(node.getKey(), (Map<String, Object>) node.getValue()));
                }
            }
        }
        return conversation.nodes.isEmpty() || (conversation.npcIds.isEmpty() && conversation.npcNames.isEmpty())
                ? null
                : conversation;
    }

    @SuppressWarnings("unchecked")
    private Conversation.Node parseNode(String key, Map<String, Object> raw) {
        Conversation.Node node = new Conversation.Node(key);
        node.randomDialogue = bool(raw.get("random_dialogue"), false);
        Object dialogue = raw.get("dialogue");
        if (dialogue instanceof Map) {
            for (Map.Entry<String, Object> line : sortedByKey((Map<String, Object>) dialogue).entrySet()) {
                if (line.getValue() instanceof Map) {
                    Conversation.Line parsed = parseLine((Map<String, Object>) line.getValue());
                    parsed.key = line.getKey();
                    node.lines.add(parsed);
                }
            }
        }
        // options and options1/options2 both appear in the migrated files
        for (String optionsKey : new String[] { "options", "options1", "options2" }) {
            Object opts = raw.get(optionsKey);
            if (!(opts instanceof Map))
                continue;
            for (Object value : sortedByKey((Map<String, Object>) opts).values()) {
                if (!(value instanceof Map))
                    continue;
                Map<String, Object> map = (Map<String, Object>) value;
                Conversation.Option option = new Conversation.Option();
                option.text = str(map.get("text"), "");
                option.startConversation = str(map.get("start_conversation"), null);
                for (Object require : list(map.get("requires"))) {
                    option.requires.add(String.valueOf(require));
                }
                for (Object action : list(map.get("actions"))) {
                    option.actions.add(String.valueOf(action));
                }
                node.options.add(option);
            }
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    private Conversation.Line parseLine(Map<String, Object> raw) {
        Conversation.Line line = new Conversation.Line();
        Object text = raw.get("text");
        if (text instanceof List) {
            for (Object each : (List<Object>) text) {
                line.text.add(String.valueOf(each));
            }
        } else if (text != null) {
            line.text.add(String.valueOf(text));
        }
        line.time = dbl(raw.get("time"), 2);
        line.showName = bool(raw.get("show_name"), true);
        line.startConversation = str(raw.get("start_conversation"), null);
        line.startOptions = str(raw.get("start_options"), null);
        line.saveToPlayer = bool(raw.get("save_dialogue_to_player"), false);
        for (Object action : list(raw.get("actions"))) {
            line.actions.add(String.valueOf(action));
        }
        for (Object action : list(raw.get("last_actions"))) {
            line.lastActions.add(String.valueOf(action));
        }
        for (Object require : list(raw.get("requires"))) {
            line.requires.add(String.valueOf(require));
        }
        Object conditional = raw.get("conditional_dialogue");
        if (conditional instanceof Map) {
            for (Object value : sortedByKey((Map<String, Object>) conditional).values()) {
                if (value instanceof Map) {
                    Map<String, Object> fields = (Map<String, Object>) value;
                    Conversation.Conditional redirect = new Conversation.Conditional();
                    redirect.startConversation = str(fields.get("start_conversation"), null);
                    for (Object require : list(fields.get("requires"))) redirect.requires.add(String.valueOf(require));
                    line.conditional.add(redirect);
                }
            }
        }
        return line;
    }

    /**
     * {@code dialogue1, dialogue2, … dialogue10} must play in that order, and a plain string sort puts 10 before 2, so
     * the trailing number is compared numerically.
     */
    private static Map<String, Object> sortedByKey(Map<String, Object> raw) {
        List<String> keys = new ArrayList<>(raw.keySet());
        keys.sort((a, b) -> {
            int na = trailingNumber(a);
            int nb = trailingNumber(b);
            return na != nb ? Integer.compare(na, nb) : a.compareTo(b);
        });
        Map<String, Object> sorted = new LinkedHashMap<>();
        for (String key : keys) {
            sorted.put(key, raw.get(key));
        }
        return sorted;
    }

    private static int trailingNumber(String key) {
        int i = key.length();
        while (i > 0 && Character.isDigit(key.charAt(i - 1))) {
            i--;
        }
        return i == key.length() ? Integer.MAX_VALUE : Integer.parseInt(key.substring(i));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        if (value instanceof List)
            return (List<Object>) value;
        return value == null ? List.of() : List.of(value);
    }

    private static String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean b)
            return b;
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private static double dbl(Object value, double fallback) {
        if (value instanceof Number n)
            return n.doubleValue();
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
