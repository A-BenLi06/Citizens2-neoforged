package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.PlayerFilter;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.trait.ClickRedirectTrait;
import net.citizensnpcs.trait.HologramTrait;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

/** Native world and virtual hologram metadata, using actual viewer admission and packet encoding. */
@EventBusSubscriber(modid = "citizens")
public final class HologramMetadataRuntimeAudit {
    private static boolean forced, done;
    private static int phase, nextTick, passed, deadline, revision = 1;
    private static NPCRegistry registry;
    private static NPC parent;
    private static HologramTrait hologram;
    private static Entity ordinary;
    private static Actor alice, bob;
    private static List<Entity> helpers = List.of(), previous = List.of();
    private static final List<Actor> actors = new ArrayList<>();
    private static final String template = "Hello <player> / <id> / <audit_value>";
    private static boolean reverseSneaking, customName;
    private static int nameRenders;
    private static Entity nameEntity;

    @SubscribeEvent public static void nameRenderer(HologramTrait.HologramRendererCreateEvent event) {
        if (customName && event.isNameRenderer() && event.getNPC() == parent)
            event.setRenderer(new StationaryNameRenderer());
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        MinecraftServer server = event.getServer(); ServerLevel level = server.overworld();
        if (!forced) { level.setChunkForced(0, 0, true); forced = true; deadline = server.getTickCount() + 1200; }
        try {
            for (Actor actor : actors) actor.pump();
            if (server.getTickCount() > deadline) throw new AssertionError("timed_out_phase_" + phase);
            if (!level.areEntitiesLoaded(0L) || !level.isPositionEntityTicking(new BlockPos(1, -60, 1))) return;
            if (server.getTickCount() < nextTick) return;
            if (phase == 1 && (!alice.watches(level, 0, 0) || !bob.watches(level, 0, 0))) return;
            nextTick = server.getTickCount() + 30;
            switch (phase++) {
                case 0 -> {
                    check(Files.isRegularFile(Path.of("hologram-metadata-audit-fixture.txt"))
                            && !CitizensAPI.getNPCRegistry().iterator().hasNext(), "isolated_empty_fixture");
                    alice = actor(server, "MetadataAlice", 6); bob = actor(server, "MetadataBob", 8);
                    registry = CitizensAPI.createNamedNPCRegistry("hologram-metadata-audit", new MemoryNPCDataStore());
                    registry.createNPC(EntityType.PIG, "Reserved identity").destroy();
                    net.citizensnpcs.api.util.Placeholders.registerNPCPlaceholder(java.util.regex.Pattern.compile("<audit_value>"), (npc, sender, input) -> Integer.toString(revision));
                    net.citizensnpcs.api.util.Placeholders.registerNPCPlaceholder(java.util.regex.Pattern.compile("<audit_empty>"), (npc, sender, input) -> sender != null && sender.getEntity() == bob.player ? "" : "Visible");
                }
                case 1 -> {
                    check(alice.chunks.contains(0L) && bob.chunks.contains(0L), "native_chunk_delivery");
                    parent = registry.createNPC(EntityType.COW, "Metadata parent");
                    parent.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, false);
                    hologram = parent.getOrAddTrait(HologramTrait.class);
                    hologram.addLine(template); hologram.addLine(template, new HologramTrait.ArmorstandRenderer());
                    hologram.addLine(template, new PacketTextRenderer());
                    clear(); check(parent.spawn(new Location(level, 4, -60, 4)), "parent_spawn");
                }
                case 2 -> {
                    helpers = List.copyOf(hologram.getHologramEntities());
                    check(helpers.size() == 3, "three_renderers_created");
                    check(level.getEntity(helpers.get(0).getId()) == helpers.get(0) && level.getEntity(helpers.get(2).getId()) == null, "world_and_virtual_transports");
                    check(net.citizensnpcs.npc.NPCRegistries.lookup(helpers.get(0)).getId() != parent.getId(), "parent_and_helper_have_distinct_ids");
                    for (Entity helper : helpers) {
                        check(alice.spawns(helper) == 1 && bob.spawns(helper) == 1, "native_pairing_once_" + helper.getType());
                        checkText(alice, helper, expected(alice), "initial_alice"); checkText(bob, helper, expected(bob), "initial_bob");
                        check(texts(alice, helper).stream().noneMatch(s -> s.contains("MetadataBob") || s.contains("<player>")), "no_foreign_or_raw_alice_text");
                    }
                    check(((net.minecraft.world.entity.Display.TextDisplay) helpers.get(0)).getEntityData().get(net.minecraft.world.entity.Display.TextDisplay.DATA_TEXT_ID).getString().contains("<player>"), "shared_entity_keeps_template");
                    var raw = new ClientboundSetEntityDataPacket(helpers.get(0).getId(), List.of(net.minecraft.network.syncher.SynchedEntityData.DataValue.create(net.minecraft.world.entity.Display.TextDisplay.DATA_TEXT_ID, Component.literal("source packet"))));
                    var a = net.citizensnpcs.util.HologramMetadata.rewrite(helpers.get(0), alice.player, raw);
                    var b = net.citizensnpcs.util.HologramMetadata.rewrite(helpers.get(0), bob.player, raw);
                    check(a != b && raw.packedItems().getFirst().value().equals(Component.literal("source packet")), "rewrites_leave_shared_packet_immutable");
                    clear(); revision = 2;
                }
                case 3 -> {
                    for (Entity helper : helpers) { checkText(alice, helper, expected(alice), "dynamic_alice"); checkText(bob, helper, expected(bob), "dynamic_bob"); }
                    check(hologram.getLines().stream().allMatch(template::equals), "dynamic_refresh_preserves_authored_lines");
                    clear(); for (Entity helper : helpers) helper.setGlowingTag(true);
                }
                case 4 -> {
                    for (Entity helper : helpers) { checkText(alice, helper, expected(alice), "dirty_native_alice"); checkText(bob, helper, expected(bob), "dirty_native_bob"); }
                    check(helpers.stream().allMatch(e -> alice.packets.stream().anyMatch(p -> p instanceof ClientboundSetEntityDataPacket data && data.id() == e.getId() && data.packedItems().stream().anyMatch(v -> v.value() instanceof Byte))), "unrelated_metadata_survives_overlay");
                    clear(); parent.getOrAddTrait(PlayerFilter.class).addPlayer(bob.player.getUUID()); revision = 3;
                }
                case 5 -> {
                    for (Entity helper : helpers) { check(bob.removals(helper) == 1 && bob.metadata(helper) == 0, "hidden_viewer_gets_no_dynamic_updates"); checkText(alice, helper, expected(alice), "visible_dynamic_refresh"); }
                    check(cache().values().stream().noneMatch(v -> ((java.util.Map<?, ?>) v).containsKey(bob.player)), "unlink_releases_viewer_cache");
                    clear(); parent.removeTrait(PlayerFilter.class);
                }
                case 6 -> {
                    for (Entity helper : helpers) { check(bob.spawns(helper) == 1, "reentry_gets_fresh_spawn"); checkText(bob, helper, expected(bob), "reentry_current_value"); }
                    clear(); hologram.setLine(0, "Edited <player>"); hologram.setLine(1, "<audit_empty>");
                }
                case 7 -> {
                    checkText(alice, helpers.get(0), "Edited MetadataAlice", "live_edit_alice"); checkText(bob, helpers.get(0), "Edited MetadataBob", "live_edit_bob");
                    checkText(alice, helpers.get(1), "Visible", "nonempty_viewer_name"); checkText(bob, helpers.get(1), "", "empty_viewer_name");
                    check(bob.packets.stream().anyMatch(p -> p instanceof ClientboundSetEntityDataPacket data && data.id() == helpers.get(1).getId() && data.packedItems().stream().anyMatch(v -> v.id() == Entity.DATA_CUSTOM_NAME_VISIBLE.id() && Boolean.FALSE.equals(v.value()))), "empty_viewer_name_is_hidden");
                    check(helpers.get(1).getCustomName().getString().equals("Visible"), "empty_viewer_does_not_change_shared_name");
                    ordinary = EntityType.TEXT_DISPLAY.create(level); ordinary.setPos(4, -58, 6);
                    ((net.minecraft.world.entity.Display.TextDisplay) ordinary).setText(Component.literal("vanilla control"));
                    clear(); check(level.addFreshEntity(ordinary), "non_npc_control_spawn");
                }
                case 8 -> {
                    checkText(alice, ordinary, "vanilla control", "non_npc_text_unchanged_alice"); checkText(bob, ordinary, "vanilla control", "non_npc_text_unchanged_bob");
                    clear();
                }
                case 9 -> {
                    check(helpers.stream().allMatch(e -> alice.metadata(e) == 0 && bob.metadata(e) == 0), "unchanged_values_are_not_rebroadcast");
                    clear(); bob.player.setPos(100, -60, 4); revision = 4;
                    hologram.setLine(2, template);
                    check(bob.metadata(helpers.get(2)) == 0, "same_tick_range_exit_excludes_supplemental_update");
                    checkText(alice, helpers.get(2), expected(alice), "eligible_viewer_gets_supplemental_update");
                }
                case 10 -> {
                    check(bob.removals(helpers.get(2)) == 1 && bob.metadata(helpers.get(2)) == 0, "departed_packet_viewer_receives_no_refresh");
                    clear(); bob.player.setPos(8, -60, 4);
                }
                case 11 -> {
                    check(bob.spawns(helpers.get(2)) == 1, "range_return_pairs_packet_helper");
                    checkText(bob, helpers.get(2), expected(bob), "range_return_uses_current_template_value");
                    previous = helpers; clear(); check(parent.despawn(net.citizensnpcs.api.event.DespawnReason.PENDING_RESPAWN), "parent_despawn");
                    check(previous.stream().noneMatch(cache()::containsKey), "despawn_releases_entity_cache");
                    check(parent.spawn(new Location(level, 4, -60, 4)), "parent_respawn");
                }
                case 12 -> {
                    helpers = List.copyOf(hologram.getHologramEntities());
                    check(helpers.size() == 3 && helpers.stream().noneMatch(previous::contains), "respawn_creates_new_helpers");
                    checkText(alice, helpers.get(0), "Edited MetadataAlice", "respawn_personalized_pairing");
                    checkText(bob, helpers.get(2), expected(bob), "respawn_virtual_pairing");
                    previous = helpers; parent.destroy(); parent = null;
                    check(previous.stream().noneMatch(cache()::containsKey), "destroy_releases_entity_cache");
                }
                case 13 -> {
                    customName = true;
                    parent = registry.createNPC(EntityType.COW, "Stationary name");
                    parent.data().set(NPC.Metadata.ALWAYS_USE_NAME_HOLOGRAM, true);
                    hologram = parent.getOrAddTrait(HologramTrait.class);
                    hologram.addLine("World <player>", new SneakingArmorstand(false, false));
                    hologram.addLine("Shared null-text name", new SneakingArmorstand(true, true));
                    hologram.addLine("World display <player>", new SneakingDisplay(false));
                    hologram.addLine("Packet display <player>", new SneakingDisplay(true));
                    clear(); check(parent.spawn(new Location(level, 4, -60, 4)), "sneaking_parent_spawn");
                    parent.getEntity().setNoGravity(true);
                }
                case 14 -> {
                    helpers = List.copyOf(hologram.getHologramEntities());
                    nameEntity = hologram.getNameEntity();
                    check(helpers.size() == 4 && nameEntity != null, "sneaking_world_packet_and_name_helpers");
                    for (Entity helper : helpers) {
                        check(alice.spawns(helper) == 1 && bob.spawns(helper) == 1, "sneaking_native_pairing_once");
                        checkSneaking(alice, helper, true, "initial_sneaking");
                        checkSneaking(bob, helper, false, "initial_standing");
                        check(!helper.isShiftKeyDown(), "viewer_sneaking_does_not_mutate_entity");
                    }
                    checkText(alice, helpers.get(1), "Shared null-text name", "null_override_preserves_shared_name_alice");
                    checkText(bob, helpers.get(1), "Shared null-text name", "null_override_preserves_shared_name_bob");
                    checkPacketFlags(level);
                    clear(); reverseSneaking = true; ordinary.setShiftKeyDown(true);
                }
                case 15 -> {
                    for (Entity helper : helpers) {
                        checkSneaking(alice, helper, false, "refresh_clears_sneaking");
                        checkSneaking(bob, helper, true, "refresh_enables_sneaking");
                        check(helper.getPose() == net.minecraft.world.entity.Pose.STANDING, "viewer_override_preserves_shared_pose");
                    }
                    check(texts(alice, helpers.get(1)).isEmpty() && texts(bob, helpers.get(1)).isEmpty(), "null_text_refresh_only_sends_flags");
                    checkSneaking(alice, ordinary, true, "non_npc_native_flags");
                    clear();
                    for (Entity helper : helpers) { helper.setShiftKeyDown(true); helper.setSprinting(true); }
                }
                case 16 -> {
                    for (Entity helper : helpers) {
                        checkSneaking(alice, helper, false, "native_dirty_clears_shared_sneaking_for_alice");
                        checkSneaking(bob, helper, true, "native_dirty_keeps_shared_sneaking_for_bob");
                        check(flags(alice, helper).stream().allMatch(value -> (value & 8) != 0)
                                && flags(bob, helper).stream().allMatch(value -> (value & 8) != 0), "native_dirty_preserves_sprinting_bit");
                        check(helper.isShiftKeyDown() && helper.isSprinting(), "native_shared_flags_not_rewritten");
                    }
                    clear();
                }
                case 17 -> {
                    check(helpers.stream().allMatch(e -> alice.metadata(e) == 0 && bob.metadata(e) == 0), "unchanged_sneaking_is_not_rebroadcast");
                    parent.getOrAddTrait(PlayerFilter.class).addPlayer(bob.player.getUUID());
                    reverseSneaking = false; clear();
                    for (var renderer : hologram.getHologramRenderers()) net.citizensnpcs.util.HologramMetadata.refresh(renderer);
                    check(helpers.stream().allMatch(e -> bob.metadata(e) == 0), "same_tick_hidden_viewer_gets_no_flags");
                }
                case 18 -> {
                    for (Entity helper : helpers) {
                        check(bob.metadata(helper) == 0, "hidden_viewer_gets_no_sneaking_refresh");
                        checkSneaking(alice, helper, true, "visible_viewer_sneaking_refresh");
                    }
                    check(cache().values().stream().noneMatch(v -> ((java.util.Map<?, ?>) v).containsKey(bob.player)), "hidden_sneaking_cache_released");
                    clear(); parent.removeTrait(PlayerFilter.class);
                }
                case 19 -> {
                    for (Entity helper : helpers) {
                        check(bob.spawns(helper) == 1, "sneaking_visibility_return_pairs_again");
                        checkSneaking(bob, helper, false, "sneaking_visibility_return_uses_current_value");
                    }
                    clear(); bob.player.setPos(100, -60, 4); reverseSneaking = true;
                    for (int index : List.of(1, 3)) {
                        net.citizensnpcs.util.HologramMetadata.refresh(List.copyOf(hologram.getHologramRenderers()).get(index));
                        check(bob.metadata(helpers.get(index)) == 0, "same_tick_range_exit_gets_no_sneaking_flags");
                    }
                }
                case 20 -> {
                    for (int index : List.of(1, 3)) check(bob.metadata(helpers.get(index)) == 0, "departed_packet_viewer_gets_no_sneaking_flags");
                    clear(); bob.player.setPos(8, -60, 4);
                }
                case 21 -> {
                    for (int index : List.of(1, 3)) {
                        check(bob.spawns(helpers.get(index)) == 1, "sneaking_range_return_pairs_again");
                        checkSneaking(bob, helpers.get(index), true, "sneaking_range_return_uses_current_value");
                    }
                    clear(); toggleStationaryName(true);
                }
                case 22 -> {
                    check(nameEntity.isShiftKeyDown(), "stationary_world_name_inherits_parent_sneaking");
                    checkSneaking(alice, nameEntity, true, "stationary_world_name_metadata");
                    clear(); toggleStationaryName(false);
                }
                case 23 -> {
                    check(!nameEntity.isShiftKeyDown(), "stationary_world_name_clears_parent_sneaking");
                    checkSneaking(bob, nameEntity, false, "stationary_world_name_clear_metadata");
                    net.citizensnpcs.npc.NPCRegistries.lookup(nameEntity).getOrAddTrait(net.citizensnpcs.trait.PacketNPC.class);
                    clear();
                }
                case 24 -> {
                    Entity oldName = nameEntity;
                    nameEntity = hologram.getNameEntity();
                    check(nameEntity != oldName && !nameEntity.isRemoved() && net.citizensnpcs.trait.PacketNPC.isPacketEntity(nameEntity)
                            && level.getEntity(nameEntity.getId()) == null, "stationary_name_uses_live_replacement_packet_entity");
                    clear(); toggleStationaryName(true);
                }
                case 25 -> {
                    checkSneaking(alice, nameEntity, true, "stationary_packet_name_metadata");
                    clear(); toggleStationaryName(false);
                }
                case 26 -> {
                    checkSneaking(bob, nameEntity, false, "stationary_packet_name_clear_metadata");
                    previous = new ArrayList<>(helpers); previous.add(nameEntity);
                    check(parent.despawn(net.citizensnpcs.api.event.DespawnReason.PENDING_RESPAWN), "sneaking_parent_despawn");
                    check(previous.stream().noneMatch(cache()::containsKey), "sneaking_despawn_releases_all_cache");
                    clear(); check(parent.spawn(new Location(level, 4, -60, 4)), "sneaking_parent_respawn");
                    parent.getEntity().setNoGravity(true);
                }
                case 27 -> {
                    helpers = List.copyOf(hologram.getHologramEntities());
                    check(helpers.stream().noneMatch(previous::contains), "sneaking_respawn_rebuilds_helpers");
                    for (Entity helper : helpers) {
                        checkSneaking(alice, helper, false, "sneaking_respawn_alice");
                        checkSneaking(bob, helper, true, "sneaking_respawn_bob");
                    }
                    previous = new ArrayList<>(helpers); previous.add(hologram.getNameEntity());
                    parent.destroy(); parent = null;
                    check(previous.stream().noneMatch(cache()::containsKey), "sneaking_destroy_releases_all_cache");
                    LoggerFactory.getLogger("citizens").info("[HOLOGRAMMETADATAAUDIT] COMPLETE {} checks", passed); done = true;
                }
            }
        } catch (Throwable failure) { done = true; LoggerFactory.getLogger("citizens").error("[HOLOGRAMMETADATAAUDIT] FAILED phase " + phase, failure); }
        if (done) {
            try { if (parent != null) parent.destroy(); if (ordinary != null) ordinary.discard(); for (Actor actor : actors) { server.getPlayerList().remove(actor.player); actor.channel.finishAndReleaseAll(); } }
            catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[HOLOGRAMMETADATAAUDIT] FAILED cleanup", failure); }
            server.halt(false);
        }
    }

    private static final class PacketTextRenderer extends HologramTrait.TextDisplayRenderer {
        @Override protected void configureHologram(NPC child, NPC parent) {
            super.configureHologram(child, parent); child.getOrAddTrait(net.citizensnpcs.trait.PacketNPC.class);
        }
    }
    private static boolean sneaking(ServerPlayer viewer) { return (viewer == alice.player) != reverseSneaking; }
    private static final class SneakingArmorstand extends HologramTrait.ArmorstandRenderer {
        private final boolean packet, nullText;
        SneakingArmorstand(boolean packet, boolean nullText) { this.packet = packet; this.nullText = nullText; }
        @Override protected void configureHologram(NPC child, NPC parent) {
            super.configureHologram(child, parent);
            if (packet) child.getOrAddTrait(net.citizensnpcs.trait.PacketNPC.class);
        }
        @Override public String getPerPlayerText(NPC npc, ServerPlayer viewer) { return nullText ? null : super.getPerPlayerText(npc, viewer); }
        @Override public boolean isSneaking(NPC npc, ServerPlayer viewer) { return sneaking(viewer); }
    }
    private static final class SneakingDisplay extends HologramTrait.TextDisplayRenderer {
        private final boolean packet;
        SneakingDisplay(boolean packet) { this.packet = packet; }
        @Override protected void configureHologram(NPC child, NPC parent) {
            super.configureHologram(child, parent);
            if (packet) child.getOrAddTrait(net.citizensnpcs.trait.PacketNPC.class);
        }
        @Override public boolean isSneaking(NPC npc, ServerPlayer viewer) { return sneaking(viewer); }
    }
    private static final class StationaryNameRenderer extends HologramTrait.ArmorstandVehicleRenderer {
        @Override protected void render0(NPC npc, org.joml.Vector3d offset) { nameRenders++; super.render0(npc, offset); }
    }
    private static void toggleStationaryName(boolean value) {
        var position = parent.getEntity().position(); float height = parent.getEntity().getBbHeight();
        int renders = nameRenders;
        parent.getEntity().setShiftKeyDown(value); hologram.run();
        check(nameRenders == renders && parent.getEntity().position().equals(position) && parent.getEntity().getBbHeight() == height,
                "parent_shift_changes_without_position_or_render");
    }
    private static List<Byte> flags(Actor actor, Entity entity) {
        actor.pump();
        return actor.packets.stream().filter(p -> p instanceof ClientboundSetEntityDataPacket data && data.id() == entity.getId())
                .map(p -> (ClientboundSetEntityDataPacket) p).flatMap(p -> p.packedItems().stream())
                .filter(v -> v.id() == Entity.DATA_SHARED_FLAGS_ID.id()).map(v -> (Byte) v.value()).toList();
    }
    private static void checkSneaking(Actor actor, Entity helper, boolean expected, String label) {
        var values = flags(actor, helper);
        check(!values.isEmpty() && values.stream().allMatch(v -> ((v & 2) != 0) == expected), label + "_" + helper.getType() + "_" + values);
    }
    private static void checkPacketFlags(ServerLevel level) {
        Entity helper = helpers.get(1);
        var flags = net.minecraft.network.syncher.SynchedEntityData.DataValue.create(Entity.DATA_SHARED_FLAGS_ID, (byte) 0xff);
        var name = net.minecraft.network.syncher.SynchedEntityData.DataValue.create(Entity.DATA_CUSTOM_NAME, java.util.Optional.of(Component.literal("Packet-owned name")));
        var raw = new ClientboundSetEntityDataPacket(helper.getId(), List.of(flags, name));
        var rewritten = (ClientboundSetEntityDataPacket) net.citizensnpcs.util.HologramMetadata.rewrite(helper, bob.player, raw);
        var control = EntityType.COW.create(level);
        control.getEntityData().assignValues(rewritten.packedItems());
        check(!control.isShiftKeyDown() && control.isInvisible() && control.isSprinting() && control.isSwimming(), "null_text_override_preserves_native_flag_meanings");
        check(control.getCustomName().getString().equals("Packet-owned name") && rewritten.packedItems().contains(name), "null_text_override_preserves_packet_name");
        check(raw.packedItems().equals(List.of(flags, name)) && rewritten.packedItems().size() == 2, "flag_rewrite_preserves_input_and_unique_keys");
        byte changed = (Byte) rewritten.packedItems().stream().filter(v -> v.id() == Entity.DATA_SHARED_FLAGS_ID.id()).findFirst().orElseThrow().value();
        check(Byte.toUnsignedInt(changed) == 0xfd, "incoming_flags_take_precedence_over_shared_flags");
        var unrelated = new ClientboundSetEntityDataPacket(ordinary.getId(), List.of(flags));
        check(net.citizensnpcs.util.HologramMetadata.rewrite(helper, bob.player, unrelated) == unrelated, "other_entity_metadata_is_untouched");
        check(net.citizensnpcs.util.HologramMetadata.rewrite(ordinary, bob.player, unrelated) == unrelated, "non_npc_metadata_is_untouched");
        var spawn = alice.packets.stream().filter(p -> p instanceof ClientboundAddEntityPacket add && add.getId() == helper.getId()).findFirst().orElseThrow();
        var bundle = new ClientboundBundlePacket(List.of((ClientboundAddEntityPacket) spawn));
        var projected = (ClientboundBundlePacket) net.citizensnpcs.util.HologramMetadata.rewrite(helper, alice.player, bundle);
        var packets = new ArrayList<Packet<? super ClientGamePacketListener>>(); projected.subPackets().forEach(packets::add);
        check(packets.size() == 2 && packets.getFirst() == spawn && packets.getLast() instanceof ClientboundSetEntityDataPacket,
                "default_metadata_pairing_adds_overlay_after_spawn");
        control.getEntityData().assignValues(((ClientboundSetEntityDataPacket) packets.getLast()).packedItems());
        check(control.isShiftKeyDown(), "default_metadata_pairing_delivers_sneaking");
        var original = new ArrayList<Packet<? super ClientGamePacketListener>>(); bundle.subPackets().forEach(original::add);
        check(original.equals(List.of(spawn)), "pairing_bundle_input_unchanged");
    }
    private static String expected(Actor actor) { return "Hello " + actor.player.getGameProfile().getName() + " / " + parent.getId() + " / " + revision; }
    private static java.util.Map<?, ?> cache() throws ReflectiveOperationException { var field = net.citizensnpcs.util.HologramMetadata.class.getDeclaredField("sent"); field.setAccessible(true); return (java.util.Map<?, ?>) field.get(null); }
    private static List<String> texts(Actor actor, Entity entity) {
        actor.pump(); int id = entity instanceof net.minecraft.world.entity.Display.TextDisplay ? net.minecraft.world.entity.Display.TextDisplay.DATA_TEXT_ID.id() : Entity.DATA_CUSTOM_NAME.id();
        return actor.packets.stream().filter(p -> p instanceof ClientboundSetEntityDataPacket data && data.id() == entity.getId()).map(p -> (ClientboundSetEntityDataPacket) p)
                .flatMap(p -> p.packedItems().stream()).filter(v -> v.id() == id).map(v -> v.value() instanceof Component c ? c.getString() : ((java.util.Optional<Component>) v.value()).map(Component::getString).orElse("")).toList();
    }
    private static void checkText(Actor actor, Entity entity, String expected, String label) { var values = texts(actor, entity); check(!values.isEmpty() && values.stream().allMatch(expected::equals), label + "_" + entity.getType() + "_" + values); }
    private static void clear() { for (Actor actor : actors) { actor.pump(); actor.packets.clear(); } }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); passed++; LoggerFactory.getLogger("citizens").info("[HOLOGRAMMETADATAAUDIT] PASS {}", label); }
    private static Actor actor(MinecraftServer server, String name, double x) {
        var defaults = ClientInformation.createDefault();
        var information = new ClientInformation(defaults.language(), 6, defaults.chatVisibility(), defaults.chatColors(),
                defaults.modelCustomisation(), defaults.mainHand(), defaults.textFilteringEnabled(), defaults.allowsListing());
        Actor actor = new Actor(); actor.player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), name), information);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        actor.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                connection.configurePacketHandler(channel.pipeline());
                channel.pipeline().addLast("hologram-metadata-capture", new ChannelOutboundHandlerAdapter() {
                    @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                        if (message instanceof Packet<?> packet) actor.capture(packet);
                        super.write(context, message, promise);
                    }
                });
            }
        });
        NetworkRegistry.configureMockConnection(connection);
        var cookie = new CommonListenerCookie(actor.player.getGameProfile(), 0, information, false, ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
        server.getPlayerList().placeNewPlayer(connection, actor.player, cookie);
        actor.player.setPos(x, -60, 4); actors.add(actor); return actor;
    }

    private static final class Actor {
        ServerPlayer player; EmbeddedChannel channel; int pendingBatches;
        final List<Packet<?>> packets = new ArrayList<>();
        final Set<Long> chunks = new HashSet<>();
        void capture(Packet<?> packet) {
            if (packet instanceof ClientboundBundlePacket bundle) { bundle.subPackets().forEach(this::capture); return; }
            packets.add(packet);
            if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) chunks.add(ChunkPos.asLong(chunk.getX(), chunk.getZ()));
            if (packet instanceof ClientboundChunkBatchFinishedPacket) pendingBatches++;
        }
        void pump() {
            channel.runPendingTasks();
            while (pendingBatches > 0) { pendingBatches--; player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(16)); }
            Object output; while ((output = channel.readOutbound()) != null) ReferenceCountUtil.release(output);
        }
        boolean watches(ServerLevel level, int x, int z) throws ReflectiveOperationException {
            var method = net.minecraft.server.level.ChunkMap.class.getDeclaredMethod("isChunkTracked", ServerPlayer.class, int.class, int.class);
            method.setAccessible(true);
            return (boolean) method.invoke(level.getChunkSource().chunkMap, player, x, z);
        }
        long spawns(Entity e) { pump(); return packets.stream().filter(p -> p instanceof ClientboundAddEntityPacket add && add.getId() == e.getId()).count(); }
        long removals(Entity e) { pump(); return packets.stream().filter(p -> p instanceof ClientboundRemoveEntitiesPacket remove && remove.getEntityIds().contains(e.getId())).count(); }
        long metadata(Entity e) { pump(); return packets.stream().filter(p -> p instanceof ClientboundSetEntityDataPacket data && data.id() == e.getId()).count(); }
        long equipment(Entity e) { pump(); return packets.stream().filter(p -> p instanceof ClientboundSetEquipmentPacket equipment && equipment.getEntity() == e.getId()).count(); }
        long profiles(NPC n) { pump(); return packets.stream().filter(p -> p instanceof ClientboundPlayerInfoUpdatePacket info && info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER) && info.entries().stream().anyMatch(e -> e.profileId().equals(n.getEntity().getUUID()))).count(); }
        long profileRemovals(NPC n) { pump(); return packets.stream().filter(p -> p instanceof ClientboundPlayerInfoRemovePacket remove && remove.profileIds().contains(n.getEntity().getUUID())).count(); }
        boolean profileBeforeSpawn(NPC n) {
            pump(); boolean profile = false;
            for (Packet<?> packet : packets) {
                if (packet instanceof ClientboundPlayerInfoUpdatePacket info && info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)
                        && info.entries().stream().anyMatch(e -> e.profileId().equals(n.getEntity().getUUID()))) profile = true;
                if (packet instanceof ClientboundAddEntityPacket add && add.getId() == n.getEntity().getId()) return profile;
            }
            return false;
        }
    }
}
