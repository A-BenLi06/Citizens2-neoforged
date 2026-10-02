package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.event.NPCDeathEvent;
import net.citizensnpcs.api.event.NPCDespawnEvent;
import net.citizensnpcs.api.event.NPCSpawnEvent;
import net.citizensnpcs.api.event.SpawnReason;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.persistence.LocationPersister;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.trait.Spawned;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.util.TeleportCause;
import net.citizensnpcs.npc.NPCRegistries;
import net.citizensnpcs.mixin.BlockDropCaptureAccessor;
import net.citizensnpcs.trait.DropsTrait;
import net.citizensnpcs.trait.HomeTrait;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.PacketNPC;
import net.citizensnpcs.trait.SentinelTrait;
import net.citizensnpcs.trait.SkinTrait;
import net.citizensnpcs.util.EntityPacketTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

/** Runs native death methods and observes real event, entity, packet, and scheduler boundaries. */
@EventBusSubscriber(modid = "citizens")
public final class DeathRuntimeAudit {
    private static final String REGISTRY = "death-audit-memory";
    private static final List<NPC> npcs = new ArrayList<>();
    private static final List<Entity> nativeEntities = new ArrayList<>();
    private static final Map<Entity, NPC> owners = new IdentityHashMap<>();
    private static final Map<NPC, Integer> deaths = new IdentityHashMap<>();
    private static final Map<NPC, Integer> despawns = new IdentityHashMap<>();
    private static final Map<NPC, List<SpawnReason>> spawns = new IdentityHashMap<>();
    private static final Map<NPC, List<ItemStack>> npcDrops = new IdentityHashMap<>();
    private static final Map<Entity, List<ItemEntity>> nativeDrops = new IdentityHashMap<>();
    private static final Map<Entity, Integer> nativeDropEvents = new IdentityHashMap<>();
    private static final Map<Integer, List<Runnable>> checks = new HashMap<>();
    private static Consumer<LivingDeathEvent> livingDeath;
    private static Consumer<LivingDropsEvent> livingDrops;
    private static Consumer<NPCDeathEvent> death;
    private static Consumer<NPCDespawnEvent> despawn;
    private static Consumer<NPCSpawnEvent> spawn;
    private static Consumer<EntityJoinLevelEvent> join;
    private static NPCRegistry named;
    private static Actor viewer;
    private static ServerLevel level;
    private static int passed, start = -1, deadline, sequence;
    private static boolean forced, done;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void livingDeath(LivingDeathEvent event) {
        if (livingDeath != null) livingDeath.accept(event);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void livingDrops(LivingDropsEvent event) {
        if (livingDrops != null) livingDrops.accept(event);
        nativeDropEvents.merge(event.getEntity(), 1, Integer::sum);
        nativeDrops.put(event.getEntity(), List.copyOf(event.getDrops()));
        nativeEntities.addAll(event.getDrops());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void death(NPCDeathEvent event) {
        deaths.merge(event.getNPC(), 1, Integer::sum);
        if (death != null) death.accept(event);
        npcDrops.put(event.getNPC(), event.getDrops().stream().filter(s -> s != null).map(ItemStack::copy).toList());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void despawn(NPCDespawnEvent event) {
        if (event.getReason() == DespawnReason.DEATH) despawns.merge(event.getNPC(), 1, Integer::sum);
        if (despawn != null) despawn.accept(event);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void spawn(NPCSpawnEvent event) {
        spawns.computeIfAbsent(event.getNPC(), unused -> new ArrayList<>()).add(event.getReason());
        if (spawn != null) spawn.accept(event);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void join(EntityJoinLevelEvent event) {
        if (join != null) join.accept(event);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        MinecraftServer server = event.getServer(); level = server.overworld();
        try {
            if (!forced) {
                check(Files.isRegularFile(Path.of("death-audit-fixture.txt")), "isolated_fixture_marker");
                check(!CitizensAPI.getNPCRegistry().iterator().hasNext(), "empty_default_registry");
                level.setChunkForced(0, 0, true); forced = true; deadline = server.getTickCount() + 1200;
            }
            if (viewer != null) viewer.pump();
            if (server.getTickCount() > deadline) throw new AssertionError("fixture_timed_out");
            if (!level.areEntitiesLoaded(0L) || !level.isPositionEntityTicking(new BlockPos(4, -60, 4))) return;
            if (start < 0) {
                start = server.getTickCount();
                named = CitizensAPI.createInMemoryNPCRegistry(REGISTRY);
                level.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(false, server);
                level.getGameRules().getRule(GameRules.RULE_DOMOBLOOT).set(true, server);
                viewer = actor(server);
                acceptedAndCanceled();
                drops();
                specialDrops();
                armorStandLifecycle();
                spawnCallbacks();
                timers();
                callbacks();
                vanillaControl();
                at(80, () -> check(checks.keySet().stream().noneMatch(t -> t > 80), "all_deadlines_observed"));
            }
            List<Runnable> due = checks.remove(server.getTickCount() - start);
            if (due != null) due.forEach(Runnable::run);
            if (server.getTickCount() - start >= 80 && checks.isEmpty()) {
                close(server);
                LoggerFactory.getLogger("citizens").info("[DEATHAUDIT] COMPLETE {} checks", passed);
            }
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[DEATHAUDIT] FAILED", failure);
            close(server);
        }
    }

    private static void acceptedAndCanceled() {
        for (NPCRegistry registry : List.of(CitizensAPI.getNPCRegistry(), named)) {
            for (EntityType<?> type : List.of(EntityType.COW, EntityType.PLAYER)) {
                for (boolean packet : new boolean[] { false, true }) {
                    String label = (registry == named ? "named_" : "default_") + (type == EntityType.PLAYER ? "player_" : "mob_") + packet;
                    NPC npc = npc(registry, "Accepted_" + label, type, packet);
                    LivingEntity original = (LivingEntity) npc.getEntity();
                    EntityPacketTracker tracker = packet ? npc.getTraitNullable(PacketNPC.class).getPacketTracker() : null;
                    if (packet) {
                        npc.getTraitNullable(PacketNPC.class).run();
                        check(tracker.isLinked(viewer.player), "packet_viewer_linked_" + label);
                        check(PacketNPC.getInteractionTarget(original.getId(), viewer.player) == original, "packet_interaction_live_" + label);
                    }
                    kill(npc, false);
                    released(npc, original, label);
                    check(deaths.getOrDefault(npc, 0) == 1 && despawns.getOrDefault(npc, 0) == 1, "one_death_and_despawn_" + label);
                    original.die(original.damageSources().genericKill());
                    check(deaths.getOrDefault(npc, 0) == 1, "duplicate_native_death_is_ignored_" + label);
                    if (packet) {
                        check(PacketNPC.getInteractionTarget(original.getId(), viewer.player) == null, "dead_packet_cannot_interact_" + label);
                        check(tracker.isLinked(viewer.player) && viewer.deaths(original) == 1, "packet_death_retains_existing_viewer_" + label);
                        check(viewer.removals(original) == 0, "packet_visual_not_removed_immediately_" + label);
                    }
                    at(19, () -> {
                        check(!original.isRemoved(), "corpse_retained_until_twentieth_tick_" + label);
                        if (packet) check(tracker.isLinked(viewer.player), "packet_viewer_retained_for_animation_" + label);
                    });
                    at(20, () -> {
                        check(original.isRemoved() && level.getEntity(original.getUUID()) != original, "corpse_removed_at_twenty_" + label);
                        if (packet) check(tracker.getLinked().isEmpty() && viewer.removals(original) == 1, "packet_viewer_removed_once_at_twenty_" + label);
                    });
                    at(80, () -> check(!npc.isSpawned() && npc.getEntity() == null, "default_negative_delay_stays_despawned_" + label));

                    for (boolean direct : new boolean[] { false, true }) {
                        NPC cancel = npc(registry, "Cancel_" + label + direct, type, packet);
                        LivingEntity live = (LivingEntity) cancel.getEntity();
                        livingDeath = e -> { if (e.getEntity() == live) { e.getEntity().setHealth(6); e.setCanceled(true); } };
                        kill(cancel, direct); livingDeath = null;
                        check(cancel.getEntity() == live && cancel.isSpawned() && live.isAlive() && NPCRegistries.lookup(live) == cancel,
                                "canceled_native_death_preserves_live_entity_" + label + direct);
                        check(deaths.getOrDefault(cancel, 0) == 0 && despawns.getOrDefault(cancel, 0) == 0,
                                "canceled_native_death_has_no_lifecycle_" + label + direct);
                        live.invulnerableTime = 0;
                        kill(cancel, direct); released(cancel, live, "after_cancellation_" + label + direct);
                        check(deaths.getOrDefault(cancel, 0) == 1, "later_death_after_cancellation_" + label + direct);
                    }
                }
            }
        }
    }

    private static void drops() {
        for (EntityType<?> type : List.of(EntityType.COW, EntityType.PLAYER)) {
            for (boolean packet : new boolean[] { false, true }) {
                for (int mode = 0; mode < 5; mode++) {
                    final int selected = mode;
                    String label = (type == EntityType.PLAYER ? "player_" : "mob_") + packet + "_" + mode;
                    NPC npc = npc(named, "Drops_" + label, type, packet);
                    LivingEntity entity = (LivingEntity) npc.getEntity();
                    if (entity instanceof ServerPlayer player) player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
                    else {
                        entity.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND, 3));
                        ((Mob) entity).setDropChance(EquipmentSlot.MAINHAND, 2);
                    }
                    if (mode == 1 || mode == 3) npc.data().set(NPC.Metadata.DROPS_ITEMS, true);
                    if (mode == 2) npc.getOrAddTrait(DropsTrait.class).addDrop(new ItemStack(Items.EMERALD, 2), 1);
                    DamageSource source = entity.damageSources().genericKill();
                    death = e -> {
                        if (e.getNPC() != npc) return;
                        check(e.getSource() == source, "native_damage_source_preserved_" + label);
                        if (selected == 3) {
                            e.getDrops().clear(); e.getDrops().add(new ItemStack(Items.GOLD_INGOT, 4));
                            e.getDrops().add(ItemStack.EMPTY); e.getDrops().add(null);
                        }
                    };
                    if (mode == 4) {
                        npc.getOrAddTrait(DropsTrait.class).addDrop(new ItemStack(Items.EMERALD, 2), 1);
                        livingDrops = e -> { if (e.getEntity() == entity) e.setCanceled(true); };
                    }
                    entity.hurt(source, Float.MAX_VALUE); death = null; livingDrops = null;
                    check(deaths.getOrDefault(npc, 0) == 1 && nativeDrops.containsKey(entity), "native_drops_notify_once_" + label);
                    List<ItemEntity> result = nativeDrops.get(entity);
                    if (mode == 0) check(result.isEmpty() && npcDrops.get(npc).isEmpty(), "native_drops_default_suppressed_" + label);
                    if (mode == 1) check(count(result, Items.DIAMOND) == 3, "enabled_native_inventory_drops_" + label);
                    if (mode == 2 || mode == 4) check(result.size() == 1 && count(result, Items.EMERALD) == 2, "custom_drops_survive_default_suppression_" + label);
                    if (mode == 3) check(result.size() == 1 && count(result, Items.GOLD_INGOT) == 4, "mutable_drop_replacement_and_empty_filtering_" + label);
                    for (ItemEntity item : result) {
                        check((level.getEntity(item.getUUID()) == item) == (mode != 4), "native_drop_cancellation_controls_admission_" + label);
                        item.discard();
                    }
                }
            }
        }
    }

    private static void specialDrops() {
        for (boolean packet : new boolean[] { false, true }) {
            for (boolean cancelDrops : new boolean[] { false, true }) {
                String label = packet + "_" + cancelDrops;
                NPC npc = npc(named, "ArmorStandDrops" + label, EntityType.ARMOR_STAND, packet);
                npc.getOrAddTrait(DropsTrait.class).addDrop(new ItemStack(Items.EMERALD, 2), 1);
                LivingEntity entity = (LivingEntity) npc.getEntity();
                if (cancelDrops) livingDrops = e -> { if (e.getEntity() == entity) e.setCanceled(true); };
                // ArmorStand breaks without invoking LivingEntity.die, but still publishes LivingDropsEvent.
                entity.hurt(entity.damageSources().explosion(null, null), 100);
                livingDrops = null;
                check(entity.isRemoved(), "native_armor_stand_break_" + label);
                released(npc, entity, "armor_stand_break_" + label);
                check(deaths.getOrDefault(npc, 0) != 0 && npcDrops.containsKey(npc), "special_native_drops_notify_npc_" + label);
                check(deaths.get(npc) == 1 && count(nativeDrops.get(entity), Items.EMERALD) == 2,
                        "special_native_drops_preserve_trait_additions_" + label);
                for (ItemEntity item : nativeDrops.get(entity)) {
                    check((level.getEntity(item.getUUID()) == item) == !cancelDrops,
                            "special_native_drops_respect_cancellation_" + label);
                    item.discard();
                }
            }
        }
    }

    private static void armorStandLifecycle() {
        for (boolean packet : new boolean[] { false, true }) {
            for (String route : List.of("explosion", "survival", "fire")) {
                for (boolean enabled : new boolean[] { false, true }) {
                    for (int mode = 0; mode < 4; mode++) {
                        boolean canceled = mode == 1, replaced = mode == 2, capturing = mode == 3;
                        String label = packet + "_" + route + "_" + enabled + "_" + mode;
                        NPC npc = npc(named, "StandLifecycle" + sequence, EntityType.ARMOR_STAND, packet);
                        npc.data().set(NPC.Metadata.RESPAWN_DELAY, 5);
                        npc.data().set(NPC.Metadata.DROPS_ITEMS, enabled);
                        npc.getOrAddTrait(DropsTrait.class).addDrop(new ItemStack(Items.EMERALD, 2), 1);
                        LivingEntity stand = (LivingEntity) npc.getEntity();
                        stand.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND, 3));
                        stand.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                        EntityPacketTracker tracker = packet ? npc.getTraitNullable(PacketNPC.class).getPacketTracker() : null;
                        if (packet) {
                            npc.getTraitNullable(PacketNPC.class).run();
                            check(tracker.isLinked(viewer.player), "stand_viewer_paired_" + label);
                        }
                        DamageSource damage = switch (route) {
                            case "survival" -> stand.damageSources().playerAttack(viewer.player);
                            case "fire" -> stand.damageSources().onFire();
                            default -> stand.damageSources().explosion(null, null);
                        };
                        List<ItemEntity> admitted = new ArrayList<>();
                        join = e -> { if (e.getEntity() instanceof ItemEntity item) admitted.add(item); };
                        livingDrops = e -> { if (e.getEntity() == stand && canceled) e.setCanceled(true); };
                        death = e -> {
                            if (e.getNPC() != npc) return;
                            check(e.getSource() == damage, "stand_original_damage_source_" + label);
                            check(!stand.hurt(damage, 100), "stand_recursive_hurt_is_ignored_" + label);
                            if (replaced) { e.getDrops().clear(); e.getDrops().add(new ItemStack(Items.GOLD_INGOT, 5)); }
                        };
                        List<ItemEntity> parent = capturing ? new ArrayList<>() : null;
                        check(BlockDropCaptureAccessor.citizens$getCapturedDrops() == null, "stand_initial_capture_clear_" + label);
                        BlockDropCaptureAccessor.citizens$setCapturedDrops(parent);
                        try {
                            if (route.equals("survival")) {
                                check(stand.hurt(damage, 100), "stand_first_hit_native_result_" + label);
                                check(npc.getEntity() == stand && stand.isAlive() && deaths.getOrDefault(npc, 0) == 0
                                        && admitted.isEmpty(), "stand_first_survival_hit_is_nonlethal_" + label);
                            }
                            if (route.equals("fire")) stand.setHealth(1);
                            boolean result = stand.hurt(damage, 100);
                            check(result == route.equals("survival"), "stand_native_break_return_value_" + label);
                            check(BlockDropCaptureAccessor.citizens$getCapturedDrops() == parent, "stand_restores_parent_capture_" + label);
                        } finally {
                            BlockDropCaptureAccessor.citizens$setCapturedDrops(null);
                            join = null; livingDrops = null; death = null;
                        }
                        released(npc, stand, "full_stand_" + label);
                        check(stand.isRemoved() && deaths.getOrDefault(npc, 0) == 1 && nativeDropEvents.getOrDefault(stand, 0) == 1,
                                "stand_one_death_and_complete_native_drop_event_" + label);
                        List<ItemEntity> drops = nativeDrops.get(stand);
                        check(count(drops, Items.DIAMOND) == (enabled && !replaced ? 3 : 0)
                                        && count(drops, Items.IRON_CHESTPLATE) == (enabled && !replaced ? 1 : 0)
                                        && count(drops, Items.ARMOR_STAND) == (enabled && !replaced && route.equals("survival") ? 1 : 0)
                                        && count(drops, Items.EMERALD) == (replaced ? 0 : 2)
                                        && count(drops, Items.GOLD_INGOT) == (replaced ? 5 : 0),
                                "stand_complete_mutable_loot_" + label);
                        check(admitted.size() + (parent == null ? 0 : parent.size()) == (canceled ? 0 : drops.size()),
                                "stand_no_bypass_or_duplicate_item_admission_" + label);
                        if (capturing) check(count(parent, Items.DIAMOND) == (enabled ? 3 : 0)
                                        && count(parent, Items.IRON_CHESTPLATE) == (enabled ? 1 : 0)
                                        && count(parent, Items.EMERALD) == 0,
                                "stand_native_block_items_keep_parent_destination_" + label);
                        if (packet) check(tracker.getLinked().isEmpty() && viewer.removals(stand) == 1 && viewer.deaths(stand) == 0,
                                "stand_virtual_break_removes_without_mob_death_animation_" + label);
                        for (ItemEntity item : drops) item.discard();
                        expectRespawn(npc, 25, SpawnReason.TIMED_RESPAWN, "stand_timer_" + label);
                    }
                }
            }
            for (String route : List.of("creative", "bypass", "kill")) {
                String label = packet + "_" + route;
                NPC npc = npc(named, "StandNoLoot" + sequence, EntityType.ARMOR_STAND, packet);
                npc.data().set(NPC.Metadata.RESPAWN_DELAY, 0);
                npc.data().set(NPC.Metadata.DROPS_ITEMS, true);
                npc.getOrAddTrait(DropsTrait.class).addDrop(new ItemStack(Items.EMERALD, 2), 1);
                LivingEntity stand = (LivingEntity) npc.getEntity();
                stand.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                List<ItemEntity> admitted = new ArrayList<>();
                join = e -> { if (e.getEntity() instanceof ItemEntity item) admitted.add(item); };
                try {
                    if (route.equals("creative")) {
                        viewer.player.getAbilities().instabuild = true;
                        check(stand.hurt(stand.damageSources().playerAttack(viewer.player), 100), "stand_creative_native_result_" + label);
                    } else if (route.equals("bypass")) {
                        check(!stand.hurt(stand.damageSources().genericKill(), 100), "stand_bypass_native_result_" + label);
                    } else stand.kill();
                } finally { viewer.player.getAbilities().instabuild = false; join = null; }
                released(npc, stand, "stand_no_loot_" + label);
                check(stand.isRemoved() && admitted.isEmpty() && !nativeDrops.containsKey(stand)
                                && deaths.getOrDefault(npc, 0) == 1, "stand_native_no_loot_semantics_" + label);
                stand.kill();
                check(deaths.get(npc) == 1, "stand_duplicate_kill_does_not_notify_again_" + label);
                expectRespawn(npc, 20, SpawnReason.TIMED_RESPAWN, "stand_no_loot_timer_" + label);
            }
        }
        net.minecraft.world.entity.decoration.ArmorStand vanilla = EntityType.ARMOR_STAND.create(level);
        vanilla.setPos(5, -60, 5); nativeEntities.add(vanilla); check(level.addFreshEntity(vanilla), "stand_vanilla_control_admitted");
        vanilla.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND, 3));
        List<ItemEntity> parent = new ArrayList<>();
        BlockDropCaptureAccessor.citizens$setCapturedDrops(parent);
        try { vanilla.hurt(vanilla.damageSources().explosion(null, null), 100); }
        finally { BlockDropCaptureAccessor.citizens$setCapturedDrops(null); }
        nativeEntities.addAll(parent);
        check(vanilla.isRemoved() && count(parent, Items.DIAMOND) == 3 && nativeDropEvents.getOrDefault(vanilla, 0) == 1,
                "stand_vanilla_control_preserves_native_capture_and_removal");
        for (boolean packet : new boolean[] { false, true }) {
            NPC noBlockDrops = npc(named, "StandBlockRule" + packet, EntityType.ARMOR_STAND, packet);
            noBlockDrops.data().set(NPC.Metadata.DROPS_ITEMS, true);
            noBlockDrops.getOrAddTrait(DropsTrait.class).addDrop(new ItemStack(Items.EMERALD, 2), 1);
            LivingEntity stand = (LivingEntity) noBlockDrops.getEntity();
            stand.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND, 3));
            level.getGameRules().getRule(GameRules.RULE_DOBLOCKDROPS).set(false, level.getServer());
            try { stand.hurt(stand.damageSources().explosion(null, null), 100); }
            finally { level.getGameRules().getRule(GameRules.RULE_DOBLOCKDROPS).set(true, level.getServer()); }
            check(count(nativeDrops.get(stand), Items.DIAMOND) == 0 && count(nativeDrops.get(stand), Items.EMERALD) == 2,
                    "stand_block_drop_gamerule_keeps_native_meaning_" + packet);
            for (ItemEntity item : nativeDrops.get(stand)) item.discard();
            for (boolean duringDespawn : new boolean[] { false, true }) {
                for (boolean destroy : new boolean[] { false, true }) {
                    String label = packet + "_" + duringDespawn + "_" + destroy;
                    NPC npc = npc(named, "StandCallback" + label, EntityType.ARMOR_STAND, packet);
                    npc.data().set(NPC.Metadata.RESPAWN_DELAY, 0);
                    LivingEntity old = (LivingEntity) npc.getEntity();
                    Runnable change = () -> {
                        death = null; despawn = null;
                        if (destroy) npc.destroy();
                        else {
                            npc.despawn(DespawnReason.PLUGIN);
                            npc.spawn(new Location(level, 13, -60, 13));
                        }
                    };
                    if (duringDespawn) despawn = e -> { if (e.getNPC() == npc) change.run(); };
                    else death = e -> { if (e.getNPC() == npc) change.run(); };
                    old.hurt(old.damageSources().explosion(null, null), 100);
                    death = null; despawn = null;
                    Entity result = npc.getEntity();
                    check(old.isRemoved() && (destroy ? !exists(npc) && result == null : npc.isSpawned() && result != old),
                            "stand_callback_result_survives_native_kill_" + label);
                    at(80, () -> check(npc.getEntity() == result && (destroy || npc.isSpawned()),
                            "stand_callback_cannot_be_replaced_by_old_timer_" + label));
                }
            }
        }
    }

    private static void spawnCallbacks() {
        for (EntityType<?> type : List.of(EntityType.COW, EntityType.PLAYER)) {
            for (boolean packet : new boolean[] { false, true }) {
                for (String stage : List.of("pre", "join", "event", "trait")) {
                    if (packet && stage.equals("join")) continue;
                    for (String action : List.of("destroy", "replace", "teleport")) {
                        String label = type.toShortString() + "_" + packet + "_" + stage + "_" + action;
                        NPC npc = npc(named, "SpawnCallback" + sequence, type, packet);
                        // Start this attempt from a real accepted death, including its pending respawn.
                        npc.data().set(NPC.Metadata.RESPAWN_DELAY, 5);
                        kill(npc, false);
                        SpawnCallbackTrait hook = new SpawnCallbackTrait(); npc.addTrait(hook);
                        Entity[] candidate = { null };
                        int[] callbacks = { 0, 0 };
                        Location target = new Location(level, 14, -60, 14, 30, 5);
                        Runnable[] once = { null };
                        once[0] = () -> {
                            once[0] = null;
                            candidate[0] = npc.getEntity();
                            if (action.equals("destroy")) npc.destroy();
                            else if (action.equals("replace")) {
                                npc.despawn(DespawnReason.PLUGIN);
                                npc.spawn(target, SpawnReason.PLUGIN, result -> callbacks[1]++);
                            } else npc.teleport(target, TeleportCause.PLUGIN);
                        };
                        Runnable invoke = () -> { if (once[0] != null) once[0].run(); };
                        if (stage.equals("pre")) hook.pre = invoke;
                        if (stage.equals("trait")) hook.spawned = invoke;
                        if (stage.equals("join")) join = e -> { if (e.getEntity() == npc.getEntity()) invoke.run(); };
                        if (stage.equals("event")) spawn = e -> { if (e.getNPC() == npc) invoke.run(); };
                        npc.spawn(new Location(level, 4, -60, 4), SpawnReason.PLUGIN, result -> {
                            callbacks[0]++;
                            check(result == npc.getEntity() && result.getX() == 14 && result.getZ() == 14,
                                    "spawn_completion_observes_callback_destination_" + label);
                        });
                        join = null; spawn = null;
                        Entity result = npc.getEntity();
                        check(candidate[0] != null && once[0] == null, "spawn_callback_executed_" + label);
                        check(!npc.data().has(NPC.Metadata.NPC_SPAWNING_IN_PROGRESS), "spawn_metadata_released_" + label);
                        if (action.equals("destroy")) {
                            check(!exists(npc) && result == null && candidate[0].isRemoved(), "spawn_callback_destruction_survives_" + label);
                            check(level.getEntity(candidate[0].getUUID()) != candidate[0], "destroyed_candidate_absent_from_native_index_" + label);
                            check(callbacks[0] == 0, "destroyed_spawn_does_not_complete_" + label);
                        } else {
                            check(npc.isSpawned() && result.getX() == 14 && result.getZ() == 14,
                                    "spawn_callback_destination_survives_" + label);
                            check((level.getEntity(result.getUUID()) == result) == !packet, "spawn_callback_native_index_" + label);
                            if (action.equals("replace")) {
                                check(result != candidate[0] && candidate[0].isRemoved(), "spawn_callback_retires_old_candidate_" + label);
                                check(callbacks[0] == 0 && callbacks[1] == 1, "only_replacement_spawn_completes_" + label);
                            } else check(callbacks[0] == 1 && result == candidate[0], "teleported_spawn_completes_once_" + label);
                        }
                        at(30, () -> check(npc.getEntity() == result && (result == null || !result.isRemoved()),
                                "stale_timer_preserves_spawn_callback_result_" + label));
                    }
                }
            }
        }
    }

    private static final class SpawnCallbackTrait extends Trait {
        Runnable pre, spawned;
        SpawnCallbackTrait() { super("death-audit-spawn-callback"); }
        @Override public void onPreSpawn() { if (pre != null) pre.run(); }
        @Override public void onSpawn() { if (spawned != null) spawned.run(); }
    }

    private static void timers() {
        for (int delay : new int[] { 0, 5 }) {
            NPC npc = npc(named, "CitizensDelay" + delay, EntityType.COW, false);
            npc.data().set(NPC.Metadata.RESPAWN_DELAY, delay);
            Location initial = npc.getStoredLocation().clone();
            HomeTrait home = npc.getOrAddTrait(HomeTrait.class); home.setDelayTicks(Integer.MAX_VALUE);
            home.setHomeLocation(new Location(level, 3, -60, 3));
            kill(npc, false);
            at(7, () -> home.setHomeLocation(new Location(level, 12, -60, 12, 35, 10)));
            expectRespawn(npc, delay + 20, SpawnReason.TIMED_RESPAWN, "citizens_delay_" + delay);
            at(delay + 20, () -> check(npc.getEntity().getX() == 12 && npc.getEntity().getZ() == 12
                    && !initial.equals(npc.getStoredLocation()), "respawn_reads_current_home_" + delay));
        }
        for (int delay : new int[] { -1, 0, 1, 10, 11, 20, 21 }) {
            NPC npc = npc(named, "SentinelDelay" + delay, EntityType.COW, false);
            sentinel(npc, delay, new Location(level, 11, -60, 11));
            kill(npc, false);
            if (delay < 0) {
                check(exists(npc), "negative_sentinel_not_destroyed_synchronously");
                at(1, () -> check(!exists(npc), "negative_sentinel_destroyed_after_one_tick"));
            } else if (delay == 0) {
                check(!npc.getOrAddTrait(Spawned.class).shouldSpawn(), "zero_sentinel_clears_saved_spawn_flag");
                at(80, () -> check(exists(npc) && !npc.isSpawned() && npc.getEntity() == null, "zero_sentinel_stays_registered_despawned"));
            } else {
                int due = 10 * ((delay + 9) / 10 + 1);
                expectRespawn(npc, due, SpawnReason.RESPAWN, "sentinel_delay_" + delay);
                at(due, () -> check(npc.getEntity().getX() == 11 && npc.getEntity().getZ() == 11, "sentinel_spawn_point_" + delay));
            }
        }
        NPC firstSentinel = npc(named, "SentinelWins", EntityType.COW, false);
        sentinel(firstSentinel, 1, null); firstSentinel.data().set(NPC.Metadata.RESPAWN_DELAY, 15); kill(firstSentinel, false);
        expectRespawn(firstSentinel, 20, SpawnReason.RESPAWN, "sentinel_wins_coexisting_timers");
        at(35, () -> check(spawnAttempts(firstSentinel) == 1, "later_citizens_timer_cannot_replace_live_sentinel_respawn"));

        NPC firstCitizens = npc(named, "CitizensWins", EntityType.PLAYER, false);
        sentinel(firstCitizens, 21, null); firstCitizens.data().set(NPC.Metadata.RESPAWN_DELAY, 1); kill(firstCitizens, false);
        expectRespawn(firstCitizens, 21, SpawnReason.TIMED_RESPAWN, "citizens_wins_coexisting_timers");
        at(40, () -> check(spawnAttempts(firstCitizens) == 1, "later_sentinel_timer_cannot_replace_live_citizens_respawn"));

        NPC retry = npc(named, "RejectedPacketRespawn", EntityType.PLAYER, true);
        sentinel(retry, 1, null); retry.data().set(NPC.Metadata.RESPAWN_DELAY, 15); kill(retry, false);
        spawn = e -> { if (e.getNPC() == retry && e.getReason() == SpawnReason.RESPAWN) e.setCanceled(true); };
        at(19, () -> check(!retry.isSpawned() && spawnAttempts(retry) == 0, "packet_respawn_not_early"));
        at(20, () -> check(!retry.isSpawned() && retry.getEntity() == null && spawnAttempts(retry) == 1,
                "first_packet_respawn_canceled_without_live_handle"));
        at(34, () -> check(!retry.isSpawned() && spawnAttempts(retry) == 1, "later_packet_timer_waits_its_own_deadline"));
        at(35, () -> {
            check(retry.isSpawned() && PacketNPC.isPacketEntity(retry.getEntity()) && spawnAttempts(retry) == 2,
                    "failed_packet_spawn_preserves_later_timer");
            check(spawns.get(retry).equals(List.of(SpawnReason.RESPAWN, SpawnReason.TIMED_RESPAWN)), "packet_retry_uses_each_timer_reason");
            spawn = null;
        });

        for (boolean packet : new boolean[] { false, true }) {
            NPC manual = npc(named, "ManualCancel" + packet, EntityType.COW, packet);
            sentinel(manual, 1, null); manual.data().set(NPC.Metadata.RESPAWN_DELAY, 15); kill(manual, false);
            at(2, manual::despawn);
            at(80, () -> check(exists(manual) && !manual.isSpawned() && manual.getEntity() == null && spawnAttempts(manual) == 0,
                    "manual_despawn_cancels_both_timers_" + packet));
        }
    }

    private static void callbacks() {
        NPC recursive = npc(named, "RecursiveDeath", EntityType.COW, false);
        LivingEntity original = (LivingEntity) recursive.getEntity();
        death = e -> { if (e.getNPC() == recursive) original.die(original.damageSources().genericKill()); };
        kill(recursive, false); death = null;
        check(deaths.getOrDefault(recursive, 0) == 1 && despawns.getOrDefault(recursive, 0) == 1,
                "recursive_native_death_callback_notifies_once");

        for (boolean duringDespawn : new boolean[] { false, true }) {
            for (boolean destroy : new boolean[] { false, true }) {
                for (boolean packet : new boolean[] { false, true }) {
                    String label = duringDespawn + "_" + destroy + "_" + packet;
                    NPC npc = npc(named, "Callback" + label, EntityType.COW, packet);
                    npc.data().set(NPC.Metadata.RESPAWN_DELAY, 0); sentinel(npc, 1, null);
                    Entity old = npc.getEntity();
                    Runnable change = () -> {
                        death = null; despawn = null;
                        if (destroy) npc.destroy();
                        else {
                            npc.despawn(DespawnReason.PLUGIN);
                            check(npc.spawn(new Location(level, 13, -60, 13)), "callback_replacement_spawned_" + label);
                        }
                    };
                    if (duringDespawn) despawn = e -> { if (e.getNPC() == npc && e.getReason() == DespawnReason.DEATH) change.run(); };
                    else death = e -> { if (e.getNPC() == npc) change.run(); };
                    kill(npc, false); death = null; despawn = null;
                    Entity replacement = npc.getEntity();
                    check(old.isRemoved() && NPCRegistries.lookup(old) == null, "callback_retires_original_" + label);
                    check(destroy ? !exists(npc) && replacement == null : npc.isSpawned() && replacement != old,
                            "callback_result_survives_outer_death_" + label);
                    at(80, () -> check(destroy ? !exists(npc) && npc.getEntity() == null : npc.isSpawned() && npc.getEntity() == replacement,
                            "stale_death_work_cannot_replace_callback_result_" + label));
                }
            }
        }
        NPC cancelDespawn = npc(named, "CancelDeathDespawn", EntityType.COW, false);
        despawn = e -> { if (e.getNPC() == cancelDespawn && e.getReason() == DespawnReason.DEATH) e.setCanceled(true); };
        Entity old = cancelDespawn.getEntity(); kill(cancelDespawn, false); despawn = null;
        released(cancelDespawn, old, "death_despawn_cancellation_cannot_retain_dead_controller");
    }

    private static void vanillaControl() {
        Mob cow = EntityType.COW.create(level); cow.setPos(6, -60, 6); cow.setNoAi(true); nativeEntities.add(cow);
        check(level.addFreshEntity(cow), "vanilla_control_admitted");
        cow.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND, 3)); cow.setDropChance(EquipmentSlot.MAINHAND, 2);
        int before = deaths.values().stream().mapToInt(Integer::intValue).sum();
        cow.hurt(cow.damageSources().genericKill(), Float.MAX_VALUE);
        check(deaths.values().stream().mapToInt(Integer::intValue).sum() == before && count(nativeDrops.get(cow), Items.DIAMOND) == 3,
                "vanilla_death_and_native_drops_unchanged");
        at(20, () -> check(cow.isRemoved(), "vanilla_corpse_receives_native_death_ticks"));
    }

    private static NPC npc(NPCRegistry registry, String name, EntityType<?> type, boolean packet) {
        NPC npc = registry.createNPC(type, name); npcs.add(npc);
        npc.getOrAddTrait(LookClose.class).setEnabled(false); npc.setProtected(false);
        npc.data().set(NPC.Metadata.RESPAWN_DELAY, -1);
        if (type == EntityType.PLAYER) npc.getOrAddTrait(SkinTrait.class).setFetchDefaultSkin(false);
        if (packet) npc.getOrAddTrait(PacketNPC.class);
        int slot = sequence++ % 36;
        check(npc.spawn(new Location(level, 1 + slot % 6 * 2, -60, 1 + slot / 6 * 2)), name + "_spawned");
        npc.getEntity().setNoGravity(true);
        if (npc.getEntity() instanceof Mob mob) mob.setNoAi(true);
        owners.put(npc.getEntity(), npc); nativeEntities.add(npc.getEntity());
        spawns.put(npc, new ArrayList<>());
        return npc;
    }

    private static void sentinel(NPC npc, int delay, Location spawnPoint) {
        MemoryDataKey data = new MemoryDataKey(); data.setInt("respawnTime", delay); data.setInt("healRate", 0);
        if (spawnPoint != null) new LocationPersister().save(spawnPoint, data.getRelative("spawnPoint"));
        SentinelTrait trait = PersistenceLoader.load(new SentinelTrait(), data);
        check(trait != null && trait.getRespawnTime() == delay, "sentinel_configuration_loaded_" + npc.getName());
        npc.addTrait(trait);
    }

    private static void kill(NPC npc, boolean direct) {
        LivingEntity entity = (LivingEntity) npc.getEntity();
        if (direct) { entity.setHealth(0); entity.die(entity.damageSources().genericKill()); }
        else check(entity.hurt(entity.damageSources().genericKill(), Float.MAX_VALUE), "native_lethal_hurt_" + npc.getName());
    }

    private static void released(NPC npc, Entity old, String label) {
        check(!npc.isSpawned() && npc.getEntity() == null, "accepted_death_releases_entity_" + label);
        check(NPCRegistries.lookup(old) == null || old instanceof net.citizensnpcs.npc.entity.EntityHumanNPC
                && npc.getEntity() != old, "accepted_death_unlinks_original_" + label);
    }

    private static void expectRespawn(NPC npc, int due, SpawnReason reason, String label) {
        at(due - 1, () -> check(!npc.isSpawned() && spawnAttempts(npc) == 0, "not_early_" + label));
        at(due, () -> check(npc.isSpawned() && npc.getEntity() instanceof LivingEntity living && living.isAlive()
                && spawns.get(npc).equals(List.of(reason)), "exact_deadline_" + label));
    }

    private static int spawnAttempts(NPC npc) { return spawns.get(npc).size(); }
    private static boolean exists(NPC npc) { return npc.getOwningRegistry().getByUniqueId(npc.getUniqueId()) == npc; }
    private static int count(List<ItemEntity> drops, net.minecraft.world.item.Item item) {
        return drops == null ? 0 : drops.stream().filter(e -> e.getItem().is(item)).mapToInt(e -> e.getItem().getCount()).sum();
    }
    private static void at(int tick, Runnable action) { checks.computeIfAbsent(tick, unused -> new ArrayList<>()).add(action); }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++; LoggerFactory.getLogger("citizens").info("[DEATHAUDIT] PASS {}", label);
    }

    private static void close(MinecraftServer server) {
        if (done) return;
        done = true; death = null; despawn = null; spawn = null; join = null; livingDeath = null; livingDrops = null;
        try {
            for (NPC npc : npcs) if (exists(npc)) npc.destroy();
            for (Entity entity : nativeEntities) if (!entity.isRemoved()) entity.discard();
            if (named != null) CitizensAPI.removeNamedNPCRegistry(REGISTRY);
            if (viewer != null) { server.getPlayerList().remove(viewer.player); viewer.channel.finishAndReleaseAll(); }
            if (forced) level.setChunkForced(0, 0, false);
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[DEATHAUDIT] FAILED cleanup", failure);
        } finally { server.halt(false); }
    }

    private static Actor actor(MinecraftServer server) {
        Actor actor = new Actor();
        ClientInformation information = ClientInformation.createDefault();
        actor.player = new ServerPlayer(server, level, new GameProfile(UUID.randomUUID(), "DeathAuditViewer"), information);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        actor.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                connection.configurePacketHandler(channel.pipeline());
                channel.pipeline().addLast("death-audit-capture", new ChannelOutboundHandlerAdapter() {
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
        actor.player.setPos(7, -60, 7); actor.player.setNoGravity(true);
        return actor;
    }

    private static final class Actor {
        ServerPlayer player;
        EmbeddedChannel channel;
        int pendingBatches;
        final List<Packet<?>> packets = new ArrayList<>();
        void capture(Packet<?> packet) {
            if (packet instanceof ClientboundBundlePacket bundle) { bundle.subPackets().forEach(this::capture); return; }
            packets.add(packet);
            if (packet instanceof ClientboundChunkBatchFinishedPacket) pendingBatches++;
        }
        void pump() {
            channel.runPendingTasks();
            while (pendingBatches > 0) { pendingBatches--; player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(16)); }
            Object value; while ((value = channel.readOutbound()) != null) ReferenceCountUtil.release(value);
        }
        long deaths(Entity entity) {
            pump();
            return packets.stream().filter(p -> p instanceof ClientboundEntityEventPacket e && e.getEventId() == 3
                    && entityEventId(e) == entity.getId()).count();
        }
        long removals(Entity entity) {
            pump();
            return packets.stream().filter(p -> p instanceof ClientboundRemoveEntitiesPacket r && r.getEntityIds().contains(entity.getId())).count();
        }
        private int entityEventId(ClientboundEntityEventPacket packet) {
            var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                ClientboundEntityEventPacket.STREAM_CODEC.encode(buffer, packet);
                return buffer.readInt();
            } finally { buffer.release(); }
        }
    }
}
