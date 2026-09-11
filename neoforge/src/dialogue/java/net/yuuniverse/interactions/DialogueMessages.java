package net.yuuniverse.interactions;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import net.minecraft.network.chat.Component;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/** Supported legacy message overrides; absent entries use translatable English fallbacks. */
public record DialogueMessages(String nextText, String nextHover) {
    public static final DialogueMessages DEFAULT = new DialogueMessages(null, null);

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
            return new DialogueMessages(message(values, "nextDialogueText"), message(values, "nextDialogueHover"));
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
