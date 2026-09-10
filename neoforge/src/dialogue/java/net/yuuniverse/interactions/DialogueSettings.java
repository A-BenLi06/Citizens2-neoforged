package net.yuuniverse.interactions;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/** Reads supported legacy global settings without rewriting unknown settings. */
public record DialogueSettings(boolean allowChat, boolean allowMobDamage) {
    public static final DialogueSettings DEFAULT = new DialogueSettings(false, false);

    public static DialogueSettings load(File file, DialogueSettings previous) {
        if (!file.exists()) return DEFAULT;
        try (var reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Object data = new Yaml(new LoaderOptions()).load(reader);
            if (!(data instanceof Map<?, ?> values)) throw new IllegalArgumentException("Expected a settings map");
            return new DialogueSettings(flag(values, "allow_chat_while_in_conversation"), flag(values, "allow_mob_damage"));
        } catch (Exception failure) {
            LoggerFactory.getLogger("interactions").error("Could not load {}; retaining previous dialogue settings", file, failure);
            return previous;
        }
    }

    private static boolean flag(Map<?, ?> values, String key) {
        if (!values.containsKey(key)) return false;
        Object value = values.get(key);
        if (value instanceof Boolean flag) return flag;
        throw new IllegalArgumentException("Expected a boolean for " + key);
    }
}
