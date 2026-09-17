package net.yuuniverse.interactions;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;

/** Original colors/type/fade/power syntax, represented by a native rocket and its explosion component. */
record FireworkAction(Fireworks fireworks) {
    // Bukkit Color's API constants, not chat or dye colors (which have different RGB values).
    private static final Map<String, Integer> COLORS = Map.ofEntries(
            Map.entry("AQUA", 0x00ffff), Map.entry("BLACK", 0x000000), Map.entry("BLUE", 0x0000ff),
            Map.entry("FUCHSIA", 0xff00ff), Map.entry("GRAY", 0x808080), Map.entry("GREEN", 0x008000),
            Map.entry("LIME", 0x00ff00), Map.entry("MAROON", 0x800000), Map.entry("NAVY", 0x000080),
            Map.entry("OLIVE", 0x808000), Map.entry("ORANGE", 0xffa500), Map.entry("PURPLE", 0x800080),
            Map.entry("RED", 0xff0000), Map.entry("SILVER", 0xc0c0c0), Map.entry("TEAL", 0x008080),
            Map.entry("WHITE", 0xffffff), Map.entry("YELLOW", 0xffff00));

    static FireworkAction parse(String body) {
        var colors = new IntArrayList();
        var fade = new IntArrayList();
        FireworkExplosion.Shape shape = null;
        int power = 0;
        for (String field : body.trim().split("\\s+")) {
            int colon = field.indexOf(':');
            if (colon < 1) throw new IllegalArgumentException("Invalid firework field: " + field);
            String value = field.substring(colon + 1);
            switch (field.substring(0, colon)) {
                case "colors" -> addColors(colors, value);
                case "fade" -> addColors(fade, value);
                case "type" -> shape = switch (value.toUpperCase(Locale.ROOT)) {
                    case "BALL" -> FireworkExplosion.Shape.SMALL_BALL;
                    case "BALL_LARGE" -> FireworkExplosion.Shape.LARGE_BALL;
                    case "STAR" -> FireworkExplosion.Shape.STAR;
                    case "CREEPER" -> FireworkExplosion.Shape.CREEPER;
                    case "BURST" -> FireworkExplosion.Shape.BURST;
                    default -> throw new IllegalArgumentException("Unknown firework type: " + value);
                };
                case "power" -> power = ActionArguments.integer(value, 0, 127);
                default -> throw new IllegalArgumentException("Unknown firework field: " + field);
            }
        }
        if (shape == null || colors.isEmpty()) throw new IllegalArgumentException("Firework requires colors and type");
        return new FireworkAction(new Fireworks(power, List.of(new FireworkExplosion(shape,
                IntList.of(colors.toIntArray()), IntList.of(fade.toIntArray()), false, false))));
    }

    private static void addColors(IntList colors, String raw) {
        for (String name : raw.split(",", -1)) {
            Integer color = COLORS.get(name.toUpperCase(Locale.ROOT));
            if (color == null) throw new IllegalArgumentException("Unknown firework color: " + name);
            colors.add(color.intValue());
        }
    }

    void spawn(ServerPlayer player) {
        ItemStack item = new ItemStack(Items.FIREWORK_ROCKET);
        item.set(DataComponents.FIREWORKS, fireworks);
        var rocket = new FireworkRocketEntity(player.level(), player.getX(), player.getY(), player.getZ(), item);
        if (!player.serverLevel().addFreshEntity(rocket))
            throw new IllegalStateException("Could not spawn dialogue firework");
    }
}
