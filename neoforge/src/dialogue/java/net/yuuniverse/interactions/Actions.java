package net.yuuniverse.interactions;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
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
    private final List<PendingBatch> batches = new java.util.ArrayList<>();
    private final ActionBarDisplays actionBars = new ActionBarDisplays();
    private long tick;
    private boolean accepting = true;

    public Actions(ItemLibrary items, Economy economy) {
        this.items = items;
        this.economy = economy;
    }

    /** Compatibility entry point returning acceptance. Use executeAll to observe delayed completion. */
    public boolean runAll(List<String> actions, ServerPlayer player, Component npcName) {
        return executeAll(actions, player, npcName).accepted();
    }

    public ActionExecution executeAll(List<String> actions, ServerPlayer player, Component npcName) {
        var result = new ActionExecution();
        if (!accepting) { result.finish(ActionExecution.Result.CANCELLED); return result; }
        try {
            var batch = new PendingBatch(List.copyOf(actions), new ActionPlayer(player), npcName, result);
            prepare(batch.remaining(), player, npcName);
            batches.add(batch);
            batch.resume(player);
        } catch (Exception failure) {
            reportFailure(actions, player, failure);
            result.finish(ActionExecution.Result.FAILED);
        }
        batches.removeIf(batch -> !batch.result.pending());
        return result;
    }

    public boolean validateAll(List<String> actions, ServerPlayer player, Component npcName) {
        try {
            prepare(actions, player, npcName);
            return true;
        } catch (Exception failure) {
            reportFailure(actions, player, failure);
            return false;
        }
    }

    private void prepare(List<String> actions, ServerPlayer player, Component npcName) {
        if (actions.isEmpty()) return;
        var inventory = player.getInventory();
        var payments = new CheckItem.PaymentPlan(java.util.stream.IntStream.range(0, inventory.getContainerSize())
                .mapToObj(inventory::getItem).toList());
        for (String action : actions) run(action, player, npcName, true, payments);
    }

    private static void reportFailure(List<String> actions, ServerPlayer player, Exception failure) {
        LOGGER.error("Dialogue action batch failed for {}: {}", player.getUUID(), actions, failure);
        player.sendSystemMessage(Component.translatableWithFallback("interactions.action.failed",
                "This conversation could not complete an action. Please contact a server administrator."));
    }

    /** Advance once per server tick, independently of any session's dialogue timer. */
    void tick(MinecraftServer server) {
        if (!accepting) return;
        tick++;
        for (PendingBatch batch : List.copyOf(batches)) {
            if (!batch.result.pending()) continue;
            ServerPlayer player = batch.player.resolve();
            if (player == null || player.getServer() != server) batch.result.finish(ActionExecution.Result.CANCELLED);
            else if (tick >= batch.due) {
                try {
                    // Inventory, providers, permissions and placeholders may have changed while waiting.
                    prepare(batch.remaining(), player, batch.npcName);
                    batch.resume(player);
                } catch (Exception failure) {
                    reportFailure(batch.remaining(), player, failure);
                    batch.result.finish(ActionExecution.Result.FAILED);
                }
            }
        }
        batches.removeIf(batch -> !batch.result.pending());
        actionBars.tick(tick);
    }

    void cancel(ServerPlayer player) {
        for (PendingBatch batch : List.copyOf(batches))
            if (batch.player.matches(player)) batch.result.finish(ActionExecution.Result.CANCELLED);
        batches.removeIf(batch -> !batch.result.pending());
        actionBars.cancel(player);
    }

    /** Reload/shutdown are cancellation boundaries; no queued old configuration can run afterward. */
    void reset(boolean shutdown) {
        accepting = false;
        try {
            for (PendingBatch batch : List.copyOf(batches)) batch.result.finish(ActionExecution.Result.CANCELLED);
            batches.clear();
            actionBars.clear();
        } finally { accepting = !shutdown; }
    }

    ActionBarDisplays actionBars() { return actionBars; }

    private final class PendingBatch {
        final List<String> actions;
        final ActionPlayer player;
        final Component npcName;
        final ActionExecution result;
        int index;
        long due;

        PendingBatch(List<String> actions, ActionPlayer player, Component npcName, ActionExecution result) {
            this.actions = actions; this.player = player; this.npcName = npcName; this.result = result;
        }

        List<String> remaining() { return actions.subList(index, actions.size()); }

        void resume(ServerPlayer target) {
            while (result.pending() && index < actions.size()) {
                long delay = run(actions.get(index++), target, npcName, false, null);
                if (delay >= 0) { due = tick + Math.max(1, delay); return; }
            }
            if (result.pending()) result.finish(ActionExecution.Result.SUCCEEDED);
        }
    }

    /** @return wait ticks, or -1 for an immediate action */
    private long run(String raw, ServerPlayer player, Component npcName, boolean validate, CheckItem.PaymentPlan payments) {
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
            case "wait", "wait_ticks" -> {
                int delay = ActionArguments.integer(body, 0, Integer.MAX_VALUE);
                return verb.equals("wait") ? delay * 20L : delay;
            }
            case "actionbar" -> {
                var fields = ActionArguments.fields(body, 2, 2);
                Component text = Text.legacy(fields.getFirst());
                int duration = ActionArguments.integer(fields.get(1), -1, Integer.MAX_VALUE);
                if (!validate) actionBars.action(player, text, duration, tick);
            }
            case "playsound" -> playSound(body, player, validate, false);
            case "playsound_resource_pack" -> playSound(body, player, validate, true);
            case "stopsound" -> stopSound(body, player, validate, false);
            case "stopsound_resource_pack" -> stopSound(body, player, validate, true);
            case "title" -> title(body, player, validate);
            case "teleport" -> teleport(body, player, validate);
            case "give_potion_effect" -> potion(body, player, validate);
            case "remove_potion_effect" -> {
                var effect = effect(body);
                if (!validate) player.removeEffect(effect);
            }
            case "firework" -> {
                var firework = FireworkAction.parse(body);
                if (!validate) firework.spawn(player);
            }
            case "remove_item" -> {
                if (validate) payments.reserve(body);
                else if (!CheckItem.consume(body, player))
                    throw new IllegalStateException("Required items unavailable: " + body);
            }
            case "console_command" -> command(body, player, CommandActor.CONSOLE, validate);
            case "player_command_as_op" -> command(body, player, CommandActor.OPERATOR, validate);
            case "player_command" -> command(body, player, CommandActor.PLAYER, validate);
            default -> throw new IllegalArgumentException("Unknown dialogue action verb: " + verb);
        }
        return -1;
    }

    /** {@code playsound: BLOCK_NOTE_BLOCK_PLING;10;0.1} - a Bukkit Sound name, then volume and pitch. */
    private void playSound(String body, ServerPlayer player, boolean validate, boolean resourcePack) {
        List<String> parts = ActionArguments.fields(body, 3, 3);
        ResourceLocation id = requireSound(parts.getFirst(), resourcePack);
        Holder<SoundEvent> sound = resourcePack ? Holder.direct(SoundEvent.createVariableRangeEvent(id))
                : BuiltInRegistries.SOUND_EVENT.getHolder(id).orElseThrow();
        float volume = ActionArguments.floating(parts.get(1));
        float pitch = ActionArguments.floating(parts.get(2));
        if (volume < 0 || pitch < 0) throw new IllegalArgumentException("Negative dialogue sound volume or pitch");
        if (validate) return;
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                sound, SoundSource.MASTER, player.getX(), player.getY(),
                player.getZ(), volume, pitch, player.level().getRandom().nextLong()));
    }

    private void stopSound(String body, ServerPlayer player, boolean validate, boolean resourcePack) {
        boolean all = !resourcePack && body.equals("all");
        ResourceLocation id = all ? null : requireSound(body, resourcePack);
        if (!validate) player.connection.send(new ClientboundStopSoundPacket(id, all ? null : SoundSource.MASTER));
    }

    private static ResourceLocation requireSound(String name, boolean resourcePack) {
        ResourceLocation id = resourcePack ? ResourceLocation.tryParse(name.trim()) : soundId(name);
        if (name.isBlank() || id == null || !resourcePack && !BuiltInRegistries.SOUND_EVENT.containsKey(id))
            throw new IllegalArgumentException("Unknown or invalid dialogue sound: " + name);
        return id;
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
    private void title(String body, ServerPlayer player, boolean validate) {
        List<String> parts = ActionArguments.fields(body, 5, Integer.MAX_VALUE);
        int in = ActionArguments.integer(parts.get(0), -1, Integer.MAX_VALUE);
        int stay = ActionArguments.integer(parts.get(1), -1, Integer.MAX_VALUE);
        int out = ActionArguments.integer(parts.get(2), -1, Integer.MAX_VALUE);
        String header = parts.get(3);
        String sub = String.join(";", parts.subList(4, parts.size()));
        Component title = Text.legacy(header.equals("none") ? "" : header);
        Component subtitle = Text.legacy(sub.equals("none") ? "" : sub);
        if (validate) return;
        player.connection.send(new ClientboundSetTitlesAnimationPacket(in, stay, out));
        player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        player.connection.send(new ClientboundSetTitleTextPacket(title));
    }

    /** {@code teleport: uDays;1666;86;-545;90;90} - a Bukkit world name, coordinates, then yaw and pitch. */
    private void teleport(String body, ServerPlayer player, boolean validate) {
        List<String> parts = ActionArguments.fields(body, 6, 6);
        ServerLevel level = Worlds.resolve(player.getServer(), parts.get(0));
        if (level == null) {
            throw new IllegalArgumentException("Unknown dialogue world: " + parts.get(0));
        }
        double x = ActionArguments.decimal(parts.get(1));
        double y = ActionArguments.decimal(parts.get(2));
        double z = ActionArguments.decimal(parts.get(3));
        float yaw = ActionArguments.floating(parts.get(4));
        float pitch = ActionArguments.floating(parts.get(5));
        if (!validate) player.teleportTo(level, x, y, z, yaw, pitch);
    }

    /** {@code give_potion_effect: BLINDNESS;30;5;true} - effect, ticks, one-based level, optional particles. */
    private void potion(String body, ServerPlayer player, boolean validate) {
        List<String> parts = ActionArguments.fields(body, 3, 4);
        var effect = effect(parts.getFirst());
        int ticks = ActionArguments.integer(parts.get(1), -1, Integer.MAX_VALUE);
        int level = ActionArguments.integer(parts.get(2), 1, MobEffectInstance.MAX_AMPLIFIER + 1);
        boolean particles = parts.size() < 4 || ActionArguments.bool(parts.get(3));
        if (!validate) player.addEffect(new MobEffectInstance(effect, ticks, level - 1, false, particles, particles));
    }

    private static Holder<MobEffect> effect(String name) {
        String value = name.trim().toLowerCase(Locale.ROOT);
        // Names changed between Bukkit's legacy API and the native registry. Namespaced IDs remain literal.
        value = switch (value) {
            case "slow" -> "slowness";
            case "fast_digging" -> "haste";
            case "slow_digging" -> "mining_fatigue";
            case "increase_damage" -> "strength";
            case "heal" -> "instant_health";
            case "harm" -> "instant_damage";
            case "jump" -> "jump_boost";
            case "confusion" -> "nausea";
            case "damage_resistance" -> "resistance";
            default -> value;
        };
        ResourceLocation id = ResourceLocation.tryParse(value);
        MobEffect effect = id == null ? null : BuiltInRegistries.MOB_EFFECT.getOptional(id).orElse(null);
        if (name.isBlank() || effect == null) throw new IllegalArgumentException("Unknown dialogue potion effect: " + name);
        return BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect);
    }

    private enum CommandActor { CONSOLE, OPERATOR, PLAYER }

    /** Ordinary player commands use the dispatcher and its real permission checks, including after alias expansion. */
    private void command(String body, ServerPlayer player, CommandActor actor, boolean validate) {
        MinecraftServer server = player.getServer();
        if (server == null)
            throw new IllegalStateException("Dialogue player has no server");
        var root = server.getCommands().getDispatcher().getRoot();
        String line = LegacyCommand.normalize(body, name -> root.getChild(name) != null);
        String[] parts = line.split("\\s+");
        String head = parts[0].toLowerCase(Locale.ROOT);
        if (actor != CommandActor.PLAYER && head.equals("si") && parts.length >= 3 && parts[1].equalsIgnoreCase("give")) {
            giveSavedItem(parts, player, validate);
            return;
        }
        if (actor != CommandActor.PLAYER && (head.equals("eco") || head.equals("balance") || head.equals("money"))) {
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
        if (actor != CommandActor.PLAYER && parts[0].equals("shop")) {
            var shopRoot = root.getChild("shop");
            boolean nativeSubcommand = parts.length > 1 && shopRoot != null
                    && shopRoot.getChild(parts[1]) instanceof com.mojang.brigadier.tree.LiteralCommandNode<?>;
            var shopId = LegacyCommand.singleArgument(line);
            if (!nativeSubcommand && shopId.isPresent()) {
                if (actor == CommandActor.CONSOLE)
                    throw new IllegalArgumentException("Opening a system shop requires a player command source");
                economy.systemShop(shopId.get(), player, validate);
                return;
            }
        }
        CommandSourceStack source = switch (actor) {
            case CONSOLE -> server.createCommandSourceStack();
            case OPERATOR -> player.createCommandSourceStack().withPermission(4);
            case PLAYER -> player.createCommandSourceStack();
        };
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

}
