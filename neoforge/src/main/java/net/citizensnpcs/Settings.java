package net.citizensnpcs;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Durations;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.Storage;
import net.citizensnpcs.api.util.YamlStorage;

/**
 * Citizens' {@code config.yml}.
 * <p>
 * Each {@link Setting} carries its config path and default value, and reads itself out of the loaded file at startup. A
 * missing key is written back with its default, so the file self-populates and a user only ever has to edit what they
 * want to change — same contract as upstream.
 * <p>
 * Two differences from upstream, both from the platform rather than choice:
 * <ul>
 * <li>Storage is the port's {@link YamlStorage} / {@link DataKey} pair rather than Bukkit's {@code YamlConfiguration}.
 * SnakeYAML has no comment-writing API in the version bundled here, so the per-setting explanatory comments are dropped;
 * paths and defaults are unchanged, which is what matters for reading an existing file.</li>
 * <li>Upstream's reflective {@code SUPPORTS_SET_COMMENTS} probe goes away with them.</li>
 * </ul>
 * Only the settings the ported subsystems actually read are declared. Adding one is a single enum entry — declaring all
 * ~200 up front would mean writing defaults for features that do not exist yet, and a config full of keys that do
 * nothing is worse than a short one.
 */
public class Settings {
    private final File file;
    private final Storage storage;

    public Settings(File folder) {
        file = new File(folder, "config.yml");
        storage = new YamlStorage(file, "Citizens Configuration");
        load();
    }

    private void load() {
        storage.load();
        DataKey root = storage.getKey("");
        boolean dirty = false;
        for (Setting setting : Setting.values()) {
            if (setting.loadFrom(root)) {
                dirty = true;
            }
        }
        updateMessagingSettings();
        if (dirty) {
            storage.save();
        }
    }

    public void reload() {
        load();
    }

    public void save() {
        DataKey root = storage.getKey("");
        for (Setting setting : Setting.values()) {
            setting.writeTo(root);
        }
        storage.save();
    }

    private void updateMessagingSettings() {
        File debugFile = null;
        if (!Setting.DEBUG_FILE.asString().isEmpty()) {
            debugFile = new File(file.getParentFile(), Setting.DEBUG_FILE.asString());
        }
        Messaging.configure(debugFile, Setting.DEBUG_MODE.asBoolean(),
                Setting.RESET_FORMATTING_ON_COLOR_CHANGE.asBoolean(), Setting.MESSAGE_COLOUR.asString(),
                Setting.HIGHLIGHT_COLOUR.asString(), Setting.ERROR_COLOUR.asString());
    }

    /**
     * A config entry. Paths and defaults match upstream so an existing {@code config.yml} keeps working; the
     * {@code migrateFrom} path is upstream's own renaming trail and is honoured for the same reason.
     */
    public enum Setting {
        ALWAYS_USE_NAME_HOLOGRAM("npc.always-use-name-holograms", false),
        BOSSBAR_RANGE("npc.default.bossbar-view-range", 64),
        CHAT_BYSTANDERS_HEAR_TARGETED_CHAT("npc.chat.options.bystanders-hear-targeted-chat", false),
        CHAT_FORMAT("npc.chat.format.no-targets", "[<npc>]: <text>"),
        CHAT_FORMAT_TO_BYSTANDERS("npc.chat.format.with-target-to-bystanders", "[<npc>] -> [<target>]: <text>"),
        CHAT_FORMAT_TO_TARGET("npc.chat.format.to-target", "<npc>: <text>"),
        CHAT_FORMAT_WITH_TARGETS_TO_BYSTANDERS("npc.chat.format.with-targets-to-bystanders",
                "[<npc>] -> [<targets>]: <text>"),
        CHAT_MAX_NUMBER_OF_TARGETS("npc.chat.options.max-number-of-targets-to-show", 2),
        CHAT_MULTIPLE_TARGETS_FORMAT("npc.chat.options.multiple-targets-format",
                "<target>|, <target>| & <target>| & others"),
        CHAT_RANGE("npc.chat.options.range", 5),
        DEBUG_FILE("general.debug-file", ""),
        DEBUG_MODE("general.debug-mode", false),
        DEBUG_PATHFINDING("npc.pathfinding.debug", "npc.pathfinding.debug-paths", false),
        DEFAULT_BLOCK_BREAKER_RADIUS("npc.defaults.block-breaker-radius",
                "npc.default.block-breaker-radius", -1),
        DEFAULT_CACHE_WAYPOINT_PATHS("npc.default.waypoints.cache-paths", false),
        DEFAULT_DESTINATION_TELEPORT_MARGIN("npc.pathfinding.defaults.destination-teleport-margin",
                "npc.pathfinding.default-destination-teleport-margin", -1),
        DEFAULT_DISTANCE_MARGIN("npc.pathfinding.default-distance-margin", 1),
        DEFAULT_HOLOGRAM_RENDERER("npc.hologram.default-renderer", "display"),
        DEFAULT_HOLOGRAM_RENDERER_SETTINGS("npc.hologram.default-renderer-settings", defaultRendererSettings()),
        DEFAULT_LOOK_CLOSE("npc.default.look-close.enabled", false),
        DEFAULT_LOOK_CLOSE_RANGE("npc.default.look-close.range", 10),
        DEFAULT_NPC_HOLOGRAM_LINE_HEIGHT("npc.hologram.default-line-height", 0.4D),
        DEFAULT_NPC_LIMIT("npc.limits.default-limit", 10),
        DEFAULT_PATH_DISTANCE_MARGIN("npc.pathfinding.default-path-distance-margin", 0),
        DEFAULT_PATHFINDER_UPDATE_PATH_RATE("npc.pathfinding.update-path-rate", "1s"),
        DEFAULT_PATHFINDING_RANGE("npc.default.pathfinding.range", "npc.pathfinding.default-range-blocks", 100F),
        DEFAULT_RANDOM_LOOK_CLOSE("npc.default.look-close.random-look-enabled", false),
        DEFAULT_RANDOM_LOOK_DELAY("npc.default.look-close.random-look-delay", "3s"),
        DEFAULT_REALISTIC_LOOKING("npc.default.realistic-looking", "npc.default.look-close.realistic-looking", false),
        DEFAULT_STATIONARY_DURATION("npc.default.stationary-duration",
                "npc.pathfinding.default-stationary-duration", -1),
        DEFAULT_STRAIGHT_LINE_TARGETING_DISTANCE("npc.pathfinding.straight-line-targeting-distance", 5),
        DEFAULT_STUCK_ACTION("npc.pathfinding.default-stuck-action", "none"),
        DEFAULT_TALK_CLOSE("npc.default.talk-close.enabled", false),
        DEFAULT_TALK_CLOSE_RANGE("npc.default.talk-close.range", 5),
        DEFAULT_RANDOM_TALKER("npc.default.random-talker", "npc.default.talk-close.random-talker", false),
        DEFAULT_TEXT("npc.default.talk-close.text", listOf("Hi, I'm <npc>!")),
        DEFAULT_TEXT_DELAY_MAX("npc.text.default-random-text-delay-max", "10s"),
        DEFAULT_TEXT_DELAY_MIN("npc.text.default-random-text-delay-min", "5s"),
        DEFAULT_TEXT_SPEECH_BUBBLE_DURATION("npc.text.speech-bubble-ticks", "npc.text.speech-bubble-duration", "50t"),
        DISABLE_LOOKCLOSE_WHILE_NAVIGATING("npc.default.look-close.disable-while-navigating", true),
        DISABLE_TABLIST("npc.tablist.disable", true),
        ERROR_COLOUR("general.color-scheme.message-error", "<red>"),
        FOLLOW_ACROSS_WORLDS("npc.follow.teleport-across-worlds", false),
        HIGHLIGHT_COLOUR("general.color-scheme.message-highlight", "<yellow>"),
        HOLOGRAM_ALWAYS_UPDATE_POSITION("npc.hologram.always-update-position", false),
        HOLOGRAM_UPDATE_RATE("npc.hologram.update-rate-ticks", "npc.hologram.update-rate", "1s"),
        KEEP_CHUNKS_LOADED("npc.chunks.always-keep-loaded", false),
        PLAYER_NPCS_COUNT_FOR_MOB_SPAWNING("npc.player-npcs-count-for-mob-spawning", false),
        PLAYER_NPCS_LOAD_CHUNKS("npc.chunks.player-npcs-load-chunks", false),
        MAX_NPC_LIMIT_CHECKS("npc.limits.max-permission-checks", 100),
        MESSAGE_COLOUR("general.color-scheme.message", "<green>"),
        CONTROLLABLE_GROUND_DIRECTION_MODIFIER("npc.controllable.ground-direction-modifier", 1.0D),
        MAX_CONTROLLABLE_FLIGHT_SPEED("npc.controllable.max-flying-speed", 0.75),
        MAX_CONTROLLABLE_GROUND_SPEED("npc.controllable.max-ground-speed", 0.5),
        USE_BOAT_CONTROLS("npc.controllable.use-boat-controls", true),
        NPC_ATTACK_DISTANCE("npc.pathfinding.attack-range", 1.75),
        NPC_COMMAND_GLOBAL_COMMAND_COOLDOWN("npc.commands.global-cooldown", "npc.commands.global-delay-seconds", "1s"),
        NPC_COMMAND_GLOBAL_MAXIMUM_TIMES_USED_MESSAGE("npc.commands.error-messages.global-maximum-times-used",
                "You have reached the maximum number of uses ({0})."),
        NPC_COMMAND_MAXIMUM_TIMES_USED_MESSAGE("npc.commands.error-messages.maximum-times-used",
                "You have reached the maximum number of uses ({0})."),
        NPC_COMMAND_MISSING_ITEM_MESSAGE("npc.commands.error-messages.missing-item", "Missing {1} {0}"),
        NPC_COMMAND_NO_PERMISSION_MESSAGE("npc.commands.error-messages.no-permission",
                "You don''t have permission to do that."),
        NPC_COMMAND_NOT_ENOUGH_EXPERIENCE_MESSAGE("npc.commands.error-messages.not-enough-experience",
                "You need at least {0} experience."),
        NPC_COMMAND_NOT_ENOUGH_MONEY_MESSAGE("npc.commands.error-messages.not-enough-money", "You need at least ${0}."),
        NPC_COMMAND_ON_COOLDOWN_MESSAGE("npc.commands.error-messages.on-cooldown",
                "Please wait for {minutes} minutes and {seconds_over} seconds."),
        NPC_COMMAND_ON_GLOBAL_COOLDOWN_MESSAGE("npc.commands.error-messages.on-global-cooldown",
                "Please wait for {minutes} minutes and {seconds_over} seconds."),
        NPC_COST("npc.defaults.npc-cost", "npc.default.npc-cost", 100D),
        NPC_SKIN_FETCH_DEFAULT("npc.skins.try-fetch-default-skin", true),
        NPC_SKIN_USE_LATEST("npc.skins.use-latest-by-default", false),
        NPC_SKIN_VIEW_DISTANCE("npc.skins.view-distance", 100),
        NPC_WATER_SPEED_MODIFIER("npc.movement.water-speed-modifier", 1.15F),
        PACKET_HOLOGRAMS("npc.use-packet-holograms", false),
        // Upstream prefixes these CITIZENS_PATHFINDER_* to tell its own pathfinder apart from Minecraft's, which has no
        // settings of its own. The config paths keep the "citizens" segment so an existing config.yml still reads.
        PATHFINDER_CHECK_BOUNDING_BOXES("npc.pathfinding.citizens.check-bounding-boxes", false),
        PATHFINDER_FALL_DISTANCE("npc.pathfinding.allowed-fall-distance", -1),
        PATHFINDER_JUMPS("npc.pathfinding.citizens.experimental-jumps", false),
        PATHFINDER_ITERATIONS_PER_TICK("npc.pathfinding.citizens.blocks-per-tick", 250),
        PATHFINDER_MAX_ITERATIONS("npc.pathfinding.citizens.maximum-search-blocks", 1024),
        PATHFINDER_OPENS_DOORS("npc.pathfinding.citizens.open-doors", false),
        PATHFINDER_TYPE("npc.pathfinding.pathfinder-type", "MINECRAFT"),
        PLACEHOLDER_SKIN_UPDATE_FREQUENCY("npc.skins.placeholder-update-frequency-ticks",
                "npc.skins.placeholder-update-frequency", "5m"),
        REMOVE_PLAYERS_FROM_PLAYER_LIST("npc.player.remove-from-list", true),
        RESET_FORMATTING_ON_COLOR_CHANGE("general.reset-formatting-on-color-change", false),
        SERVER_OWNS_NPCS("npc.server-ownership", false),
        SHOP_DEFAULT_ITEM_SETTINGS("npc.shops.default-item", defaultShopItemSettings()),
        SHOP_GLOBAL_VIEW_PERMISSION("npc.defaults.shops.global-view-permission", "npc.shops.global-view-permission", ""),
        SHOP_USE_DEFAULT_DESCRIPTION("npc.shops.add-default-item-description", false),
        TABLIST_REMOVE_PACKET_DELAY("npc.tablist.remove-packet-delay", "2t"),
        TALK_CLOSE_TO_NPCS("npc.chat.options.talk-to-npcs", true),
        TALK_ITEM("npc.text.talk-item", "*"),
        USE_SCOREBOARD_TEAMS("npc.defaults.enable-scoreboard-teams", "npc.default.enable-scoreboard-teams", true),
        WARN_ON_RELOAD("general.reload-warning", true);

        private final Object def;
        private Duration duration;
        private final String migrateFrom;
        private final String path;
        private Object value;

        Setting(String path, Object def) {
            this(path, null, def);
        }

        Setting(String path, String migrateFrom, Object def) {
            this.path = path;
            this.migrateFrom = migrateFrom;
            this.def = def;
            value = def;
        }

        /**
         * Reads this setting out of the config, migrating from the old path when present.
         *
         * @return true when the config was missing the key and the default was written back
         */
        boolean loadFrom(DataKey root) {
            duration = null;
            if (migrateFrom != null && root.keyExists(migrateFrom) && !root.keyExists(path)) {
                value = root.getRaw(migrateFrom);
                root.removeKey(migrateFrom);
                writeTo(root);
                return true;
            }
            if (!root.keyExists(path)) {
                value = def;
                writeTo(root);
                return true;
            }
            Object raw = root.getRaw(path);
            value = raw == null ? def : raw;
            return false;
        }

        void writeTo(DataKey root) {
            root.setRaw(path, value);
        }

        public boolean asBoolean() {
            if (value instanceof Boolean bool)
                return bool;
            return Boolean.parseBoolean(asString());
        }

        public double asDouble() {
            if (value instanceof Number number)
                return number.doubleValue();
            return Double.parseDouble(asString());
        }

        public Duration asDuration() {
            if (duration == null) {
                duration = Durations.parse(asString(), null);
            }
            return duration;
        }

        public float asFloat() {
            return (float) asDouble();
        }

        public int asInt() {
            if (value instanceof Number number)
                return number.intValue();
            return Integer.parseInt(asString());
        }

        @SuppressWarnings("unchecked")
        public List<String> asList() {
            if (value instanceof List)
                return (List<String>) value;
            // YamlStorage keeps YAML sequences as index-keyed maps in memory and only flattens them back on save, so a
            // list read out of the config arrives in that shape rather than as a List
            if (value instanceof Map) {
                Map<String, Object> indexed = (Map<String, Object>) value;
                List<String> flattened = new ArrayList<>(indexed.size());
                for (int i = 0; i < indexed.size(); i++) {
                    Object element = indexed.get(String.valueOf(i));
                    if (element == null) {
                        break;
                    }
                    flattened.add(element.toString());
                }
                value = flattened;
                return flattened;
            }
            List<String> single = new ArrayList<>(1);
            single.add(asString());
            value = single;
            return single;
        }

        public int asSeconds() {
            return Durations.toSeconds(asDuration());
        }

        public String asString() {
            return value == null ? "" : value.toString();
        }

        public int asTicks() {
            return Durations.toTicks(asDuration());
        }

        /**
         * A setting whose value is a nested section rather than a scalar, e.g. the default hologram renderer's
         * properties. Upstream returns a Bukkit {@code ConfigurationSection}; {@link YamlStorage} already models nesting
         * as plain maps, so that is what comes back here.
         */
        @SuppressWarnings("unchecked")
        public Map<String, Object> asMap() {
            return value instanceof Map ? (Map<String, Object>) value : Map.of();
        }

        public String getPath() {
            return path;
        }

        /** Overrides the in-memory value. Not written to disk until {@link Settings#save()}. */
        public void set(Object newValue) {
            value = newValue;
            duration = null;
        }

        public void setAsDuration(String raw, TimeUnit defaultUnits) {
            value = raw;
            duration = Durations.parse(raw, defaultUnits);
        }
    }

    private static List<String> listOf(String... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    /** Defaults for {@code npc.shops.default-item}: every key present and empty, so the config documents itself. */
    private static Map<String, Object> defaultShopItemSettings() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (String key : new String[] { "times-purchasable", "global-times-purchasable", "max-repeats-on-shift-click",
                "result-message", "name", "cost-message", "lore", "already-purchased-message",
                "click-to-confirm-message" }) {
            defaults.put(key, "");
        }
        return defaults;
    }

    /** Defaults for {@code npc.hologram.default-renderer-settings}, matching upstream key for key. */
    private static Map<String, Object> defaultRendererSettings() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("seeThrough", false);
        defaults.put("shadowed", false);
        defaults.put("billboard", "CENTER");
        defaults.put("interpolationDelay", 0);
        defaults.put("interpolationDuration", 0);
        return defaults;
    }
}
