package net.yuuniverse.interactions;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Redirects Bukkit material names whose item no longer exists under that name.
 * <p>
 * Most names convert mechanically, but a mod that renamed its items between the version the old server ran and the one
 * running now leaves names that resolve to nothing: the saved-item database asks for {@code PROSPEROUS_UDT_102024} while
 * the mod here registers {@code prosperous:timothy_udt_10}. Those are the same ten-UDT banknote, and no amount of string
 * manipulation can know that - it needs to be stated.
 * <p>
 * The file is written on first start with the renames found while migrating this server, and can be extended by hand:
 * every line is {@code BUKKIT_MATERIAL_NAME: namespace:item_id}. A name that is not listed still goes through the normal
 * conversion, so the file only ever needs the exceptions.
 */
public final class ItemAliases {
    private static final Logger LOGGER = LoggerFactory.getLogger("interactions");
    private static final Map<String, String> ALIASES = new ConcurrentHashMap<>();

    /**
     * The renames this server needs, from comparing the old item database against what the mods here register. The UDT
     * banknotes were reissued under a new name and the old series (2022 and 2024) both map onto the current one of the
     * same denomination.
     */
    private static final String[][] SEED = { { "prosperous_udt_52022", "prosperous:timothy_udt_5" },
            { "prosperous_udt_52024", "prosperous:timothy_udt_5" },
            { "prosperous_udt_102022", "prosperous:timothy_udt_10" },
            { "prosperous_udt_102024", "prosperous:timothy_udt_10" },
            { "prosperous_udt_202022", "prosperous:timothy_udt_20" },
            { "prosperous_udt_202024", "prosperous:timothy_udt_20" },
            { "prosperous_udt_502022", "prosperous:timothy_udt_50" },
            { "prosperous_udt_502024", "prosperous:timothy_udt_50" },
            { "prosperous_udt_1002022", "prosperous:timothy_udt_100" },
            { "prosperous_udt_1002024", "prosperous:timothy_udt_100" } };

    private ItemAliases() {
    }

    /** @return the item id this material name should become, or null to use the normal conversion */
    public static String lookup(String bukkitName) {
        return bukkitName == null ? null : ALIASES.get(bukkitName.toLowerCase(Locale.ROOT));
    }

    @SuppressWarnings("unchecked")
    public static void load(File file) {
        ALIASES.clear();
        if (!file.isFile()) {
            write(file);
        }
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(1024 * 1024);
            Object loaded = new Yaml(options).load(reader);
            if (loaded instanceof Map) {
                for (Map.Entry<String, Object> entry : ((Map<String, Object>) loaded).entrySet()) {
                    if (entry.getValue() != null) {
                        ALIASES.put(entry.getKey().toLowerCase(Locale.ROOT), String.valueOf(entry.getValue()).trim());
                    }
                }
            }
        } catch (Exception ex) {
            LOGGER.error("Could not read {}: {}", file.getName(), ex.toString());
        }
        LOGGER.info("{} item name alias(es) in effect.", ALIASES.size());
    }

    private static void write(File file) {
        file.getParentFile().mkdirs();
        try (PrintWriter writer = new PrintWriter(file, StandardCharsets.UTF_8)) {
            writer.println("# Bukkit material name -> the item id it should become here.");
            writer.println("#");
            writer.println("# Only exceptions belong in this file. A name not listed is converted by trying every");
            writer.println("# underscore as the namespace separator and keeping whichever split the registry knows,");
            writer.println("# which handles both PROSPEROUS_U_10 and REFURBISHED_FURNITURE_PACKAGE on its own.");
            writer.println("#");
            writer.println("# These entries exist because the items really were renamed between the mod version the old");
            writer.println("# server ran and the one running now.");
            for (String[] pair : SEED) {
                writer.println(pair[0] + ": " + pair[1]);
            }
        } catch (Exception ex) {
            LOGGER.error("Could not write {}: {}", file.getName(), ex.toString());
        }
    }
}
