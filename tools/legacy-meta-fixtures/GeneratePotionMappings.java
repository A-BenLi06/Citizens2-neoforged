import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;

import org.bukkit.craftbukkit.v1_20_R1.potion.CraftPotionUtil;
import org.bukkit.potion.PotionData;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

/** Offline extraction from the original Bukkit/CraftBukkit implementation; no server is started. */
public class GeneratePotionMappings {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]); Files.createDirectories(output);
        var potions = new TreeMap<String, String>();
        for (PotionType type : PotionType.values()) {
            for (boolean extended : new boolean[]{false, true}) for (boolean upgraded : new boolean[]{false, true}) {
                PotionData data;
                try { data = new PotionData(type, extended, upgraded); }
                catch (IllegalArgumentException invalidCombination) { continue; }
                potions.put(type.name() + "." + extended + "." + upgraded, CraftPotionUtil.fromBukkit(data));
            }
        }
        var effects = new TreeMap<String, String>();
        for (var field : PotionEffectType.class.getFields()) {
            if (field.getType() == PotionEffectType.class)
                effects.put(field.getName(), Integer.toString(((PotionEffectType) field.get(null)).getId()));
        }
        write(output.resolve("legacy-potion-data.properties"), "Original CraftPotionUtil.fromBukkit results for valid PotionData combinations.", potions);
        write(output.resolve("legacy-potion-effect-names.properties"), "Original Bukkit PotionEffectType constant names to historical numeric IDs.", effects);
        System.out.println(potions.size() + " potion combinations; " + effects.size() + " effect names");
    }
    private static void write(Path path, String comment, TreeMap<String, String> values) throws Exception {
        StringBuilder text = new StringBuilder("# " + comment + "\n");
        values.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        Files.writeString(path, text);
    }
}
