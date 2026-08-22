package net.citizensnpcs.util;

import java.text.DecimalFormat;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.gui.MenuItems;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.Placeholders;
import net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot;
import net.citizensnpcs.api.util.Location;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Assorted helpers.
 * <p>
 * Upstream's {@code Util} is 481 lines of mostly Bukkit-specific geometry and entity plumbing that the port replaces
 * with direct Minecraft calls. Only the parts that are still meaningful are carried over, and each is added when a
 * caller needs it rather than up front.
 */
public class Util {
    private Util() {
    }

    /**
     * @return a shared non-cryptographic random. Faster than {@code Math.random()} and, unlike {@code ThreadLocalRandom},
     *         reproducible enough for cosmetic behaviour like random head movement.
     */
    public static Random getFastRandom() {
        return RANDOM;
    }

    /**
     * Wraps a rotation angle into [-180, 180].
     * <p>
     * Vanilla's {@code Mth.wrapDegrees} does the same for doubles and floats, but it is kept as its own method because the
     * rotation code also needs the ranged variant below and reads better with the pair side by side.
     */
    public static float clamp(float angle) {
        float d = angle % 360.0F;
        if (d >= 180.0F) {
            d -= 360.0F;
        }
        if (d < -180.0F) {
            d += 360.0F;
        }
        return d;
    }

    /**
     * Wraps an angle into {@code [min, max)} by repeatedly adding or subtracting {@code period}.
     * <p>
     * Used to hold a rotation inside a configured yaw or pitch range without snapping it to the boundary.
     */
    public static float clamp(float angle, float min, float max, float period) {
        while (angle < min) {
            angle += period;
        }
        while (angle >= max) {
            angle -= period;
        }
        return angle;
    }

    /** Scoreboard team names are limited in length, so the NPC's UUID is truncated into one. */
    public static String getTeamName(UUID id) {
        return id.toString().substring(0, 16);
    }

    /**
     * Whether the player's held item matches an item filter — a comma-separated list of item ids, or {@code *} / empty
     * for "anything".
     * <p>
     * Upstream matches against Bukkit {@code Material} names; here the parts are resolved through the item registry, so
     * both {@code written_book} and {@code minecraft:written_book} work, as do items added by other mods. A part that
     * names no known item never matches, rather than matching everything.
     */
    public static boolean matchesItemInHand(ServerPlayer player, String setting) {
        if (setting == null || setting.isEmpty() || setting.contains("*"))
            return true;
        ItemStack held = player.getMainHandItem();
        for (String part : Splitter.on(',').trimResults().omitEmptyStrings().split(setting)) {
            ResourceLocation id = ResourceLocation.tryParse(part.toLowerCase(Locale.ROOT));
            if (id == null) {
                continue;
            }
            if (BuiltInRegistries.ITEM.get(id) == held.getItem())
                return true;
        }
        return false;
    }

    /**
     * Resolves a dimension the way a player would name one on the command line: a full id such as
     * {@code minecraft:the_nether}, or just its path.
     *
     * @return the level, or null when no dimension matches
     */
    public static ServerLevel getLevel(MinecraftServer server, String name) {
        if (server == null || name == null || name.isEmpty())
            return null;
        ResourceLocation id = ResourceLocation.tryParse(name.contains(":") ? name : "minecraft:" + name);
        if (id == null)
            return null;
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level != null)
            return level;
        for (ServerLevel candidate : server.getAllLevels()) {
            if (candidate.dimension().location().getPath().equals(id.getPath()))
                return candidate;
        }
        return null;
    }

    /** A location as shown to a command sender. Upstream prints the world name; a dimension id is the counterpart. */
    public static String prettyPrintLocation(Location to) {
        return String.format("%s at %s, %s, %s (%s, %s)",
                to.getWorld() == null ? "?" : to.getWorld().dimension().location(), TWO_DIGIT_DECIMAL.format(to.getX()),
                TWO_DIGIT_DECIMAL.format(to.getY()), TWO_DIGIT_DECIMAL.format(to.getZ()),
                TWO_DIGIT_DECIMAL.format(to.getYaw()), TWO_DIGIT_DECIMAL.format(to.getPitch()));
    }

    /**
     * A display item for a menu button: an item with a name and, optionally, lore.
     *
     * @param id
     *            a registry id such as {@code minecraft:barrier}
     */
    public static ItemStack createItem(String id, String name, String lore) {
        ItemStack stack = MenuItems.byId(id, 1);
        MenuItems.setDisplayName(stack, name);
        if (lore != null) {
            MenuItems.setLore(stack, lore);
        }
        MenuItems.hideAttributes(stack);
        return stack;
    }

    /**
     * Whether an entity would wear this item in the given slot if handed it.
     * <p>
     * Upstream tests Bukkit material name suffixes as well, because its {@code isEquippable} does not know about items
     * from other mods. Vanilla answers the question directly, and correctly for modded armour too.
     */
    public static boolean isEquippable(LivingEntity entity, ItemStack stack, EquipmentSlot slot) {
        if (stack == null || stack.isEmpty())
            return true;
        net.minecraft.world.entity.EquipmentSlot actual = entity.getEquipmentSlotForItem(stack);
        switch (slot) {
            case HELMET:
                return actual == net.minecraft.world.entity.EquipmentSlot.HEAD;
            case CHESTPLATE:
                return actual == net.minecraft.world.entity.EquipmentSlot.CHEST;
            case LEGGINGS:
                return actual == net.minecraft.world.entity.EquipmentSlot.LEGS;
            case BOOTS:
                return actual == net.minecraft.world.entity.EquipmentSlot.FEET;
            default:
                return true;
        }
    }

    /** {@code SOME_CONSTANT} becomes {@code Some constant}, for showing an enum to a player. */
    public static String prettyEnum(Enum<?> constant) {
        String name = constant.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * Runs a command on an NPC's behalf, as either the console or the clicking player.
     *
     * @param npc
     *            the NPC the command belongs to, for placeholder substitution and the implicit {@code --id}
     * @param clicker
     *            the player who triggered it, or null
     * @param command
     *            the command, without a leading slash
     * @param op
     *            whether to run it with elevated permission
     * @param asPlayer
     *            whether to run it as the clicker rather than as the console
     */
    public static void runCommand(NPC npc, ServerPlayer clicker, String command, boolean op, boolean asPlayer) {
        String cmd = command;
        if (cmd.startsWith("say")) {
            // "say X" is shorthand for making the NPC talk, not for the vanilla /say
            cmd = "npc speak \"" + cmd.replaceFirst("say", "").trim() + "\" --target <p>";
        }
        if ((cmd.startsWith("npc ") || cmd.startsWith("waypoints ") || cmd.startsWith("wp ")) && !cmd.contains("--id ")) {
            cmd += " --id <id>";
        }
        MinecraftServer server = clicker != null ? clicker.getServer() : ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return;
        CommandSourceStack base = asPlayer && clicker != null ? clicker.createCommandSourceStack()
                : server.createCommandSourceStack();
        String interpolated = Placeholders.replace(cmd, base, npc);
        Messaging.idebug(() -> "Running command " + interpolated + " on NPC " + (npc == null ? -1 : npc.getId())
                + " clicker " + clicker);
        // Upstream ops the player for the duration and notes the disk write that costs. A command source carries its own
        // permission level, so it can simply be raised for this one dispatch - no op list is touched at all.
        CommandSourceStack source = op ? base.withPermission(4) : base;
        CitizensAPI.getScheduler().runTask(() -> {
            try {
                server.getCommands().performPrefixedCommand(source, interpolated);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });
    }

    /**
     * Matches an enum constant by name, tolerating case and treating spaces, hyphens and underscores alike.
     *
     * @return the constant, or null when nothing matches
     */
    /** A comma-separated, colourised list of enum values for a "valid values are..." message. */
    public static String listValuesPretty(Object[] values) {
        return "<yellow>" + Joiner.on("<green>, <yellow>").join(values).replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    /**
     * Whether this entity type flies whatever the server does — the ones with no gravity-bound walking behaviour at all,
     * which therefore need the flying control scheme when ridden.
     */
    public static boolean isAlwaysFlyable(EntityType<?> type) {
        return type == EntityType.VEX || type == EntityType.PARROT || type == EntityType.ALLAY
                || type == EntityType.GHAST || type == EntityType.BEE
                || type == EntityType.PHANTOM || type == EntityType.BREEZE || type == EntityType.BAT
                || type == EntityType.BLAZE || type == EntityType.ENDER_DRAGON || type == EntityType.WITHER;
    }

    /** Horses and their relatives steer themselves once mounted, so the controller leaves their speed alone. */
    public static boolean isHorse(EntityType<?> type) {
        return type == EntityType.HORSE || type == EntityType.SKELETON_HORSE || type == EntityType.ZOMBIE_HORSE
                || type == EntityType.DONKEY || type == EntityType.MULE || type == EntityType.LLAMA
                || type == EntityType.TRADER_LLAMA || type == EntityType.CAMEL;
    }

    public static <T extends Enum<?>> T matchEnum(T[] values, String toMatch) {
        if (toMatch == null)
            return null;
        String normalised = normaliseEnumName(toMatch);
        T partial = null;
        for (T value : values) {
            String name = normaliseEnumName(value.name());
            if (name.equals(normalised))
                return value;
            if (partial == null && name.startsWith(normalised)) {
                partial = value;
            }
        }
        return partial;
    }

    private static String normaliseEnumName(String raw) {
        return raw.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    /**
     * The point an NPC should aim for to stand on a block: its horizontal centre, raised onto the block's own surface when
     * that surface is a thin one such as a slab or a carpet.
     */
    public static Location getCenterLocation(ServerLevel level, BlockPos pos) {
        double y = pos.getY();
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        // bounds() throws on an empty shape, and a waypoint standing in air is the common case
        if (!shape.isEmpty()) {
            AABB box = shape.bounds();
            double height = box.maxY - box.minY;
            if (height > 0 && height < 0.6D) {
                y += height;
            }
        }
        return new Location(level, pos.getX() + 0.5, y, pos.getZ() + 0.5);
    }

    private static final Random RANDOM = new XORShiftRNG();
    private static final DecimalFormat TWO_DIGIT_DECIMAL = new DecimalFormat("#.##");
}
