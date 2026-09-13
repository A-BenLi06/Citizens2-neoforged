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

/** Validates dialogue actions before execution and reports failures to the owning session. */
public final class Actions {
    private static final Logger LOGGER = LoggerFactory.getLogger("interactions");

    private final ItemLibrary items;
    private final Economy economy;

    public Actions(ItemLibrary items, Economy economy) {
        this.items = items;
        this.economy = economy;
    }

    public boolean runAll(List<String> actions, ServerPlayer player, Component npcName) {
        return process(actions, player, npcName, true);
    }

    public boolean validateAll(List<String> actions, ServerPlayer player, Component npcName) {
        return process(actions, player, npcName, false);
    }

    private boolean process(List<String> actions, ServerPlayer player, Component npcName, boolean execute) {
        if (actions.isEmpty()) return true;
        try {
            var inventory = player.getInventory();
            var payments = new CheckItem.PaymentPlan(java.util.stream.IntStream.range(0, inventory.getContainerSize())
                    .mapToObj(inventory::getItem).toList());
            ActionBatch.run(actions, action -> {
                run(action, player, npcName, true, payments);
                return () -> {
                    if (execute) run(action, player, npcName, false, payments);
                };
            });
            return true;
        } catch (Exception ex) {
            LOGGER.error("Dialogue action batch failed for {}: {}", player.getUUID(), actions, ex);
            player.sendSystemMessage(Component.translatableWithFallback("interactions.action.failed",
                    "This conversation could not complete an action. Please contact a server administrator."));
            return false;
        }
    }

    private void run(String raw, ServerPlayer player, Component npcName, boolean validate, CheckItem.PaymentPlan payments) {
        if (raw == null || player == null)
            throw new IllegalArgumentException("Missing action or player");
        String action = Text.placeholders(raw.trim(), player);
        int colon = action.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("Dialogue action has no verb: " + raw);
        }
        String verb = action.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String body = action.substring(colon + 1).trim();
        switch (verb) {
            case "playsound" -> playSound(body, player, validate);
            case "title" -> { if (!validate) title(body, player); }
            case "teleport" -> teleport(body, player, validate);
            case "give_potion_effect" -> potion(body, player, validate);
            case "remove_item" -> {
                if (validate) payments.reserve(body);
                else if (!CheckItem.consume(body, player))
                    throw new IllegalStateException("Required items unavailable: " + body);
            }
            case "console_command" -> command(body, player, true, validate);
            case "player_command_as_op" -> command(body, player, false, validate);
            default -> throw new IllegalArgumentException("Unknown dialogue action verb: " + verb);
        }
    }

    /** {@code playsound: BLOCK_NOTE_BLOCK_PLING;10;0.1} - a Bukkit Sound name, then volume and pitch. */
    private void playSound(String body, ServerPlayer player, boolean validate) {
        List<String> parts = Text.semicolons(body);
        ResourceLocation id = soundId(parts.get(0));
        SoundEvent sound = id == null ? null : BuiltInRegistries.SOUND_EVENT.getOptional(id).orElse(null);
        if (sound == null) {
            throw new IllegalArgumentException("Unknown dialogue sound: " + parts.get(0));
        }
        if (validate) return;
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
        return BuiltInRegistries.SOUND_EVENT.keySet().stream()
                .filter(id -> id.getNamespace().equals("minecraft")
                        && id.getPath().replace('.', '_').equalsIgnoreCase(value))
                .findFirst().orElse(null);
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
    private void teleport(String body, ServerPlayer player, boolean validate) {
        List<String> parts = Text.semicolons(body);
        if (parts.size() < 4) {
            throw new IllegalArgumentException("Dialogue teleport needs world;x;y;z: " + body);
        }
        ServerLevel level = Worlds.resolve(player.getServer(), parts.get(0));
        if (level == null) {
            throw new IllegalArgumentException("Unknown dialogue world: " + parts.get(0));
        }
        double x = floatOr(parts.get(1), player.getX());
        double y = floatOr(parts.get(2), player.getY());
        double z = floatOr(parts.get(3), player.getZ());
        float yaw = parts.size() > 4 ? floatOr(parts.get(4), player.getYRot()) : player.getYRot();
        float pitch = parts.size() > 5 ? floatOr(parts.get(5), player.getXRot()) : player.getXRot();
        if (!validate) player.teleportTo(level, x, y, z, yaw, pitch);
    }

    /** {@code give_potion_effect: BLINDNESS;30;5;true} - effect, seconds, amplifier, then whether particles hide. */
    private void potion(String body, ServerPlayer player, boolean validate) {
        List<String> parts = Text.semicolons(body);
        ResourceLocation id = ResourceLocation.tryParse(parts.get(0).trim().toLowerCase(Locale.ROOT).indexOf(':') >= 0
                ? parts.get(0).trim().toLowerCase(Locale.ROOT)
                : "minecraft:" + parts.get(0).trim().toLowerCase(Locale.ROOT));
        MobEffect effect = id == null ? null : BuiltInRegistries.MOB_EFFECT.getOptional(id).orElse(null);
        if (effect == null) {
            throw new IllegalArgumentException("Unknown dialogue potion effect: " + parts.get(0));
        }
        int seconds = parts.size() > 1 ? (int) floatOr(parts.get(1), 10) : 10;
        int amplifier = parts.size() > 2 ? (int) floatOr(parts.get(2), 0) : 0;
        boolean hidden = parts.size() > 3 && Boolean.parseBoolean(parts.get(3).trim());
        if (!validate) player.addEffect(new MobEffectInstance(BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect), seconds * 20,
                amplifier, false, !hidden, !hidden));
    }

    /**
     * Runs a command, intercepting the two that belonged to plugins this server does not have.
     *
     * @param asConsole
     *            true for {@code console_command}; {@code player_command_as_op} runs with the player as the source but
     *            at operator level, which is what the old plugin did
     */
    private void command(String body, ServerPlayer player, boolean asConsole, boolean validate) {
        MinecraftServer server = player.getServer();
        if (server == null)
            throw new IllegalStateException("Dialogue player has no server");
        var root = server.getCommands().getDispatcher().getRoot();
        String line = LegacyCommand.normalize(body, name -> root.getChild(name) != null);
        String[] parts = line.split("\\s+");
        String head = parts[0].toLowerCase(Locale.ROOT);
        if (head.equals("si") && parts.length >= 3 && parts[1].equalsIgnoreCase("give")) {
            giveSavedItem(parts, player, validate);
            return;
        }
        if (head.equals("eco") || head.equals("balance") || head.equals("money")) {
            if (economy.handle(parts, player, validate))
                return;
        }
        // A missing bridge must fail preflight before another action consumes payment.
        String rewritten = CommandAliases.rewrite(line, player.getGameProfile().getName());
        if (rewritten.isBlank()) {
            throw new IllegalStateException("Dialogue command has an empty alias: " + line);
        }
        if (!rewritten.equals(line)) {
            line = LegacyCommand.normalize(rewritten, name -> root.getChild(name) != null);
            parts = line.split("\\s+");
        }
        if (parts[0].equals("shop")) {
            var shopRoot = root.getChild("shop");
            boolean nativeSubcommand = parts.length > 1 && shopRoot != null
                    && shopRoot.getChild(parts[1]) instanceof com.mojang.brigadier.tree.LiteralCommandNode<?>;
            var shopId = LegacyCommand.singleArgument(line);
            if (!nativeSubcommand && shopId.isPresent()) {
                if (asConsole)
                    throw new IllegalArgumentException("Opening a system shop requires a player command source");
                economy.systemShop(shopId.get(), player, validate);
                return;
            }
        }
        CommandSourceStack source = asConsole ? server.createCommandSourceStack()
                : player.createCommandSourceStack().withPermission(4);
        if (server.getCommands().getDispatcher().getRoot().getChild(parts[0]) == null) {
            throw new IllegalArgumentException("Unknown dialogue command: " + parts[0]);
        }
        var parsed = server.getCommands().getDispatcher().parse(line, source);
        var error = net.minecraft.commands.Commands.getParseException(parsed);
        if (error != null)
            throw new IllegalArgumentException("Invalid dialogue command: " + line, error);
        if (com.mojang.brigadier.context.ContextChain.tryFlatten(parsed.getContext().build(line)).isEmpty())
            throw new IllegalArgumentException("Incomplete dialogue command: " + line);
        if (parts[0].equals("cam-server") && parts.length > 1 && parts[1].equals("start"))
            CmdCamBridge.validateStart(parsed.getContext().build(line));
        if (validate) return;
        boolean[] outcome = { false, false };
        source = source.withCallback((success, result) -> {
            outcome[0] = true;
            outcome[1] |= success;
        });
        server.getCommands().performPrefixedCommand(source, line);
        if (!outcome[0] || !outcome[1])
            throw new IllegalStateException("Dialogue command did not succeed: " + line);
    }

    /** {@code si give <id> <amount> <player> silent} - hand over one of the saved items. */
    private void giveSavedItem(String[] parts, ServerPlayer player, boolean validate) {
        String id = parts[2];
        int amount = parts.length > 3 ? Integer.parseInt(parts[3]) : 1;
        if (amount <= 0)
            throw new IllegalArgumentException("Saved item amount must be positive");
        ServerPlayer recipient = parts.length > 4 ? player.getServer().getPlayerList().getPlayerByName(parts[4]) : player;
        if (recipient == null)
            throw new IllegalArgumentException("Saved item recipient is not online");
        ItemStack stack = items.get(id, amount);
        if (stack == null) {
            throw new IllegalArgumentException("Missing saved item: " + id);
        }
        if (validate) return;
        if (!recipient.getInventory().add(stack) && !stack.isEmpty()) {
            recipient.drop(stack, false);
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
