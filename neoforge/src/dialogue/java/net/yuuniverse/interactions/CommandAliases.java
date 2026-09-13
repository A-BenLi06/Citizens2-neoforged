package net.yuuniverse.interactions;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Rewrites commands the dialogues call by a name that belonged to a Bukkit plugin.
 * <p>
 * The conversations reach into whatever else the old server had installed: {@code manuadd} was GroupManager's,
 * {@code heal} EssentialsX's, {@code questadmin} Quests'. Those names mean nothing here, and a hard-coded guess at the
 * replacement would be worse than nothing - it would fail silently in a way nobody could correct without a rebuild. So
 * the mapping is a file: one line per command, edited without touching the mod.
 * <p>
 * A template may use {@code {player}} for the acting player and {@code {1}}, {@code {2}} … for the original command's
 * arguments. An empty value marks an unavailable command; action preflight rejects it before payment. A command with
 * no entry is passed to the server dispatcher after legacy vanilla root normalization.
 */
public final class CommandAliases {
    private static final Logger LOGGER = LoggerFactory.getLogger("interactions");
    private static volatile Map<String, String> templates = Map.of();
    private static final Set<String> NATIVE_SERVICES = Set.of("shop", "cam-server");

    /** What the migrated dialogues call, and the best-known replacement on this server. */
    private static final String[][] SEED = {
            { "manuadd", "paradigm permissions group add {player} {2}" },
            { "manudel", "paradigm permissions group remove {player} {2}" },
            { "heal", "effect give {player} minecraft:instant_health 1 10 true" },
            { "questadmin", "" } };

    private CommandAliases() {
    }

    /**
     * @return the command to run instead, or the original when nothing is mapped; an empty string means unavailable
     */
    public static String rewrite(String line, String playerName) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length == 0)
            return line;
        String template = templates.get(parts[0].toLowerCase(Locale.ROOT));
        if (template == null)
            return line;
        if (template.isBlank())
            return "";
        String result = template.replace("{player}", playerName == null ? "" : playerName);
        for (int i = parts.length - 1; i >= 1; i--) {
            result = result.replace("{" + i + "}", parts[i]);
        }
        return result.trim();
    }

    @SuppressWarnings("unchecked")
    public static void load(File file) {
        if (!file.isFile()) {
            write(file);
        }
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(1024 * 1024);
            Object loaded = new Yaml(options).load(reader);
            if (!(loaded instanceof Map<?, ?> values)) {
                throw new IllegalArgumentException("Expected a command alias map");
            }
            Map<String, String> updated = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                if (!(entry.getKey() instanceof String name)
                        || entry.getValue() != null && !(entry.getValue() instanceof String)) {
                    throw new IllegalArgumentException("Expected command names and string alias templates");
                }
                name = name.toLowerCase(Locale.ROOT);
                String template = entry.getValue() == null ? "" : ((String) entry.getValue()).trim();
                // Older generated files marked these services unavailable before their providers were connected.
                // Explicit replacement templates still take precedence; missing providers fail action preflight.
                if (!template.isBlank() || !NATIVE_SERVICES.contains(name)) updated.put(name, template);
            }
            templates = Map.copyOf(updated);
        } catch (Exception ex) {
            LOGGER.error("Could not read {}; retaining previous command aliases: {}", file.getName(), ex.toString());
            return;
        }
        Map<String, String> dropped = new LinkedHashMap<>();
        templates.forEach((name, template) -> {
            if (template.isBlank()) {
                dropped.put(name, template);
            }
        });
        LOGGER.info("{} command alias(es) in effect, {} of them unavailable.", templates.size(), dropped.size());
    }

    private static void write(File file) {
        file.getParentFile().mkdirs();
        try (PrintWriter writer = new PrintWriter(file, StandardCharsets.UTF_8)) {
            writer.println("# What a dialogue command should become on this server.");
            writer.println("#");
            writer.println("# The conversations were written against Bukkit plugins. Vanilla commands (setblock, give,");
            writer.println("# fill, playsound, tellraw, ...) normally need no entry. Bukkit minecraft: roots are resolved");
            writer.println("# against native command roots. Command arguments must match this Minecraft version.");
            writer.println("#");
            writer.println("# {player} is the player in the conversation; {1}, {2}, ... are the original arguments.");
            writer.println("# An empty value marks a missing service and fails action preflight before payment.");
            writer.println("#");
            writer.println("# manuadd was GroupManager's. The template below is Paradigm's shape but has NOT been");
            writer.println("# verified against a running Paradigm - check it with /paradigm and correct this one line");
            writer.println("# if the path differs. A wrong path is reported in the log, never silently ignored.");
            for (String[] pair : SEED) {
                writer.println(pair[0] + ": " + (pair[1].isEmpty() ? "''" : "'" + pair[1] + "'"));
            }
        } catch (Exception ex) {
            LOGGER.error("Could not write {}: {}", file.getName(), ex.toString());
        }
    }
}
