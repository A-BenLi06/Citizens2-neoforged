package net.yuuniverse.interactions;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * arguments. An empty value means "ignore this command", for actions that have no equivalent and should stop filling the
 * log. A command with no entry is passed through unchanged, which is right for the ~2859 vanilla calls.
 */
public final class CommandAliases {
    private static final Logger LOGGER = LoggerFactory.getLogger("interactions");
    private static final Map<String, String> TEMPLATES = new ConcurrentHashMap<>();

    /** What the migrated dialogues call, and the best-known replacement on this server. */
    private static final String[][] SEED = {
            { "manuadd", "paradigm permissions group add {player} {2}" },
            { "manudel", "paradigm permissions group remove {player} {2}" },
            { "heal", "effect give {player} minecraft:instant_health 1 10 true" },
            { "questadmin", "" },
            { "shop", "" },
            { "cam-server", "" } };

    private CommandAliases() {
    }

    /**
     * @return the command to run instead, or the original when nothing is mapped; an empty string means the call should
     *         be dropped
     */
    public static String rewrite(String line, String playerName) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length == 0)
            return line;
        String template = TEMPLATES.get(parts[0].toLowerCase(Locale.ROOT));
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
        TEMPLATES.clear();
        if (!file.isFile()) {
            write(file);
        }
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(1024 * 1024);
            Object loaded = new Yaml(options).load(reader);
            if (loaded instanceof Map) {
                for (Map.Entry<String, Object> entry : ((Map<String, Object>) loaded).entrySet()) {
                    TEMPLATES.put(entry.getKey().toLowerCase(Locale.ROOT),
                            entry.getValue() == null ? "" : String.valueOf(entry.getValue()).trim());
                }
            }
        } catch (Exception ex) {
            LOGGER.error("Could not read {}: {}", file.getName(), ex.toString());
        }
        Map<String, String> dropped = new LinkedHashMap<>();
        TEMPLATES.forEach((name, template) -> {
            if (template.isBlank()) {
                dropped.put(name, template);
            }
        });
        LOGGER.info("{} command alias(es) in effect, {} of them dropping the call.", TEMPLATES.size(), dropped.size());
    }

    private static void write(File file) {
        file.getParentFile().mkdirs();
        try (PrintWriter writer = new PrintWriter(file, StandardCharsets.UTF_8)) {
            writer.println("# What a dialogue command should become on this server.");
            writer.println("#");
            writer.println("# The conversations were written against Bukkit plugins. Vanilla commands (setblock, give,");
            writer.println("# fill, playsound, tellraw, ...) need no entry - about 2859 of the calls are those and pass");
            writer.println("# through untouched. Only the plugin-specific names belong here.");
            writer.println("#");
            writer.println("# {player} is the player in the conversation; {1}, {2}, ... are the original arguments.");
            writer.println("# An empty value drops the call quietly, for anything with no equivalent here.");
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
