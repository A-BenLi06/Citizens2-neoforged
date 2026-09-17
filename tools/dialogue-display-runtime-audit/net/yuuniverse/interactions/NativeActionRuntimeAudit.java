package net.yuuniverse.interactions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.netty.buffer.Unpooled;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.phys.AABB;
import org.slf4j.LoggerFactory;

/** Native action checks share the connected-player fixture, without joining the production source set. */
final class NativeActionRuntimeAudit {
    private static int passed;
    private static final Component NPC = Component.literal("Action speaker");
    private static final String PAYMENT = "remove_item: %checkitem_remove_mat:minecraft:paper,amt:1%";

    static void run(DialogueDisplayRuntimeAudit.AuditPlayer alice, DialogueDisplayRuntimeAudit.AuditPlayer bob) throws Exception {
        var library = new ItemLibrary();
        var file = Path.of("config/action-items.yml");
        Files.writeString(file, "audit:\n  item:\n    type: DIAMOND\n");
        library.load(file.toFile(), alice.registryAccess());
        var actions = new Actions(library, new Economy());
        try {
            effects(actions, alice);
            presentation(actions, alice, bob);
            commands(actions, alice);
            fireworks(actions, alice);
            invalidActions(actions, alice);
            LoggerFactory.getLogger("interactions").info("[NATIVEACTIONAUDIT] COMPLETE {} checks", passed);
        } finally {
            alice.removeAllEffects(); alice.getInventory().clearContent();
            alice.teleportTo(alice.getServer().overworld(), 1, -60, 1, 0, 0);
            alice.clear(); bob.clear();
        }
    }

    private static void effects(Actions actions, DialogueDisplayRuntimeAudit.AuditPlayer player) {
        player.removeAllEffects();
        check(actions.validateAll(List.of("give_potion_effect: BLINDNESS;30;5;true"), player, NPC)
                && !player.hasEffect(MobEffects.BLINDNESS), "effect_preflight_does_not_apply");
        check(actions.runAll(List.of("give_potion_effect: BLINDNESS;30;5;true"), player, NPC), "potion_action_executes");
        var effect = player.getEffect(MobEffects.BLINDNESS);
        check(effect.getDuration() == 30 && effect.getAmplifier() == 4 && !effect.isAmbient()
                && effect.isVisible() && effect.showIcon(), "potion_uses_ticks_one_based_level_particles_and_icon");
        check(actions.runAll(List.of("remove_potion_effect: blindness"), player, NPC)
                && !player.hasEffect(MobEffects.BLINDNESS), "remove_effect_resolves_legacy_name");
        check(actions.runAll(List.of("give_potion_effect: minecraft:blindness;2;1;false"), player, NPC)
                && !player.getEffect(MobEffects.BLINDNESS).isVisible() && !player.getEffect(MobEffects.BLINDNESS).showIcon(),
                "false_particles_also_hides_icon");
        player.advanceEffects();
        check(player.getEffect(MobEffects.BLINDNESS).getDuration() == 1, "potion_duration_decrements_by_one_tick");
        player.advanceEffects();
        check(!player.hasEffect(MobEffects.BLINDNESS), "potion_expires_at_configured_tick");
        check(actions.runAll(List.of("give_potion_effect: SPEED;-1;1"), player, NPC)
                && player.getEffect(MobEffects.MOVEMENT_SPEED).isInfiniteDuration()
                && player.getEffect(MobEffects.MOVEMENT_SPEED).isVisible(), "infinite_duration_and_default_particles");
        check(actions.runAll(List.of("remove_potion_effect: minecraft:speed", "remove_potion_effect: minecraft:speed"), player, NPC)
                && !player.hasEffect(MobEffects.MOVEMENT_SPEED), "remove_effect_is_idempotent");
        var aliases = Map.of("SLOW", "slowness", "FAST_DIGGING", "haste", "SLOW_DIGGING", "mining_fatigue",
                "INCREASE_DAMAGE", "strength", "HEAL", "instant_health", "HARM", "instant_damage", "JUMP", "jump_boost",
                "CONFUSION", "nausea", "DAMAGE_RESISTANCE", "resistance");
        for (var alias : aliases.entrySet()) {
            var holder = BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.withDefaultNamespace(alias.getValue())).orElseThrow();
            check(actions.runAll(List.of("give_potion_effect: " + alias.getKey() + ";50;2"), player, NPC)
                    && player.hasEffect(holder) && actions.runAll(List.of("remove_potion_effect: " + alias.getKey()), player, NPC)
                    && !player.hasEffect(holder), "legacy_effect_alias_" + alias.getKey());
        }
    }

    private static void presentation(Actions actions, DialogueDisplayRuntimeAudit.AuditPlayer alice,
            DialogueDisplayRuntimeAudit.AuditPlayer bob) {
        alice.clear(); bob.clear();
        check(actions.runAll(List.of("title: 2;8;3;none;&aSubtitle;tail"), alice, NPC), "title_action_executes");
        var timing = only(alice, ClientboundSetTitlesAnimationPacket.class);
        check(timing.getFadeIn() == 2 && timing.getStay() == 8 && timing.getFadeOut() == 3, "title_timings_are_ticks");
        check(only(alice, ClientboundSetTitleTextPacket.class).text().getString().isEmpty()
                && only(alice, ClientboundSetSubtitleTextPacket.class).text().getString().equals("Subtitle;tail"),
                "title_none_is_empty_and_subtitle_keeps_semicolons");
        check(only(alice, ClientboundSetSubtitleTextPacket.class).text().toFlatList().stream()
                .anyMatch(part -> part.getStyle().getColor() != null && part.getStyle().getColor().getValue() == 0x55ff55),
                "title_legacy_color_is_preserved");
        alice.clear();
        check(actions.runAll(List.of("title: -1;-1;-1;&6%player_name%;none"), alice, NPC)
                && only(alice, ClientboundSetTitleTextPacket.class).text().getString().equals(alice.getGameProfile().getName())
                && only(alice, ClientboundSetSubtitleTextPacket.class).text().getString().isEmpty()
                && only(alice, ClientboundSetTitlesAnimationPacket.class).getStay() == -1,
                "title_placeholders_and_previous_timing_sentinel");
        alice.clear();
        check(actions.runAll(List.of("teleport: minecraft:overworld;1.1234567890123;-60.0123456789;1.234567890123;91.25;-13.5"), alice, NPC)
                && alice.getX() == 1.1234567890123 && alice.getY() == -60.0123456789 && alice.getZ() == 1.234567890123
                && alice.getYRot() == 91.25F && alice.getXRot() == -13.5F, "teleport_preserves_double_coordinates_and_rotation");
        for (String action : List.of("playsound: BLOCK_NOTE_BLOCK_PLING;10;0.1",
                "playsound_resource_pack: auditpack:dialogue.chime;0.75;1.25")) {
            alice.clear();
            check(actions.runAll(List.of(action), alice, NPC), "sound_action_" + action.substring(0, action.indexOf(':')));
            var sound = only(alice, ClientboundSoundPacket.class);
            boolean custom = action.startsWith("playsound_resource_pack");
            check(sound.getSource() == SoundSource.MASTER
                    && sound.getSound().value().getLocation().toString().equals(custom ? "auditpack:dialogue.chime" : "minecraft:block.note_block.pling")
                    && sound.getVolume() == (custom ? 0.75F : 10F) && sound.getPitch() == (custom ? 1.25F : 0.1F)
                    && sound.getSound().unwrapKey().isEmpty() == custom, "sound_id_registry_kind_category_volume_pitch_" + custom);
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), alice.registryAccess());
            try {
                ClientboundSoundPacket.STREAM_CODEC.encode(buffer, sound);
                var decoded = ClientboundSoundPacket.STREAM_CODEC.decode(buffer);
                check(decoded.getSound().value().getLocation().equals(sound.getSound().value().getLocation())
                        && decoded.getVolume() == sound.getVolume() && decoded.getPitch() == sound.getPitch(),
                        "sound_packet_round_trip_" + custom);
            } finally { buffer.release(); }
        }
        for (String action : List.of("stopsound: BLOCK_NOTE_BLOCK_PLING", "stopsound_resource_pack: auditpack:dialogue.chime", "stopsound: all")) {
            alice.clear();
            check(actions.runAll(List.of(action), alice, NPC), "stop_action_" + action);
            var stop = only(alice, ClientboundStopSoundPacket.class);
            boolean all = action.endsWith(": all");
            check(all ? stop.getName() == null && stop.getSource() == null
                    : stop.getSource() == SoundSource.MASTER && stop.getName().toString().equals(action.contains("resource_pack")
                            ? "auditpack:dialogue.chime" : "minecraft:block.note_block.pling"), "stop_sound_id_and_category_" + action);
        }
        bob.pump();
        check(bob.packets.stream().noneMatch(packet -> packet instanceof ClientboundSoundPacket || packet instanceof ClientboundStopSoundPacket
                || packet instanceof ClientboundSetTitleTextPacket), "presentation_packets_are_private");
    }

    private static void commands(Actions actions, DialogueDisplayRuntimeAudit.AuditPlayer player) throws Exception {
        player.getInventory().clearContent();
        check(!player.createCommandSourceStack().hasPermission(2), "fixture_player_is_not_operator");
        check(actions.runAll(List.of("player_command: minecraft:help"), player, NPC), "ordinary_player_can_run_permitted_command");
        check(!actions.runAll(List.of("player_command: minecraft:give @s minecraft:diamond 1"), player, NPC)
                && player.getInventory().countItem(Items.DIAMOND) == 0, "ordinary_player_cannot_run_operator_command");
        check(!actions.runAll(List.of("player_command: si give audit 1"), player, NPC)
                && player.getInventory().countItem(Items.DIAMOND) == 0, "ordinary_player_cannot_use_privileged_saved_item_bridge");
        // A real native root is used for these names: the privileged API interception must not consume them.
        var dispatcher = player.getServer().getCommands().getDispatcher();
        var seen = new java.util.ArrayList<net.minecraft.server.level.ServerPlayer>();
        for (String root : List.of("eco", "money", "balance", "shop", "si")) {
            int before = seen.size();
            dispatcher.register(net.minecraft.commands.Commands.literal(root).then(net.minecraft.commands.Commands.argument("payload",
                    com.mojang.brigadier.arguments.StringArgumentType.greedyString()).executes(context -> {
                        seen.add(context.getSource().getPlayerOrException()); return 1;
                    })));
            check(actions.runAll(List.of("player_command: " + root + (root.equals("si") ? " give audit 1" : " audit")), player, NPC)
                    && seen.size() == before + 1 && seen.getLast() == player && player.getInventory().countItem(Items.DIAMOND) == 0,
                    "ordinary_" + root + "_reaches_native_player_dispatcher");
        }
        var aliases = CommandAliases.class.getDeclaredField("templates"); aliases.setAccessible(true);
        Object previous = aliases.get(null);
        try {
            Path file = Path.of("config/action-command-aliases.yml");
            Files.writeString(file, "action-reward: 'minecraft:give @s minecraft:diamond 1'\n");
            CommandAliases.load(file.toFile());
            check(!actions.runAll(List.of("player_command: action-reward"), player, NPC)
                    && player.getInventory().countItem(Items.DIAMOND) == 0, "alias_keeps_ordinary_player_permissions");
            check(actions.runAll(List.of("player_command_as_op: action-reward"), player, NPC)
                    && player.getInventory().countItem(Items.DIAMOND) == 1, "operator_action_keeps_player_selector_context");
        } finally { aliases.set(null, previous); }
        check(actions.runAll(List.of("console_command: si give audit 1"), player, NPC)
                && player.getInventory().countItem(Items.DIAMOND) == 2, "privileged_saved_item_bridge_is_retained");
        check(!player.createCommandSourceStack().hasPermission(2), "operator_action_does_not_promote_player");
    }

    private static void fireworks(Actions actions, DialogueDisplayRuntimeAudit.AuditPlayer player) {
        var level = player.serverLevel(); var area = new AABB(player.blockPosition()).inflate(3);
        int before = level.getEntitiesOfClass(FireworkRocketEntity.class, area).size();
        String action = "firework: colors:RED,GREEN type:BALL_LARGE fade:AQUA,ORANGE power:2";
        check(actions.validateAll(List.of(action), player, NPC) && level.getEntitiesOfClass(FireworkRocketEntity.class, area).size() == before,
                "firework_preflight_does_not_spawn");
        check(actions.runAll(List.of(action), player, NPC), "firework_action_executes");
        var rockets = level.getEntitiesOfClass(FireworkRocketEntity.class, area);
        check(rockets.size() == before + 1, "firework_is_a_world_entity");
        var rocket = rockets.getLast();
        var fireworks = rocket.getItem().get(DataComponents.FIREWORKS); var effect = fireworks.explosions().getFirst();
        check(fireworks.flightDuration() == 2 && effect.shape() == FireworkExplosion.Shape.LARGE_BALL
                && effect.colors().equals(it.unimi.dsi.fastutil.ints.IntList.of(0xff0000, 0x008000))
                && effect.fadeColors().equals(it.unimi.dsi.fastutil.ints.IntList.of(0x00ffff, 0xffa500))
                && !effect.hasTwinkle() && !effect.hasTrail(), "firework_native_explosion_matches_bukkit_colors_shape_fade_power");
        check(rocket.position().equals(player.position()) && rocket.getOwner() == null, "firework_starts_at_player_without_invented_owner");
        var position = rocket.position(); rocket.tick();
        check(rocket.getY() > position.y, "firework_uses_native_rocket_motion");
        rocket.discard();
    }

    private static void invalidActions(Actions actions, DialogueDisplayRuntimeAudit.AuditPlayer player) {
        List<String> invalid = List.of(
                "playsound: BLOCK_NOTE_BLOCK_PLING;broken;1", "playsound: BLOCK_NOTE_BLOCK_PLING;1;NaN",
                "playsound: BLOCK_NOTE_BLOCK_PLING;1;Infinity", "playsound: BLOCK_NOTE_BLOCK_PLING;1",
                "playsound: auditpack:missing;1;1", "playsound_resource_pack: INVALID KEY;1;1",
                "playsound_resource_pack: auditpack:chime;1;1e100", "playsound_resource_pack: auditpack:chime;-1;1",
                "stopsound: UNKNOWN_SOUND", "stopsound_resource_pack: INVALID KEY", "remove_potion_effect: unknown_effect",
                "give_potion_effect: SPEED;1.5;1", "give_potion_effect: SPEED;30;0", "give_potion_effect: SPEED;30;257",
                "give_potion_effect: SPEED;2147483648;1", "give_potion_effect: SPEED;30;1;hidden",
                "give_potion_effect: SPEED;30", "give_potion_effect: unknown:speed;30;1",
                "title: nope;10;10;Title;Subtitle", "title: 1;2;3", "title: 1.5;2;3;Title;Subtitle",
                "teleport: minecraft:overworld;not-a-number;-60;1;0;0", "teleport: minecraft:overworld;NaN;-60;1;0;0",
                "teleport: minecraft:overworld;1;-60;1;Infinity;0", "teleport: minecraft:overworld;1;-60;1",
                "firework: colors:RED type:BALL power:128", "firework: colors:RED type:BALL power:-1",
                "firework: colors:TYPO type:BALL", "firework: colors:RED type:TYPO", "firework: colors:RED",
                "firework: type:STAR", "firework: colors:RED type:BALL poewr:2", "firework: colors:RED, type:BALL",
                "player_command: give @s minecraft:diamond 1");
        for (String action : invalid) {
            player.getInventory().clearContent(); player.getInventory().add(new ItemStack(Items.PAPER));
            var position = player.position(); player.clear();
            check(!actions.runAll(List.of(PAYMENT, action, "player_command_as_op: give @s minecraft:diamond 1"), player, NPC)
                    && player.getInventory().countItem(Items.PAPER) == 1 && player.getInventory().countItem(Items.DIAMOND) == 0
                    && player.position().equals(position), "invalid_action_preserves_payment_" + action);
        }
        player.clear();
        check(actions.validateAll(List.of("title: 1;2;3;Title;Subtitle", "playsound: BLOCK_NOTE_BLOCK_PLING;1;1",
                "stopsound: all", "teleport: minecraft:overworld;2;-60;2;0;0"), player, NPC)
                && player.packets.isEmpty(), "presentation_preflight_emits_no_packets");
    }

    private static <T> T only(DialogueDisplayRuntimeAudit.AuditPlayer player, Class<T> type) {
        player.pump(); var found = player.packets.stream().filter(type::isInstance).map(type::cast).toList();
        if (found.size() != 1) throw new AssertionError("Expected one " + type + ", got " + found.size());
        return found.getFirst();
    }

    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        passed++; LoggerFactory.getLogger("interactions").info("[NATIVEACTIONAUDIT] PASS {}", name);
    }
}
