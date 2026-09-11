package net.yuuniverse.interactions;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/** Supported legacy message overrides; absent entries use translatable English fallbacks. */
public record DialogueMessages(String nextText, String nextHover, String optionsFormat, String clickableOptionHover,
        List<String> optionsMainFormat) {
    public static final DialogueMessages DEFAULT = new DialogueMessages(null, null);

    public DialogueMessages(String nextText, String nextHover) {
        this(nextText, nextHover, null, null, null);
    }

    public DialogueMessages {
        if (optionsMainFormat != null) optionsMainFormat = List.copyOf(optionsMainFormat);
    }

    public Component optionLabel(int number, String text, ServerPlayer player) {
        if (optionsFormat == null) return Component.translatableWithFallback("interactions.option.label", "[%s] %s",
                number, Text.legacy(Text.placeholders(text, player)));
        return Text.legacy(Text.placeholders(optionsFormat.replace("%number%", Integer.toString(number))
                .replace("%text%", text), player));
    }

    public Component optionTooltip(int number, ServerPlayer player) {
        if (clickableOptionHover == null) return Component.translatableWithFallback("interactions.option.hover",
                "Click to choose option %s.", number);
        return Text.legacy(Text.placeholders(clickableOptionHover.replace("%option%", Integer.toString(number)), player));
    }

    public Component nextLabel() {
        return nextText == null ? Component.translatableWithFallback("interactions.dialogue.next", "[Next →]")
                : Text.legacy(nextText);
    }

    public Component nextTooltip() {
        return nextHover == null ? Component.translatableWithFallback("interactions.dialogue.next.hover",
                "Click to continue with the conversation.") : Text.legacy(nextHover);
    }

    public static DialogueMessages load(File file, DialogueMessages previous) {
        if (!file.exists()) return DEFAULT;
        try (var reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            if (!(new Yaml(new LoaderOptions()).load(reader) instanceof Map<?, ?> values))
                throw new IllegalArgumentException("Expected a messages map");
            List<String> layout = null;
            if (values.containsKey("optionsMainFormat")) {
                if (!(values.get("optionsMainFormat") instanceof List<?> entries)
                        || entries.stream().anyMatch(entry -> !(entry instanceof String)))
                    throw new IllegalArgumentException("Expected a string list for optionsMainFormat");
                layout = entries.stream().map(String.class::cast).toList();
            }
            return new DialogueMessages(message(values, "nextDialogueText"), message(values, "nextDialogueHover"),
                    message(values, "optionsFormat"), message(values, "clickableOptionHover"), layout);
        } catch (Exception failure) {
            LoggerFactory.getLogger("interactions").error("Could not load {}; retaining previous dialogue messages", file, failure);
            return previous;
        }
    }

    private static String message(Map<?, ?> values, String key) {
        if (!values.containsKey(key)) return null;
        if (values.get(key) instanceof String text) return text;
        throw new IllegalArgumentException("Expected text for " + key);
    }
}
