package net.citizensnpcs.audit;

import static net.citizensnpcs.audit.EntityCommandRuntimeAudit.check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.math.Transformation;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.AbstractNPC;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.trait.trait.Owner;
import net.citizensnpcs.api.trait.trait.Spawned;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.api.util.YamlStorage;
import net.citizensnpcs.trait.versioned.BossBarTrait;
import net.citizensnpcs.trait.versioned.DisplayTrait;
import net.citizensnpcs.trait.versioned.InteractionTrait;
import net.citizensnpcs.trait.versioned.ItemDisplayTrait;
import net.citizensnpcs.trait.versioned.PotionEffectsTrait;
import net.citizensnpcs.trait.versioned.TextDisplayTrait;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.neoforge.server.permission.PermissionAPI;

/** Additional probes in the isolated entity-command fixture; never included in release builds. */
public final class PresentationCommandRuntimeAudit {
    public static void run(MinecraftServer server, ServerPlayer player) throws Exception {
        State state = new State(server, player);
        try {
            state.display(); state.textAndItems(); state.interaction(); state.potions(); state.unavailablePotions();
            state.effectProviderRecovery(); state.bossbar(); state.rejections();
        } finally { state.close(); }
    }

    private static final class State {
        final MinecraftServer server;
        final ServerPlayer player;
        final CommandSourceStack source, restricted;
        final List<NPC> created = new ArrayList<>();
        final List<PermissionUtil.Attachment> permissions = new ArrayList<>();
        final List<ClientboundBossEventPacket> barPackets = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        final EmbeddedChannel channel;

        State(MinecraftServer server, ServerPlayer player) {
            this.server = server; this.player = player;
            source = player.createCommandSourceStack().withPermission(4);
            restricted = player.createCommandSourceStack().withPermission(0);
            channel = (EmbeddedChannel) player.connection.getConnection().channel;
            channel.pipeline().addLast("presentation-command-audit", new ChannelOutboundHandlerAdapter() {
                @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                    capture(message); super.write(context, message, promise);
                }
            });
        }

        void capture(Object message) {
            if (message instanceof ClientboundBundlePacket bundle) bundle.subPackets().forEach(this::capture);
            else if (message instanceof ClientboundBossEventPacket packet) barPackets.add(packet);
            else if (message instanceof ClientboundSystemChatPacket packet) messages.add(packet.content().getString());
        }

        void display() throws Exception {
            String configuration = "npc display --billboard center --brightness 12,3 --interpolation_delay -1 --interpolation_duration 7"
                    + " --height 3 --width 2 --scale 1.5,2,0.5 --offset 0.25,1.25,-0.5 --view_range 1.5"
                    + " --shadow_radius 0.75 --shadow_strength 0.4 --left_rotation 0,1,0,0 --right_rotation 0,0,1,0";
            for (EntityType<?> type : List.of(EntityType.BLOCK_DISPLAY, EntityType.ITEM_DISPLAY, EntityType.TEXT_DISPLAY)) {
                String kind = EntityType.getKey(type).getPath();
                NPC npc = create(type, kind); select(source, npc); ok(source, configuration);
                var data = snapshot(npc);
                check(data.getDouble("traits.displaytrait.leftRotation.y") == 1
                        && data.getDouble("traits.displaytrait.rightRotation.z") == 1
                        && data.getInt("traits.displaytrait.blockLight") == 12, "display_saved_transform_" + kind);
                NPC copy = npc.copy();
                check(snapshot(copy).getRaw("traits.displaytrait").equals(data.getRaw("traits.displaytrait")), "display_copied_" + kind);
                copy.destroy();
                spawn(npc, kind);
                check(displayMatches((Display) npc.getEntity()), "display_native_fields_" + kind);
                npc.despawn(); spawn(npc, kind + "_respawn");
                check(displayMatches((Display) npc.getEntity()), "display_respawn_fields_" + kind);
            }

            NPC partial = create(EntityType.BLOCK_DISPLAY, "PartialTransform"); spawn(partial, "partial_transform");
            Display entity = (Display) partial.getEntity();
            entity.setTransformation(new Transformation(new Vector3f(1, 2, 3), new Quaternionf(0, 0, 1, 0),
                    new Vector3f(2, 3, 4), new Quaternionf(0, 1, 0, 0)));
            select(source, partial); ok(source, "npc display --offset 9,8,7");
            Transformation transform = Display.createTransformation(entity.getEntityData());
            check(transform.getTranslation().equals(new Vector3f(9, 8, 7)) && transform.getScale().equals(new Vector3f(2, 3, 4))
                    && transform.getLeftRotation().equals(new Quaternionf(0, 0, 1, 0))
                    && transform.getRightRotation().equals(new Quaternionf(0, 1, 0, 0)), "partial_transform_preserves_unconfigured_native_values");
            ok(source, "npc display --leftrotation 0,0,0,1 --rightrotation 0,0,0,1 --viewrange 2 --interpolationduration 4");
            transform = Display.createTransformation(entity.getEntityData());
            check(transform.getLeftRotation().equals(new Quaternionf()) && transform.getRightRotation().equals(new Quaternionf())
                    && nativeData(entity).getFloat("view_range") == 2 && nativeData(entity).getInt("interpolation_duration") == 4,
                    "display_documented_flag_aliases_apply_rotations");
        }

        boolean displayMatches(Display entity) throws Exception {
            var data = nativeData(entity); var transform = Display.createTransformation(entity.getEntityData());
            var delay = Display.class.getDeclaredMethod("getTransformationInterpolationDelay"); delay.setAccessible(true);
            return data.getString("billboard").equals("center") && data.getFloat("height") == 3 && data.getFloat("width") == 2
                    && data.getFloat("view_range") == 1.5F && data.getFloat("shadow_radius") == 0.75F
                    && data.getFloat("shadow_strength") == 0.4F && data.getInt("interpolation_duration") == 7
                    && (int) delay.invoke(entity) == -1 && data.getCompound("brightness").getInt("block") == 12
                    && data.getCompound("brightness").getInt("sky") == 3
                    && transform.getTranslation().equals(new Vector3f(0.25F, 1.25F, -0.5F))
                    && transform.getScale().equals(new Vector3f(1.5F, 2, 0.5F))
                    && transform.getLeftRotation().equals(new Quaternionf(0, 1, 0, 0))
                    && transform.getRightRotation().equals(new Quaternionf(0, 0, 1, 0));
        }

        void textAndItems() throws Exception {
            NPC item = create(EntityType.ITEM_DISPLAY, "ItemTransform"); select(source, item);
            ok(source, "npc itemdisplay --transform THIRDPERSON_RIGHTHAND");
            NPC itemCopy = item.copy();
            check(itemCopy.getOrAddTrait(ItemDisplayTrait.class).getTransform() == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    "item_transform_legacy_name_survives_copy"); itemCopy.destroy();
            spawn(item, "item_transform");
            check(nativeData(item.getEntity()).getString("item_display").equals("thirdperson_righthand"), "item_transform_applied_live");
            ok(source, "npc itemdisplay --transform first_person_left_hand");
            check(nativeData(item.getEntity()).getString("item_display").equals("firstperson_lefthand"), "item_transform_native_name_applied_live");
            item.despawn(); spawn(item, "item_transform_respawn");
            check(nativeData(item.getEntity()).getString("item_display").equals("firstperson_lefthand"), "item_transform_survives_respawn");

            NPC text = create(EntityType.TEXT_DISPLAY, "FallbackName"); select(source, text);
            ok(source, "npc textdisplay --text \"<green>Authored<br>line\" --shadowed true --seethrough true --line_width 180 --bgcolor 17,34,51,64 --alignment right");
            NPC textCopy = text.copy();
            check(textCopy.getOrAddTrait(TextDisplayTrait.class).getText().equals("<green>Authored<br>line")
                    && textCopy.getOrAddTrait(TextDisplayTrait.class).getBackgroundColor() == 0x40112233,
                    "text_display_body_and_rgba_survive_copy"); textCopy.destroy();
            spawn(text, "text_display");
            update(text); update(text);
            Display.TextDisplay entity = (Display.TextDisplay) text.getEntity();
            check(text(entity).getString().equals("Authored\nline"), "text_display_body_survives_npc_ticks");
            check(nativeData(entity).getInt("line_width") == 180 && nativeData(entity).getInt("background") == 0x40112233
                    && entity.getFlags() == (Display.TextDisplay.FLAG_SHADOW | Display.TextDisplay.FLAG_SEE_THROUGH | Display.TextDisplay.FLAG_ALIGN_RIGHT),
                    "text_display_layout_and_flags_apply_live");
            text.setName("RenamedFallback"); update(text);
            check(text(entity).getString().equals("Authored\nline"), "npc_rename_does_not_replace_authored_display_text");
            text.data().set(NPC.Metadata.TEXT_DISPLAY_COMPONENT, Component.literal("Rendered component")); update(text);
            check(text(entity).getString().equals("Rendered component"), "hologram_component_priority_remains_intact");
            text.data().remove(NPC.Metadata.TEXT_DISPLAY_COMPONENT);
            text.despawn(); spawn(text, "text_display_respawn");
            check(text((Display.TextDisplay) text.getEntity()).getString().equals("Authored\nline"), "text_display_body_survives_respawn");
            ok(source, "npc textdisplay --text \"\" --seethrough false --shadowed false --alignment center --bgcolor 0,0,0,0"); update(text);
            check(text((Display.TextDisplay) text.getEntity()).getString().isEmpty()
                    && ((Display.TextDisplay) text.getEntity()).getFlags() == 0
                    && nativeData(text.getEntity()).getInt("background") == 0, "text_display_empty_text_transparency_and_false_flags");
        }

        void interaction() throws Exception {
            NPC npc = create(EntityType.INTERACTION, "InvisibleTarget"); select(source, npc);
            ok(source, "npc interaction --height 3.25 --width 2.5 --responsive true");
            NPC copy = npc.copy();
            check(copy.hasTrait(InteractionTrait.class) && copy.getOrAddTrait(InteractionTrait.class).getInteractionHeight() == 3.25F
                    && snapshot(copy).getBoolean("traits.interactiontrait.responsive"), "interaction_trait_registered_and_copied"); copy.destroy();
            spawn(npc, "interaction"); update(npc);
            Interaction entity = (Interaction) npc.getEntity();
            // Interaction rebuilds its actual AABB directly; Entity's cached dimensions are not its hitbox API.
            check(entity.getBoundingBox().getYsize() == 3.25 && entity.getBoundingBox().getXsize() == 2.5 && nativeData(entity).getBoolean("response"),
                    "interaction_dimensions_and_response_applied_live");
            check(!entity.hasCustomName(), "interaction_name_stays_suppressed_after_npc_tick");
            ok(source, "npc interaction --height 1.5 --width 1.25 --responsive false");
            check(entity.getBoundingBox().getYsize() == 1.5 && entity.getBoundingBox().getXsize() == 1.25 && !nativeData(entity).getBoolean("response"),
                    "interaction_updates_resize_hitbox_and_clear_response");
            npc.despawn(); spawn(npc, "interaction_respawn");
            check(npc.getEntity().getBoundingBox().getXsize() == 1.25 && !nativeData(npc.getEntity()).getBoolean("response")
                    && !npc.getEntity().hasCustomName(), "interaction_settings_survive_respawn");
        }

        void potions() throws Exception {
            var customType = BuiltInRegistries.MOB_EFFECT.getHolder(EntityCommandRegistryRuntimeAudit.id("effect")).orElseThrow();
            NPC npc = create(EntityType.PIG, "EffectTarget"); select(source, npc);
            ok(source, "npc potioneffect add --name effect.saved --type " + EntityCommandRegistryRuntimeAudit.id("effect")
                    + " --duration 200 --amplifier 2 --ambient true --particles true --icon true");
            var stored = npc.getOrAddTrait(PotionEffectsTrait.class).getPersistentEffects().get("effect.saved");
            check(stored != null && stored.getEffect().equals(customType) && stored.getDuration() == 200 && stored.getAmplifier() == 2,
                    "potion_custom_effect_registered_and_stored");
            NPC copy = npc.copy();
            var copied = copy.getOrAddTrait(PotionEffectsTrait.class).getPersistentEffects().get("effect.saved");
            check(copied != null && copied.getEffect().equals(customType) && copied.isAmbient() && copied.isVisible() && copied.showIcon(),
                    "potion_custom_registry_id_flags_and_dotted_name_survive_copy"); copy.destroy();
            spawn(npc, "potion");
            LivingEntity entity = (LivingEntity) npc.getEntity();
            var live = entity.getEffect(customType);
            check(live != null && live.getDuration() == 200 && live.isAmbient() && live.isVisible() && live.showIcon(),
                    "persistent_potion_applied_live");
            for (int i = 0; i < 5; i++) live.tick(entity, () -> { });
            check(live.getDuration() == 195 && stored.getDuration() == 200, "live_potion_duration_does_not_drain_persisted_template");
            ok(source, "npc potioneffect add -t --type speed --duration 123 --amplifier 0"); update(npc);
            check(entity.hasEffect(MobEffects.MOVEMENT_SPEED) && entity.getEffect(MobEffects.MOVEMENT_SPEED).getDuration() == 123,
                    "temporary_potion_applied_on_trait_tick");
            ok(source, "npc potioneffect list");
            npc.despawn(); spawn(npc, "potion_respawn"); entity = (LivingEntity) npc.getEntity();
            check(entity.getEffect(customType).getDuration() == 200 && !entity.hasEffect(MobEffects.MOVEMENT_SPEED),
                    "respawn_reapplies_persistent_but_not_temporary_potions");
            ok(source, "npc potioneffect remove --name effect.saved");
            check(npc.getOrAddTrait(PotionEffectsTrait.class).getPersistentEffects().isEmpty() && entity.hasEffect(customType),
                    "potion_remove_preserves_upstream_current_effect_lifetime");
            npc.despawn(); spawn(npc, "removed_potion_respawn");
            check(!((LivingEntity) npc.getEntity()).hasEffect(customType), "removed_potion_is_not_reapplied");
            ok(source, "npc potioneffect add --name default --type luck");
            stored = npc.getOrAddTrait(PotionEffectsTrait.class).getPersistentEffects().get("default");
            check(stored.isInfiniteDuration() && stored.getAmplifier() == 1 && !stored.isVisible() && !stored.showIcon() && !stored.isAmbient(),
                    "potion_legacy_default_duration_amplifier_and_flags");
            ok(source, "npc potioneffect add --name infinite --type speed --duration 100 -i");
            check(npc.getOrAddTrait(PotionEffectsTrait.class).getPersistentEffects().get("infinite").isInfiniteDuration(),
                    "potion_infinite_flag_overrides_duration");
            var suggestions = server.getCommands().getDispatcher().getCompletionSuggestions(server.getCommands().getDispatcher()
                    .parse("npc potioneffect add --type citizens_entity_audit:", source)).get().getList();
            check(suggestions.stream().anyMatch(value -> value.getText().contains(EntityCommandRegistryRuntimeAudit.id("effect").toString())),
                    "potion_custom_registry_completion_keeps_namespace");
        }

        Map<String, Object> effectData(String type) {
            return new LinkedHashMap<>(Map.of("type", type, "duration", 700, "amplifier", 3,
                    "ambient", true, "particles", false, "icon", true));
        }

        @SuppressWarnings("unchecked")
        void unavailablePotions() throws Exception {
            String missing = "missing_provider:effect";
            Map<String, Object> opaque = effectData(missing);
            var extension = new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("literal.key", "preserved"))));
            opaque.put("extension", extension);
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("missing.效果", opaque); raw.put("invalid.id", effectData("Invalid: Effect ID"));
            raw.put("malformed", "unparsed record"); raw.put("known", effectData("minecraft:luck"));
            MemoryDataKey input = new MemoryDataKey(new LinkedHashMap<>(Map.of("persistent", raw)));
            NPC npc = create(EntityType.PIG, "UnavailableEffects");
            PotionEffectsTrait trait = npc.getOrAddTrait(PotionEffectsTrait.class);
            check(PersistenceLoader.load(trait, input) == trait, "unavailable_effect_does_not_fail_trait_load");
            check(trait.getPersistentEffects().size() == 1 && trait.getUnresolvedEffectTypes().size() == 3
                    && missing.equals(trait.getUnresolvedEffectTypes().get("missing.效果")), "unavailable_effects_keep_names_and_ids_separate_from_native_instances");
            MemoryDataKey before = snapshot(npc);
            check(before.getRaw("traits.potioneffects.persistent").equals(raw), "unavailable_effect_raw_definitions_survive_snapshot");
            extension.getFirst().put("literal.key", "changed by caller"); opaque.put("duration", 1);
            check(snapshot(npc).getRaw("traits.potioneffects.persistent").equals(before.getRaw("traits.potioneffects.persistent")),
                    "unavailable_effect_definitions_are_deep_copied_on_load");
            ((Map<String, Object>) ((Map<?, ?>) before.getRaw("traits.potioneffects.persistent")).get("missing.效果")).put("duration", 2);
            check(((Map<?, ?>) ((Map<?, ?>) snapshot(npc).getRaw("traits.potioneffects.persistent")).get("missing.效果")).get("duration").equals(700),
                    "snapshot_edit_cannot_mutate_retained_effect_definition");
            NPC copy = npc.copy();
            check(snapshot(copy).getRaw("traits.potioneffects").equals(snapshot(npc).getRaw("traits.potioneffects")),
                    "unavailable_effects_survive_npc_copy"); copy.destroy();
            YamlStorage disk = new YamlStorage(new java.io.File("effect-audit/unknown-effects.yml"));
            npc.saveSnapshot(disk.getKey("npc")); disk.save();
            YamlStorage reload = new YamlStorage(new java.io.File("effect-audit/unknown-effects.yml"));
            check(reload.load(), "unavailable_effect_file_reloads");
            NPC restored = create(EntityType.PIG, "RestoredEffects"); restored.load(reload.getKey("npc"));
            // YamlStorage represents lists as indexed maps internally; compare with the storage's loaded form.
            check(snapshot(restored).getRaw("traits.potioneffects").equals(reload.getKey("npc").getRaw("traits.potioneffects")),
                    "unavailable_effects_survive_actual_yaml_and_npc_load");
            YamlStorage resaved = new YamlStorage(new java.io.File("effect-audit/unknown-effects-resaved.yml"));
            restored.saveSnapshot(resaved.getKey("npc")); resaved.save();
            YamlStorage documentBefore = new YamlStorage(new java.io.File("effect-audit/unknown-effects.yml"), null, false);
            YamlStorage documentAfter = new YamlStorage(new java.io.File("effect-audit/unknown-effects-resaved.yml"), null, false);
            check(documentBefore.load() && documentAfter.load()
                    && documentBefore.getKey("npc").getRaw("traits.potioneffects").equals(documentAfter.getKey("npc").getRaw("traits.potioneffects")),
                    "unavailable_effect_yaml_keeps_nested_lists_and_literal_keys_after_resave");
            spawn(restored, "unavailable_potions");
            check(((LivingEntity) restored.getEntity()).getActiveEffects().size() == 1
                    && ((LivingEntity) restored.getEntity()).hasEffect(MobEffects.LUCK), "unavailable_effects_do_not_apply_a_fallback_effect");
            restored.despawn(); spawn(restored, "unavailable_potions_respawn");
            check(restored.getOrAddTrait(PotionEffectsTrait.class).getUnresolvedEffectTypes().size() == 3,
                    "unavailable_definitions_survive_spawn_and_respawn");
            select(source, npc); channel.runPendingTasks(); messages.clear(); ok(source, "npc potioneffect list"); channel.runPendingTasks();
            check(messages.stream().anyMatch(text -> text.contains("missing.效果") && text.contains(missing))
                    && messages.stream().anyMatch(text -> text.contains("invalid.id")), "potion_list_reports_retained_unavailable_names_and_ids");
            var unchanged = snapshot(npc).getRaw("traits.potioneffects");
            bad(source, "npc potioneffect add --name missing.效果 --type missing_provider:effect");
            check(snapshot(npc).getRaw("traits.potioneffects").equals(unchanged), "invalid_replacement_preserves_unavailable_definition");
            ok(source, "npc potioneffect remove --name invalid.id");
            check(!trait.hasPersistentEffect("invalid.id") && trait.hasPersistentEffect("missing.效果"), "remove_command_can_delete_unavailable_definition");
            ok(source, "npc potioneffect add --name missing.效果 --type speed --duration 99");
            check(!trait.getUnresolvedEffectTypes().containsKey("missing.效果")
                    && trait.getPersistentEffects().get("missing.效果").getDuration() == 99, "add_command_replaces_unavailable_definition");
            trait.getPersistentEffects().compute("malformed", (name, old) -> new MobEffectInstance(MobEffects.LUCK, 20));
            check(!trait.getUnresolvedEffectTypes().containsKey("malformed"), "native_map_api_replacement_clears_unavailable_definition");
            trait.getPersistentEffects().clear(); trait.removePersistentEffect("known");
            MemoryDataKey cleared = snapshot(npc);
            check(!cleared.keyExists("traits.potioneffects.persistent"), "removed_effects_do_not_resurrect_on_save");
            PersistenceLoader.load(trait, input); PersistenceLoader.load(trait, new MemoryDataKey());
            check(trait.getPersistentEffects().isEmpty() && trait.getUnresolvedEffectTypes().isEmpty(), "reload_replaces_previous_effect_state");
        }

        void effectProviderRecovery() {
            var id = EntityCommandRegistryRuntimeAudit.id("returning_effect");
            boolean available = Boolean.getBoolean("citizens.audit.effectProvider");
            check(BuiltInRegistries.MOB_EFFECT.containsKey(id) == available, "effect_provider_mode_matches_native_registry");
            YamlStorage disk = new YamlStorage(new java.io.File("effect-audit/provider-recovery.yml"));
            NPC npc = create(EntityType.PIG, "ReturningEffect");
            PotionEffectsTrait trait = npc.getOrAddTrait(PotionEffectsTrait.class);
            if (!available) {
                var data = new MemoryDataKey(new LinkedHashMap<>(Map.of("persistent", Map.of("returning.effect", effectData(id.toString())))));
                PersistenceLoader.load(trait, data);
                check(trait.getUnresolvedEffectTypes().containsKey("returning.effect"), "absent_provider_definition_retained_before_restart");
                npc.saveSnapshot(disk.getKey("npc")); disk.save();
                check(disk.load(), "absent_provider_definition_written_for_restart");
            } else {
                check(disk.load() && disk.getKey("npc.traits.potioneffects.persistent").hasSubKeys(), "previous_process_saved_provider_definition_exists");
                npc.load(disk.getKey("npc")); trait = npc.getOrAddTrait(PotionEffectsTrait.class);
                var effect = trait.getPersistentEffects().get("returning.effect");
                check(effect != null && effect.getEffect().unwrapKey().orElseThrow().location().equals(id)
                        && effect.getDuration() == 700 && effect.getAmplifier() == 3 && effect.isAmbient() && !effect.isVisible() && effect.showIcon(),
                        "returning_provider_restores_exact_saved_id_parameters_and_flags");
                check(trait.getUnresolvedEffectTypes().isEmpty(), "returning_provider_definition_resolves_without_stale_unavailable_record");
                spawn(npc, "returning_provider_effect");
                var live = ((LivingEntity) npc.getEntity()).getEffect(effect.getEffect());
                check(live != null && live.getDuration() == 700 && live.getAmplifier() == 3,
                        "returning_provider_effect_applies_to_native_entity");
                npc.despawn(); spawn(npc, "returning_provider_effect_respawn");
                check(((LivingEntity) npc.getEntity()).getEffect(effect.getEffect()).getDuration() == 700,
                        "returning_provider_effect_retains_template_on_respawn");
            }
        }

        void bossbar() throws Exception {
            NPC npc = create(EntityType.PIG, "BarTarget"); select(source, npc);
            ok(source, "npc bossbar --style segmented_10 --color blue --title \"<red>Guard\" --track health --range 8"
                    + " --flags darken_sky,create_fog --viewpermission presentationaudit.view");
            NPC copy = npc.copy();
            var copyTrait = copy.getOrAddTrait(BossBarTrait.class);
            check(copyTrait.getStyle() == BossEvent.BossBarOverlay.NOTCHED_10 && copyTrait.getFlags().size() == 2
                    && copyTrait.getViewPermission().equals("presentationaudit.view") && copyTrait.getRange() == 8,
                    "bossbar_configuration_survives_copy"); copy.destroy();
            check(npc.spawn(new Location(server.overworld(), 3, -60, 1)), "spawn_bossbar");
            ((LivingEntity) npc.getEntity()).setHealth(4); update(npc);
            BossBarTrait trait = npc.getOrAddTrait(BossBarTrait.class); ServerBossEvent bar = bar(trait);
            check(bar.getName().getString().equals("Guard") && bar.getColor() == BossEvent.BossBarColor.BLUE
                    && bar.getOverlay() == BossEvent.BossBarOverlay.NOTCHED_10 && bar.shouldDarkenScreen() && bar.shouldCreateWorldFog()
                    && !bar.shouldPlayBossMusic() && Math.abs(bar.getProgress() - 4 / ((LivingEntity) npc.getEntity()).getMaxHealth()) < 0.0001,
                    "bossbar_live_style_title_flags_and_health");
            check(!bar.getPlayers().contains(player), "bossbar_view_permission_required");
            var viewGrant = PermissionUtil.grantTemporary(player, List.of("presentationaudit.view")); permissions.add(viewGrant);
            trait.run(); channel.runPendingTasks();
            check(bar.getPlayers().contains(player) && !barPackets.isEmpty(), "bossbar_grant_adds_viewer_and_sends_packet");
            barPackets.clear(); trait.run(); channel.runPendingTasks();
            check(barPackets.isEmpty(), "unchanged_bossbar_does_not_remove_and_readd_viewer");
            ok(source, "npc bossbar --track NaN --visible false");
            check(!bar.isVisible() && Float.isFinite(bar.getProgress()), "invalid_bossbar_progress_does_not_block_visibility");
            ok(source, "npc bossbar --visible true --track 50");
            check(bar.getProgress() == 0.5F, "bossbar_percentage_progress");
            ok(source, "npc bossbar --track unresolved-placeholder"); viewGrant.remove(); trait.run();
            check(bar.getPlayers().isEmpty(), "invalid_bossbar_tracking_does_not_block_permission_revocation");
            ok(source, "npc bossbar --viewpermission \"\" --flags \"\" --track 0.5");
            check(bar.getPlayers().contains(player) && !bar.shouldDarkenScreen() && !bar.shouldCreateWorldFog(),
                    "bossbar_empty_permission_and_flags_clear_filters");
            player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 100)); trait.run();
            check(bar.getPlayers().contains(player), "invisible_viewer_can_see_bossbar"); player.removeEffect(MobEffects.INVISIBILITY);
            player.setPos(11, -60, 1); trait.run(); check(bar.getPlayers().contains(player), "bossbar_exact_range_boundary");
            player.setPos(11.01, -60, 1); trait.run(); check(!bar.getPlayers().contains(player), "bossbar_outside_range_removed");
            player.setPos(1, -60, 1); trait.run();
            permissions.add(PermissionUtil.grantTemporary(player, List.of("citizens.npc.bossbar")));
            select(restricted, npc); bad(restricted, "npc bossbar --range 12 --title Denied");
            check(trait.getRange() == 8 && trait.getTitle().equals("<red>Guard"), "bossbar_range_permission_failure_is_atomic");
            permissions.add(PermissionUtil.grantTemporary(player, List.of("citizens.npc.bossbar.range")));
            ok(restricted, "npc bossbar --range 12"); check(trait.getRange() == 12, "bossbar_range_permission_grant");
            trait.setProgressProvider(() -> Double.POSITIVE_INFINITY); trait.run();
            check(bar.getProgress() == 0.5F, "bossbar_nonfinite_progress_provider_preserves_valid_progress");
            trait.setProgressProvider(() -> 5.0); trait.run();
            check(bar.getProgress() == 1, "bossbar_progress_provider_is_bounded"); trait.setProgressProvider(null);
            npc.despawn(); check(bar.getPlayers().isEmpty() && !bar.isVisible(), "bossbar_despawn_cleans_viewers");
            check(npc.spawn(new Location(server.overworld(), 3, -60, 1)), "spawn_bossbar_after_despawn"); update(npc);
            ServerBossEvent replacement = bar(trait);
            check(replacement != bar && replacement.getPlayers().contains(player) && replacement.getName().getString().equals("Guard"),
                    "bossbar_respawn_creates_configured_bar");
            npc.removeTrait(BossBarTrait.class);
            check(replacement.getPlayers().isEmpty() && !replacement.isVisible(), "bossbar_trait_removal_cleans_viewers");

            NPC wither = create(EntityType.WITHER, "BossEntity"); spawn(wither, "wither_bar"); select(source, wither);
            ok(source, "npc bossbar --color green --style solid --title OwnedBar");
            check(((WitherBoss) wither.getEntity()).bossEvent.getName().getString().equals("OwnedBar")
                    && ((WitherBoss) wither.getEntity()).bossEvent.getColor() == BossEvent.BossBarColor.GREEN
                    && bar(wither.getOrAddTrait(BossBarTrait.class)) == null, "wither_uses_native_bar_without_duplicate");
        }

        void rejections() throws Exception {
            NPC display = create(EntityType.TEXT_DISPLAY, "RejectDisplay"); select(source, display);
            for (String command : List.of("npc display", "npc display --scale 1,2", "npc display --offset 1,2,NaN",
                    "npc display --scale 1,1,1 --brightness 16,0", "npc display --left_rotation 0,0,0,0",
                    "npc display --width -1", "npc display --interpolation_duration -1", "npc textdisplay --shadowed banana",
                    "npc textdisplay --text changed --bgcolor 300,0,0", "npc textdisplay --line_width -1")) bad(source, command);
            check(!display.hasTrait(DisplayTrait.class) && !display.hasTrait(TextDisplayTrait.class), "invalid_display_configuration_is_atomic");
            NPC interaction = create(EntityType.INTERACTION, "RejectInteraction"); select(source, interaction);
            for (String command : List.of("npc interaction --width -1", "npc interaction --height Infinity", "npc interaction --responsive maybe")) bad(source, command);
            check(!interaction.hasTrait(InteractionTrait.class), "invalid_interaction_configuration_is_atomic");
            NPC item = create(EntityType.ITEM_DISPLAY, "RejectItem"); select(source, item);
            bad(source, "npc itemdisplay --transform missing"); check(!item.hasTrait(ItemDisplayTrait.class), "invalid_item_transform_is_atomic");
            bad(source, "npc potioneffect add --name test --type speed"); check(!item.hasTrait(PotionEffectsTrait.class), "potion_requires_living_entity");
            NPC pig = create(EntityType.PIG, "RejectPotion"); select(source, pig);
            for (String command : List.of("npc potioneffect invalid --name test", "npc potioneffect add --type speed",
                    "npc potioneffect add --name test --type missing", "npc potioneffect add --name test --type speed --duration -2",
                    "npc potioneffect add --name test --type speed --amplifier 256", "npc potioneffect remove --name missing")) bad(source, command);
            ok(source, "npc potioneffect list"); check(!pig.hasTrait(PotionEffectsTrait.class), "potion_invalid_and_empty_list_do_not_attach_trait");
            for (String command : List.of("npc display --scale 1,1,1", "npc itemdisplay --transform gui", "npc textdisplay --text wrong",
                    "npc interaction --width 1")) bad(source, command);
            check(!pig.hasTrait(DisplayTrait.class) && !pig.hasTrait(ItemDisplayTrait.class) && !pig.hasTrait(TextDisplayTrait.class)
                    && !pig.hasTrait(InteractionTrait.class), "presentation_cosmetic_type_requirements");
            for (String command : List.of("npc bossbar --title changed --style invalid", "npc bossbar --color blue --flags create_fog,bogus",
                    "npc bossbar --visible banana", "npc bossbar --bogus true")) bad(source, command);
            check(!pig.hasTrait(BossBarTrait.class), "invalid_bossbar_configuration_is_atomic");
            permissions.add(PermissionUtil.grantTemporary(player, List.of("citizens.npc.interaction")));
            select(restricted, interaction); ok(restricted, "npc interaction --width 2");
            check(interaction.getOrAddTrait(InteractionTrait.class).getInteractionWidth() == 2, "non_operator_interaction_permission_grant");
            interaction.getOrAddTrait(Owner.class).setOwner(UUID.randomUUID()); bad(restricted, "npc interaction --width 3");
            check(interaction.getOrAddTrait(InteractionTrait.class).getInteractionWidth() == 2, "interaction_ownership_denial_preserves_state");
            for (String name : List.of("bossbar", "display", "itemdisplay", "textdisplay", "interaction", "potioneffect"))
                check(PermissionAPI.getRegisteredNodes().stream().anyMatch(node -> node.getNodeName().equals("citizens.npc." + name)),
                        "presentation_permission_node_registered_" + name);
        }

        NPC create(EntityType<?> type, String name) {
            NPC npc = CitizensAPI.getNPCRegistry().createNPC(type, name); created.add(npc);
            npc.getOrAddTrait(Owner.class).setOwner(player.getUUID()); npc.getOrAddTrait(Spawned.class).setSpawned(false); return npc;
        }
        void spawn(NPC npc, String name) { check(npc.spawn(new Location(server.overworld(), 61, -60, 61)), "spawn_" + name); update(npc); }
        void select(CommandSourceStack source, NPC npc) { CitizensAPI.getDefaultNPCSelector().select(source, npc); }
        void ok(CommandSourceStack source, String command) throws Exception {
            if (server.getCommands().getDispatcher().execute(command, source) <= 0) throw new AssertionError("Command failed: " + command);
        }
        void bad(CommandSourceStack source, String command) throws Exception {
            try { server.getCommands().getDispatcher().execute(command, source); }
            catch (CommandSyntaxException expected) { return; }
            throw new AssertionError("Invalid command succeeded: " + command);
        }
        Component text(Display.TextDisplay entity) { return Component.Serializer.fromJson(nativeData(entity).getString("text"), server.registryAccess()); }
        void close() {
            permissions.forEach(PermissionUtil.Attachment::remove); created.forEach(NPC::destroy);
            player.setPos(1, -60, 1); player.removeEffect(MobEffects.INVISIBILITY); channel.pipeline().remove("presentation-command-audit");
        }
    }
    private static MemoryDataKey snapshot(NPC npc) { var data = new MemoryDataKey(); npc.saveSnapshot(data); return data; }
    private static CompoundTag nativeData(Entity entity) { return entity.saveWithoutId(new CompoundTag()); }
    private static void update(NPC npc) { ((AbstractNPC) npc).update(); }
    private static ServerBossEvent bar(BossBarTrait trait) throws Exception {
        var field = BossBarTrait.class.getDeclaredField("activeBar"); field.setAccessible(true); return (ServerBossEvent) field.get(trait);
    }
}
