package net.yuuniverse.interactions;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/** Reads supported legacy global settings without rewriting unknown settings. */
public record DialogueSettings(boolean allowChat, boolean allowMobDamage, boolean allowCommands,
        List<String> commandsWhitelist, boolean skipDialogueOnNpcClick, boolean allowInventoryInteract,
        boolean clickableOptions, boolean useEmptySpaces, SelectionSettings selection, ConversationStartClick startClick,
        BossBarSettings bossBar) {
    public static final DialogueSettings DEFAULT = new DialogueSettings(false, false);

    public DialogueSettings(boolean allowChat, boolean allowMobDamage) {
        this(allowChat, allowMobDamage, false, List.of());
    }

    public DialogueSettings(boolean allowChat, boolean allowMobDamage, boolean allowCommands, List<String> whitelist) {
        this(allowChat, allowMobDamage, allowCommands, whitelist, false);
    }

    public DialogueSettings(boolean allowChat, boolean allowMobDamage, boolean allowCommands, List<String> whitelist,
            boolean skipDialogueOnNpcClick) {
        this(allowChat, allowMobDamage, allowCommands, whitelist, skipDialogueOnNpcClick, true);
    }

    public DialogueSettings(boolean allowChat, boolean allowMobDamage, boolean allowCommands, List<String> whitelist,
            boolean skipDialogueOnNpcClick, boolean allowInventoryInteract) {
        this(allowChat, allowMobDamage, allowCommands, whitelist, skipDialogueOnNpcClick, allowInventoryInteract, true, true);
    }

    public DialogueSettings(boolean allowChat, boolean allowMobDamage, boolean allowCommands, List<String> whitelist,
            boolean skipDialogueOnNpcClick, boolean allowInventoryInteract, boolean clickableOptions, boolean useEmptySpaces) {
        this(allowChat, allowMobDamage, allowCommands, whitelist, skipDialogueOnNpcClick, allowInventoryInteract,
                clickableOptions, useEmptySpaces, SelectionSettings.DEFAULT);
    }

    public DialogueSettings {
        commandsWhitelist = List.copyOf(commandsWhitelist);
    }

    public DialogueSettings(boolean allowChat, boolean allowMobDamage, boolean allowCommands, List<String> whitelist,
            boolean skipDialogueOnNpcClick, boolean allowInventoryInteract, boolean clickableOptions, boolean useEmptySpaces,
            SelectionSettings selection, ConversationStartClick startClick) {
        this(allowChat, allowMobDamage, allowCommands, whitelist, skipDialogueOnNpcClick, allowInventoryInteract,
                clickableOptions, useEmptySpaces, selection, startClick, BossBarSettings.DEFAULT);
    }

    public DialogueSettings(boolean allowChat, boolean allowMobDamage, boolean allowCommands, List<String> whitelist,
            boolean skipDialogueOnNpcClick, boolean allowInventoryInteract, boolean clickableOptions, boolean useEmptySpaces,
            SelectionSettings selection) {
        this(allowChat, allowMobDamage, allowCommands, whitelist, skipDialogueOnNpcClick, allowInventoryInteract,
                clickableOptions, useEmptySpaces, selection, ConversationStartClick.RIGHT_CLICK);
    }

    public boolean permitsCommand(String command) {
        if (allowCommands) return true;
        // This command carries dialogue option clicks, which must remain usable while commands are restricted.
        if (command.equals("interactions choose") || command.startsWith("interactions choose ")) return true;
        if (command.equals("interactions skipdialogue")) return true;
        String legacyInput = "/" + command.toLowerCase(Locale.ROOT);
        return commandsWhitelist.stream().anyMatch(legacyInput::startsWith);
    }

    public static DialogueSettings load(File file, DialogueSettings previous) {
        if (!file.exists()) return DEFAULT;
        try (var reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Object data = new Yaml(new LoaderOptions()).load(reader);
            if (!(data instanceof Map<?, ?> values)) throw new IllegalArgumentException("Expected a settings map");
            Object whitelist = values.containsKey("commands_whitelist") ? values.get("commands_whitelist") : List.of();
            if (!(whitelist instanceof List<?> entries) || entries.stream().anyMatch(entry -> !(entry instanceof String)))
                throw new IllegalArgumentException("Expected a string list for commands_whitelist");
            return new DialogueSettings(flag(values, "allow_chat_while_in_conversation"), flag(values, "allow_mob_damage"),
                    flag(values, "allow_commands_while_in_conversation"), entries.stream().map(String.class::cast).toList(),
                    flag(values, "skip_dialogue_on_npc_click"),
                    flag(values, "allow_inventory_interact_while_in_conversation", true),
                    flag(values, "clickable_options", true), flag(values, "use_empty_spaces", true),
                    new SelectionSettings(flag(values, "selectable_options", true),
                            SelectionSettings.Mode.valueOf(String.valueOf(values.containsKey("selectable_options_mode")
                                    ? values.get("selectable_options_mode") : "MOVE").toUpperCase(Locale.ROOT)),
                            flag(values, "selectable_options_restart_on_overflow", true)),
                    ConversationStartClick.valueOf(String.valueOf(values.containsKey("conversation_start_click_type")
                            ? values.get("conversation_start_click_type") : "RIGHT_CLICK").toUpperCase(Locale.ROOT)),
                    BossBarSettings.read(values.get("boss_bar")));
        } catch (Exception failure) {
            LoggerFactory.getLogger("interactions").error("Could not load {}; retaining previous dialogue settings", file, failure);
            return previous;
        }
    }

    private static boolean flag(Map<?, ?> values, String key) {
        return flag(values, key, false);
    }

    private static boolean flag(Map<?, ?> values, String key, boolean fallback) {
        if (!values.containsKey(key)) return fallback;
        Object value = values.get(key);
        if (value instanceof Boolean flag) return flag;
        throw new IllegalArgumentException("Expected a boolean for " + key);
    }
}
