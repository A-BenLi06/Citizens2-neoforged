package net.yuuniverse.interactions;

import java.util.Locale;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Maps the Bukkit world names the dialogues were written against onto this server's dimensions.
 * <p>
 * The migrated teleports say {@code uDays} and {@code uDays/DIM1}: Bukkit-on-Forge named the overworld after the
 * level-name and the other dimensions {@code <level>/DIM1} and {@code <level>/DIM-1}. The level name is arbitrary, so the
 * suffix is what identifies the dimension - the same rule Citizens' own location loader needed.
 */
public final class Worlds {
    private Worlds() {
    }

    public static ServerLevel resolve(MinecraftServer server, String bukkitName) {
        if (server == null || bukkitName == null)
            return null;
        String lower = bukkitName.trim().toLowerCase(Locale.ROOT);
        if (lower.endsWith("/dim1") || lower.endsWith("_the_end") || lower.equals("the_end"))
            return server.getLevel(Level.END);
        if (lower.endsWith("/dim-1") || lower.endsWith("_nether") || lower.equals("the_nether"))
            return server.getLevel(Level.NETHER);
        ResourceLocation id = ResourceLocation.tryParse(lower.indexOf(':') >= 0 ? lower : "minecraft:" + lower);
        if (id != null) {
            for (ServerLevel level : server.getAllLevels()) {
                if (level.dimension().location().equals(id))
                    return level;
            }
        }
        // a name matching no dimension is the overworld, which is what every migrated teleport but the End ones meant
        return server.overworld();
    }
}
