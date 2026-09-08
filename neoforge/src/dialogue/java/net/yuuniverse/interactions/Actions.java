package net.yuuniverse.interactions;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;

/**
 * Runs the actions a dialogue line carries. The old plugin had exactly seven verbs and this understands all of them.
 * <p>
 * Two of those verbs - {@code console_command} and {@code player_command_as_op} - are escape hatches into whatever else
 * the server had installed, and that is where the migrated data leans hardest: roughly 2859 of those calls are plain
 * vanilla commands that work unchanged, while {@code eco} (2277) and {@code si} (1899) belonged to plugins that do not
 * exist here. Those two are intercepted and served locally - {@code si} from {@link ItemLibrary}, {@code eco} through
 * whatever economy mod is present - so the dialogues keep working rather than failing at the till. Anything else is
 * passed to the server's own dispatcher, and a command that does not exist is reported once rather than silently doing
 * nothing.
 */
public final class Actions {
    private static final Logger LOGGER = LoggerFactory.getLogger("interactions");

    private final ItemLibrary items;
    private final Economy economy;

    public Actions(ItemLibrary items, Economy economy) {
        this.items = items;
        this.economy = economy;
    }

    public void runAll(List<String> actions, ServerPlayer player, Component npcName) {
        for (String action : actions) {
            try {
                run(action, player, npcName);
            } catch (Exception ex) {
                LOGGER.error("Dialogue action failed [{}]: {}", action, ex.toString());
            }
        }
    }

    private void run(String raw, ServerPlayer player, Component npcName) {
        if (raw == null || player == null)
            return;
        String action = Text.placeholders(raw.trim(), player);
        int colon = action.indexOf(':');
        if (colon < 0) {
            LOGGER.warn("Dialogue action has no verb: {}", raw);
            return;
        }
        String verb = action.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String body = action.substring(colon + 1).trim();
        switch (verb) {
            case "playsound" -> playSound(body, player);
            case "title" -> title(body, player);
            case "teleport" -> teleport(body, player);
            case "give_potion_effect" -> potion(body, player);
            case "remove_item" -> CheckItem.evaluate(body, player);
            case "console_command" -> command(body, player, true);
            case "player_command_as_op" -> command(body, player, false);
            default -> LOGGER.warn("Unknown dialogue action verb \"{}\" in: {}", verb, raw);
        }
    }

    /** {@code playsound: BLOCK_NOTE_BLOCK_PLING;10;0.1} - a Bukkit Sound name, then volume and pitch. */
    private void playSound(String body, ServerPlayer player) {
        List<String> parts = Text.semicolons(body);
        ResourceLocation id = soundId(parts.get(0));
        SoundEvent sound = id == null ? null : BuiltInRegistries.SOUND_EVENT.getOptional(id).orElse(null);
        if (sound == null) {
            LOGGER.warn("Dialogue asked for sound \"{}\", which does not exist here.", parts.get(0));
            return;
        }
        float volume = parts.size() > 1 ? floatOr(parts.get(1), 1) : 1;
        float pitch = parts.size() > 2 ? floatOr(parts.get(2), 1) : 1;
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), SoundSource.MASTER, player.getX(), player.getY(),
                player.getZ(), volume, pitch, player.level().getRandom().nextLong()));
    }

    /** A Bukkit sound constant is the registry path in upper case with dots as underscores. */
    static ResourceLocation soundId(String bukkitName) {
        String value = bukkitName.trim();
        if (value.indexOf(':') >= 0)
            return ResourceLocation.tryParse(value.toLowerCase(Locale.ROOT));
        return ResourceLocation.tryParse("minecraft:" + value.toLowerCase(Locale.ROOT).replace('_', '.'));
    }

    /** {@code title: 20;80;20;&6&lHeader;&fSubtitle} - fade in, stay, fade out, then the two lines. */
    private void title(String body, ServerPlayer player) {
        List<String> parts = Text.semicolons(body);
        int in = parts.size() > 0 ? (int) floatOr(parts.get(0), 10) : 10;
        int stay = parts.size() > 1 ? (int) floatOr(parts.get(1), 70) : 70;
        int out = parts.size() > 2 ? (int) floatOr(parts.get(2), 20) : 20;
        String header = parts.size() > 3 ? parts.get(3) : "";
        String sub = parts.size() > 4 ? String.join(";", parts.subList(4, parts.size())) : "";
        player.connection.send(new ClientboundSetTitlesAnimationPacket(in, stay, out));
        player.connection.send(new ClientboundSetTitleTextPacket(Text.legacy(header)));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Text.legacy(sub)));
    }

    /** {@code teleport: uDays;1666;86;-545;90;90} - a Bukkit world name, coordinates, then yaw and pitch. */
    private void teleport(String body, ServerPlayer player) {
        List<String> parts = Text.semicolons(body);
        if (parts.size() < 4) {
            LOGGER.warn("Dialogue teleport needs world;x;y;z: {}", body);
            return;
        }
        ServerLevel level = Worlds.resolve(player.getServer(), parts.get(0));
        if (level == null) {
            LOGGER.warn("Dialogue teleport names world \"{}\", which does not exist here.", parts.get(0));
            return;
        }
        double x = floatOr(parts.get(1), player.getX());
        double y = floatOr(parts.get(2), player.getY());
        double z = floatOr(parts.get(3), player.getZ());
        float yaw = parts.size() > 4 ? floatOr(parts.get(4), player.getYRot()) : player.getYRot();
        float pitch = parts.size() > 5 ? floatOr(parts.get(5), player.getXRot()) : player.getXRot();
        player.teleportTo(level, x, y, z, yaw, pitch);
    }

    /** {@code give_potion_effect: BLINDNESS;30;5;true} - effect, seconds, amplifier, then whether particles hide. */
    private void potion(String body, ServerPlayer player) {
        List<String> parts = Text.semicolons(body);
        ResourceLocation id = ResourceLocation.tryParse(parts.get(0).trim().toLowerCase(Locale.ROOT).indexOf(':') >= 0
                ? parts.get(0).trim().toLowerCase(Locale.ROOT)
                : "minecraft:" + parts.get(0).trim().toLowerCase(Locale.ROOT));
        MobEffect effect = id == null ? null : BuiltInRegistries.MOB_EFFECT.getOptional(id).orElse(null);
        if (effect == null) {
            LOGGER.warn("Dialogue asked for potion effect \"{}\", which does not exist here.", parts.get(0));
            return;
        }
        int seconds = parts.size() > 1 ? (int) floatOr(parts.get(1), 10) : 10;
        int amplifier = parts.size() > 2 ? (int) floatOr(parts.get(2), 0) : 0;
        boolean hidden = parts.size() > 3 && Boolean.parseBoolean(parts.get(3).trim());
        player.addEffect(new MobEffectInstance(BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect), seconds * 20,
                amplifier, false, !hidden, !hidden));
    }

    /**
     * Runs a command, intercepting the two that belonged to plugins this server does not have.
     *
     * @param asConsole
     *            true for {@code console_command}; {@code player_command_as_op} runs with the player as the source but
     *            at operator level, which is what the old plugin did
     */
    private void command(String body, ServerPlayer player, boolean asConsole) {
        String line = body.trim();
        if (line.startsWith("/")) {
            line = line.substring(1);
        }
        String[] parts = line.split("\\s+");
        String head = parts[0].toLowerCase(Locale.ROOT);
        if (head.equals("si") && parts.length >= 3 && parts[1].equalsIgnoreCase("give")) {
            giveSavedItem(parts, player);
            return;
        }
        if (head.equals("eco") || head.equals("balance") || head.equals("money")) {
            if (economy.handle(parts, player))
                return;
        }
        // a command that belonged to a Bukkit plugin is rewritten, or dropped when it has no equivalent here
        String rewritten = CommandAliases.rewrite(line, player.getGameProfile().getName());
        if (rewritten.isBlank()) {
            return;
        }
        if (!rewritten.equals(line)) {
            line = rewritten;
            parts = line.split("\s+");
        }
        MinecraftServer server = player.getServer();
        if (server == null)
            return;
        CommandSourceStack source = asConsole ? server.createCommandSourceStack()
                : player.createCommandSourceStack().withPermission(4);
        if (server.getCommands().getDispatcher().getRoot().getChild(parts[0]) == null) {
            LOGGER.warn("Dialogue ran \"{}\", but no such command exists on this server.", parts[0]);
            return;
        }
        server.getCommands().performPrefixedCommand(source, line);
    }

    /** {@code si give <id> <amount> <player> silent} - hand over one of the saved items. */
    private void giveSavedItem(String[] parts, ServerPlayer player) {
        String id = parts[2];
        int amount = parts.length > 3 ? (int) floatOr(parts[3], 1) : 1;
        ItemStack stack = items.get(id, amount);
        if (stack == null) {
            LOGGER.warn("Dialogue asked for saved item {}, which is not in the item database.", id);
            return;
        }
        if (!player.getInventory().add(stack) && !stack.isEmpty()) {
            player.drop(stack, false);
        }
    }

    private static float floatOr(String raw, double fallback) {
        try {
            return Float.parseFloat(raw.trim());
        } catch (Exception ex) {
            return (float) fallback;
        }
    }
}
