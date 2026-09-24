package net.citizensnpcs.audit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCSeenByPlayerEvent;
import net.citizensnpcs.api.npc.*;
import net.citizensnpcs.api.trait.trait.PlayerFilter;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.npc.NPCRegistries;
import net.citizensnpcs.npc.entity.EntityHumanNPC;
import net.citizensnpcs.npc.skin.SkinPacketTracker;
import net.citizensnpcs.trait.*;
import net.citizensnpcs.util.NPCVisibility;
import net.minecraft.core.BlockPos;
import net.minecraft.network.*;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.scores.PlayerTeam;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

/** Captured native packets with separate profile-map and cached client-entity lifetimes. */
@EventBusSubscriber(modid = "citizens")
public final class SkinRefreshRuntimeAudit {
    private static boolean forced, done, recursive, denyBob;
    private static int phase, nextTick, passed, deadline;
    private static NPCRegistry registry;
    private static Actor alice, bob, far;
    private static final List<Actor> actors = new ArrayList<>();
    private static final List<NPC> owned = new ArrayList<>(), humans = new ArrayList<>();
    private static final Map<NPC, Entity> original = new IdentityHashMap<>();
    private static final Map<NPC, Entity> parents = new IdentityHashMap<>(), children = new IdentityHashMap<>();
    private static final Map<String, Integer> stops = new HashMap<>(), starts = new HashMap<>();
    private static final List<String> errors = new ArrayList<>();
    private static final List<ClientboundPlayerInfoUpdatePacket.Entry> captured = new ArrayList<>();
    private static NPC destroyOnStop, replaceOnStop;
    private static net.citizensnpcs.util.EntityPacketTracker retiredTracker;
    private static PlayerTeam realTeam;
    private static NPC nameNpc;
    private static Entity previousNameEntity;
    private static int nameStep;

    @SubscribeEvent public static void seen(NPCSeenByPlayerEvent event) {
        if (humans.contains(event.getNPC()) && denyBob && event.getPlayer() == bob.player) event.setCanceled(true);
    }
    @SubscribeEvent public static void started(PlayerEvent.StartTracking event) {
        NPC npc = NPCRegistries.lookup(event.getTarget());
        if (humans.contains(npc)) starts.merge(key(npc, (ServerPlayer) event.getEntity()), 1, Integer::sum);
    }
    @SubscribeEvent public static void stopped(PlayerEvent.StopTracking event) {
        NPC npc = NPCRegistries.lookup(event.getTarget());
        if (!humans.contains(npc)) return;
        stops.merge(key(npc, (ServerPlayer) event.getEntity()), 1, Integer::sum);
        if (npc == destroyOnStop) { destroyOnStop = null; npc.destroy(); return; }
        if (npc == replaceOnStop) { replaceOnStop = null; npc.addTrait(new PacketNPC()); return; }
        if (recursive) SkinPacketTracker.respawn((EntityHumanNPC) event.getTarget());
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        MinecraftServer server = event.getServer(); ServerLevel level = server.overworld();
        if (!forced) { level.setChunkForced(0, 0, true); forced = true; deadline = server.getTickCount() + 1400; }
        try {
            for (Actor actor : actors) actor.pump();
            if (server.getTickCount() > deadline) throw new AssertionError("timed_out_phase_" + phase);
            if (!level.areEntitiesLoaded(0L) || !level.isPositionEntityTicking(new BlockPos(1, -60, 1))) return;
            if (server.getTickCount() < nextTick) return;
            if ((phase == 1 || phase == 9) && (!alice.watches(level) || !bob.watches(level))) return;
            nextTick = server.getTickCount() + 20;
            switch (phase++) {
                case 0 -> {
                    check(Files.isRegularFile(Path.of("skin-refresh-audit-fixture.txt")) && !CitizensAPI.getNPCRegistry().iterator().hasNext(), "isolated_empty_fixture");
                    Setting.TABLIST_REMOVE_PACKET_DELAY.set(6);
                    alice = actor(server, UUID.randomUUID(), "SkinAlice", 6);
                    bob = actor(server, UUID.randomUUID(), "SkinBob", 8);
                    far = actor(server, UUID.randomUUID(), "SkinFar", 180);
                    profile(alice.player, "alice-v1"); profile(bob.player, "bob-v1");
                    alice.player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND));
                    bob.player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.EMERALD));
                    registry = CitizensAPI.createNamedNPCRegistry("skin-refresh-audit", new MemoryNPCDataStore());
                    realTeam = server.getScoreboard().addPlayerTeam("real-players");
                    server.getScoreboard().addPlayerToTeam(alice.player.getScoreboardName(), realTeam);
                    server.getScoreboard().addPlayerToTeam(bob.player.getScoreboardName(), realTeam);
                }
                case 1 -> {
                    for (boolean virtual : List.of(false, true)) for (boolean mirror : List.of(false, true)) {
                        NPC npc = npc(EntityType.PLAYER, (virtual ? "V" : "W") + (mirror ? "Mirror" : "Skin"), virtual);
                        humans.add(npc); npc.getOrAddTrait(ScoreboardTrait.class);
                        npc.data().set(NPC.Metadata.REMOVE_FROM_TABLIST, true);
                        SkinTrait skin = npc.getOrAddTrait(SkinTrait.class); skin.setFetchDefaultSkin(false); skin.setSkinPersistent("source", "signature", texture("npc-v1"));
                        if (mirror) {
                            MirrorTrait trait = npc.getOrAddTrait(MirrorTrait.class);
                            trait.setMirrorName(true); trait.setMirrorEquipment(true); trait.setEnabled(true);
                        }
                        spawn(npc, level);
                        npc.getEntity().setGlowingTag(true);
                        ((LivingEntity) npc.getEntity()).getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.2);
                        ((LivingEntity) npc.getEntity()).setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.GOLD_INGOT));
                        NPC parent = npc(EntityType.COW, "Parent", !virtual); spawn(parent, level);
                        NPC child = npc(EntityType.ARMOR_STAND, "Child", virtual); spawn(child, level);
                        check(npc.getEntity().startRiding(parent.getEntity(), true) && child.getEntity().startRiding(npc.getEntity(), true), "mount_setup_" + npc.getName());
                        original.put(npc, npc.getEntity()); parents.put(npc, parent.getEntity()); children.put(npc, child.getEntity());
                    }
                }
                case 2 -> {
                    for (NPC npc : humans) {
                        for (Actor actor : List.of(alice, bob)) {
                            appearance(actor, npc, npc.hasTrait(MirrorTrait.class) ? actor == alice ? "alice-v1" : "bob-v1" : "npc-v1");
                            mounts(actor, npc);
                            check(actor.births(npc) == 1 && !actor.info.get(npc.getMinecraftUniqueId()).listed, "initial_native_pair_hidden_" + actor.name() + npc.getName());
                            captured.add(actor.rawProfile(npc));
                        }
                        check(far.births(npc) == 0 && !far.info.containsKey(npc.getMinecraftUniqueId()), "distant_has_no_profile_" + npc.getName());
                    }
                    equipmentPackets();
                    // Negative control: the old map-only refresh leaves the already created player's cached info stale.
                    NPC first = humans.getFirst(); Replica previous = alice.replica(first);
                    profile(human(first), "negative-control");
                    SkinPacketTracker.removeFrom(human(first), alice.player); SkinPacketTracker.sendTo(human(first), alice.player); alice.pump();
                    check(alice.replica(first) == previous && previous.cached != alice.info.get(first.getMinecraftUniqueId()), "map_only_refresh_does_not_replace_cached_info");
                    check(value(previous.cached.profile).equals(texture("npc-v1"))
                            && value(alice.info.get(first.getMinecraftUniqueId()).profile).equals(texture("negative-control")), "map_only_refresh_keeps_old_rendered_texture");
                    clear(); profile(alice.player, "alice-v2"); profile(bob.player, "bob-v2");
                    for (NPC npc : humans) {
                        if (!npc.hasTrait(MirrorTrait.class)) npc.getTrait(SkinTrait.class).setSkinPersistent("source", "signature", texture("npc-v2"));
                        else SkinPacketTracker.respawn(human(npc));
                    }
                }
                case 3 -> {
                    int i = 0;
                    for (NPC npc : humans) {
                        unchangedServer(npc);
                        for (Actor actor : List.of(alice, bob)) {
                            appearance(actor, npc, npc.hasTrait(MirrorTrait.class) ? actor == alice ? "alice-v2" : "bob-v2" : "npc-v2");
                            check(actor.refreshOrder(npc), "entity_remove_profile_remove_add_entity_order_" + actor.name() + npc.getName());
                            check(actor.births(npc) == 1 && actor.removals(npc) == 1, "one_refresh_per_viewer_" + actor.name() + npc.getName());
                            check(actor.replica(npc).metadata > 0 && actor.replica(npc).attributes > 0, "full_native_pairing_state_" + actor.name() + npc.getName());
                            mounts(actor, npc);
                            String old = npc.hasTrait(MirrorTrait.class) ? actor == alice ? "alice-v1" : "bob-v1" : "npc-v1";
                            check(value(captured.get(i++).profile()).equals(texture(old)), "queued_profile_snapshot_immutable_" + actor.name() + npc.getName());
                        }
                        check(far.updates(npc) == 0, "refresh_does_not_leak_" + npc.getName());
                        SkinPacketTracker.setListed(human(npc), false);
                        SkinPacketTracker.respawn(human(npc));
                        SkinPacketTracker.setListed(human(npc), true);
                    }
                    clear();
                }
                case 4 -> {
                    for (NPC npc : humans) for (Actor actor : List.of(alice, bob))
                        check(actor.info.get(npc.getMinecraftUniqueId()).listed, "delayed_refresh_honors_live_show_" + actor.name() + npc.getName());
                    recursive = true; clear(); stops.clear(); starts.clear();
                    for (NPC npc : humans) {
                        npc.getOrAddTrait(PlayerFilter.class).addPlayer(alice.player.getUUID());
                        SkinPacketTracker.respawn(human(npc));
                    }
                }
                case 5 -> {
                    for (NPC npc : humans) {
                        check(alice.replica(npc) == null && !alice.info.containsKey(npc.getMinecraftUniqueId()) && alice.births(npc) == 0, "same_tick_filter_never_readds_" + npc.getName());
                        check(stops.getOrDefault(key(npc, bob.player), 0) == 1 && starts.getOrDefault(key(npc, bob.player), 0) == 1, "recursive_refresh_suppressed_" + npc.getName());
                        check(bob.refreshOrder(npc), "recursive_refresh_has_one_valid_sequence_" + npc.getName());
                        npc.removeTrait(PlayerFilter.class);
                    }
                    recursive = false; clear(); bob.player.setPos(180, -60, 4);
                    for (NPC npc : humans) SkinPacketTracker.respawn(human(npc));
                }
                case 6 -> {
                    for (NPC npc : humans) {
                        check(bob.replica(npc) == null && !bob.info.containsKey(npc.getMinecraftUniqueId()) && bob.births(npc) == 0, "same_tick_range_exit_no_readd_" + npc.getName());
                        appearance(alice, npc, npc.hasTrait(MirrorTrait.class) ? "alice-v2" : "npc-v2");
                    }
                    bob.player.setPos(8, -60, 4); clear();
                }
                case 7 -> {
                    for (NPC npc : humans) {
                        appearance(bob, npc, npc.hasTrait(MirrorTrait.class) ? "bob-v2" : "npc-v2");
                        check(bob.births(npc) == 1, "range_reentry_latest_profile_" + npc.getName());
                    }
                    UUID id = alice.player.getUUID();
                    server.getPlayerList().remove(alice.player); actors.remove(alice); alice.channel.finishAndReleaseAll();
                    alice = actor(server, id, "SkinAlice", 6); profile(alice.player, "alice-v3");
                    alice.player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND));
                    // Admission may already have happened during placeNewPlayer; refresh observes current actual viewers.
                    for (NPC npc : humans) SkinPacketTracker.respawn(human(npc));
                }
                case 8 -> {
                    for (NPC npc : humans) appearance(alice, npc, npc.hasTrait(MirrorTrait.class) ? "alice-v3" : "npc-v2");
                    ServerPlayer old = bob.player; old.setHealth(0);
                    old.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                    bob.player = old.connection.getPlayer(); bob.player.setPos(8, -60, 4);
                    bob.player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.EMERALD)); clear();
                }
                case 9 -> {
                    for (NPC npc : humans) SkinPacketTracker.respawn(human(npc));
                    NPC ridden = humans.getFirst(); children.get(ridden).stopRiding();
                    check(alice.player.startRiding(ridden.getEntity(), true), "real_viewer_mount_setup");
                    clear(); SkinPacketTracker.respawn(human(ridden));
                }
                case 10 -> {
                    for (NPC npc : humans) { unchangedServer(npc); appearance(bob, npc, npc.hasTrait(MirrorTrait.class) ? "bob-v2" : "npc-v2"); }
                    NPC ridden = humans.getFirst();
                    check(alice.player.getVehicle() == ridden.getEntity() && alice.mounts.getOrDefault(ridden.getEntity().getId(), List.of()).contains(alice.player.getId()), "real_viewer_mount_restored_after_refresh");
                    alice.player.stopRiding();
                    check(children.get(ridden).startRiding(ridden.getEntity(), true), "helper_remounted");
                    denyBob = true; clear();
                    for (NPC npc : humans) SkinPacketTracker.respawn(human(npc));
                }
                case 11 -> {
                    for (NPC npc : humans) {
                        check(bob.replica(npc) == null && !bob.info.containsKey(npc.getMinecraftUniqueId()) && bob.births(npc) == 0, "refresh_honors_cancelled_readmission_" + npc.getName());
                        check(alice.replica(npc) != null, "other_viewer_still_refreshes_" + npc.getName());
                    }
                    denyBob = false; clear();
                    for (boolean virtual : List.of(false, true)) {
                        NPC target = npc(EntityType.PLAYER, "Destroy" + virtual, virtual);
                        target.getOrAddTrait(SkinTrait.class).setFetchDefaultSkin(false); humans.add(target); spawn(target, level); original.put(target, target.getEntity());
                    }
                }
                case 12 -> {
                    for (NPC npc : List.copyOf(humans)) if (npc.getName().startsWith("Destroy")) {
                        Entity removed = npc.getEntity(); destroyOnStop = npc; clear();
                        SkinPacketTracker.respawn(human(npc));
                        check(registry.getByUniqueId(npc.getUniqueId()) == null && removed.isRemoved(), "stop_listener_destruction_final_" + npc.getName());
                        for (Actor actor : actors) check(actor.births(npc) == 0 && !actor.info.containsKey(npc.getMinecraftUniqueId()), "destruction_never_resurrects_" + actor.name() + npc.getName());
                        humans.remove(npc);
                    }
                    NPC target = humans.get(2); retiredTracker = target.getTrait(PacketNPC.class).getPacketTracker(); replaceOnStop = target; clear();
                    SkinPacketTracker.respawn(human(target));
                    check(retiredTracker.getLinked().isEmpty(), "replaced_virtual_tracker_stays_unlinked");
                }
                case 13 -> {
                    for (NPC npc : humans) for (Actor actor : List.of(alice, bob)) {
                        appearance(actor, npc, npc.hasTrait(MirrorTrait.class) ? actor == alice ? "alice-v3" : "bob-v2" : "npc-v2");
                        mounts(actor, npc);
                    }
                    var guard = SkinPacketTracker.class.getDeclaredField("refreshing"); guard.setAccessible(true);
                    check(((Set<?>) guard.get(null)).isEmpty(), "refresh_guard_released");
                    check(errors.isEmpty(), "native_packet_replay_consistency_" + errors);
                    for (NPC npc : List.copyOf(humans)) {
                        EntityHumanNPC removed = human(npc); npc.destroy(); clear();
                        SkinPacketTracker.respawn(removed); SkinPacketTracker.setListed(removed, true);
                        for (Actor actor : actors) check(actor.updates(npc) == 0 && actor.births(npc) == 0, "retired_entity_cannot_send_" + actor.name() + npc.getName());
                    }
                    nextTick = server.getTickCount() + 30; clear();
                }
                case 14 -> {
                    // Virtual viewers join during normal trait ticks; observe the first pairing on the following tick.
                    if (!mirrorNameCommands(server, level)) { phase--; nextTick = server.getTickCount() + 1; break; }
                    check(realTeam.getPlayers().equals(Set.of("SkinAlice", "SkinBob")), "real_player_team_untouched_by_mirrored_names");
                    for (NPC npc : humans) for (Actor actor : actors) check(!actor.info.containsKey(npc.getMinecraftUniqueId()) && actor.updates(npc) == 0, "pending_list_update_cannot_revive_" + actor.name() + npc.getName());
                    check(errors.isEmpty(), "final_packet_consistency_" + errors);
                    LoggerFactory.getLogger("citizens").info("[SKINREFRESHAUDIT] COMPLETE {} checks", passed); done = true;
                }
            }
        } catch (Throwable failure) { done = true; LoggerFactory.getLogger("citizens").error("[SKINREFRESHAUDIT] FAILED phase " + phase, failure); }
        if (done) {
            try {
                recursive = false; destroyOnStop = null; replaceOnStop = null;
                for (NPC npc : owned) if (registry.getByUniqueId(npc.getUniqueId()) != null) npc.destroy();
                for (Actor actor : actors) { server.getPlayerList().remove(actor.player); actor.channel.finishAndReleaseAll(); }
                if (realTeam != null) server.getScoreboard().removePlayerTeam(realTeam);
            } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[SKINREFRESHAUDIT] FAILED cleanup", failure); }
            server.halt(false);
        }
    }
    private static NPC npc(EntityType<?> type, String name, boolean virtual) {
        NPC npc = registry.createNPC(type, name); owned.add(npc); npc.data().set(NPC.Metadata.TRACKING_RANGE, 64);
        if (virtual) npc.getOrAddTrait(PacketNPC.class); return npc;
    }
    private static void spawn(NPC npc, ServerLevel level) { check(npc.spawn(new Location(level, 4, -60, 4)), "spawn_" + npc.getName()); }
    private static EntityHumanNPC human(NPC npc) { return (EntityHumanNPC) npc.getEntity(); }
    private static String key(NPC npc, ServerPlayer player) { return npc.getUniqueId() + "/" + player.getUUID(); }
    private static String texture(String revision) { return Base64.getEncoder().encodeToString(("{\"textures\":{},\"revision\":\"" + revision + "\"}").getBytes(StandardCharsets.UTF_8)); }
    private static void profile(ServerPlayer player, String revision) {
        player.getGameProfile().getProperties().removeAll("textures"); player.getGameProfile().getProperties().put("textures", new Property("textures", texture(revision), "signature"));
    }
    private static String value(GameProfile profile) { return profile.getProperties().get("textures").stream().findFirst().map(Property::value).orElse(""); }
    private static void unchangedServer(NPC npc) {
        check(npc.getEntity() == original.get(npc) && !npc.getEntity().isRemoved(), "server_entity_identity_preserved_" + npc.getName());
        check(npc.getEntity().getVehicle() == parents.get(npc), "server_vehicle_preserved_" + npc.getName());
        check(npc.hasTrait(PacketNPC.class) ? npc.getEntity().level().getEntity(npc.getEntity().getId()) == null
                : npc.getEntity().level().getEntity(npc.getEntity().getId()) == npc.getEntity(), "transport_unchanged_" + npc.getName());
    }
    private static void appearance(Actor actor, NPC npc, String expected) {
        actor.pump(); Replica replica = actor.replica(npc); Info info = actor.info.get(npc.getMinecraftUniqueId());
        check(replica != null && info != null && replica.cached == info && value(replica.cached.profile).equals(texture(expected)),
                "cached_entity_uses_current_skin_" + actor.name() + npc.getName() + " entity=" + (replica != null)
                        + " info=" + (info != null) + " same=" + (replica != null && replica.cached == info)
                        + " texture=" + (info == null ? "none" : value(info.profile)) + " expected=" + texture(expected));
        check(replica.profile.getId().equals(npc.getMinecraftUniqueId()) && replica.profile.getName().equals(npc.hasTrait(MirrorTrait.class) ? actor.name() : npc.getName()), "profile_identity_and_name_" + actor.name() + npc.getName());
        Item item = npc.hasTrait(MirrorTrait.class) ? actor == alice ? Items.DIAMOND : Items.EMERALD : Items.GOLD_INGOT;
        check(replica.equipment.getOrDefault(EquipmentSlot.MAINHAND, ItemStack.EMPTY).is(item), "equipment_after_pairing_" + actor.name() + npc.getName());
    }
    private static void mounts(Actor actor, NPC npc) {
        actor.pump();
        check(actor.mounts.getOrDefault(parents.get(npc).getId(), List.of()).contains(original.get(npc).getId())
                && actor.mounts.getOrDefault(original.get(npc).getId(), List.of()).contains(children.get(npc).getId()), "client_mount_graph_restored_" + actor.name() + npc.getName());
    }
    private static void clear() { for (Actor actor : actors) { actor.pump(); actor.packets.clear(); } }
    private static boolean mirrorNameCommands(MinecraftServer server, ServerLevel level) throws Exception {
        boolean virtual = nameStep >= 5;
        int step = nameStep++ % 5;
        if (step == 0) {
            nameNpc = npc(EntityType.PLAYER, virtual ? "NameCmdVirtual" : "NameCmdWorld", virtual);
            nameNpc.getOrAddTrait(SkinTrait.class).setFetchDefaultSkin(false);
            spawn(nameNpc, level);
            return false;
        }
        if (step >= 2) {
            boolean name = step != 3;
            for (Actor actor : List.of(alice, bob)) {
                actor.pump(); var entry = actor.rawProfile(nameNpc);
                check(entry.profile().getId().equals(nameNpc.getMinecraftUniqueId())
                        && entry.profile().getName().equals(name ? actor.name() : nameNpc.getName()),
                        "mirror_name_command_initial_profile_" + actor.name() + "_" + virtual + "_" + name);
                check(!actor.entities.containsKey(previousNameEntity.getId()) && actor.replica(nameNpc) != null
                        && actor.replica(nameNpc).cached == actor.info.get(nameNpc.getMinecraftUniqueId()),
                        "mirror_name_command_client_entity_" + actor.name() + "_" + virtual + "_" + name);
            }
            check(far.updates(nameNpc) == 0, "mirror_name_command_no_distant_profile_" + virtual + "_" + name);
        }
        if (step == 4) {
            nameNpc.destroy();
            for (Actor actor : List.of(alice, bob)) {
                actor.pump();
                check(!actor.info.containsKey(nameNpc.getMinecraftUniqueId()), "mirror_name_command_cleanup_" + actor.name() + "_" + virtual);
            }
            return virtual;
        }
        boolean name = step != 2;
        var source = server.createCommandSourceStack().withPermission(4);
        CitizensAPI.getDefaultNPCSelector().select(source, nameNpc);
        previousNameEntity = nameNpc.getEntity(); clear();
        check(server.getCommands().getDispatcher().execute("npc mirror --name " + name, source) > 0
                && nameNpc.getTrait(MirrorTrait.class).isEnabled() && nameNpc.getTrait(MirrorTrait.class).mirrorName() == name,
                "mirror_name_command_sets_option_" + virtual + "_" + name);
        check(nameNpc.getEntity() != previousNameEntity && previousNameEntity.isRemoved(), "mirror_name_command_replaces_old_entity_" + virtual + "_" + name);
        return false;
    }
    private static void equipmentPackets() {
        NPC npc = humans.get(1); MirrorTrait mirror = npc.getTrait(MirrorTrait.class);
        ItemStack source = new ItemStack(Items.GOLD_INGOT);
        var packet = new ClientboundSetEquipmentPacket(npc.getEntity().getId(), List.of(com.mojang.datafixers.util.Pair.of(EquipmentSlot.MAINHAND, source)));
        var a = (ClientboundSetEquipmentPacket) net.citizensnpcs.util.EquipmentPackets.rewrite(npc.getEntity(), alice.player, packet);
        var b = (ClientboundSetEquipmentPacket) net.citizensnpcs.util.EquipmentPackets.rewrite(npc.getEntity(), bob.player, packet);
        check(packet.getSlots().size() == 1 && packet.getSlots().getFirst().getSecond() == source && source.is(Items.GOLD_INGOT), "equipment_input_packet_unchanged");
        check(a.getSlots().getFirst().getSecond().is(Items.DIAMOND) && b.getSlots().getFirst().getSecond().is(Items.EMERALD), "shared_equipment_packet_personalized_independently");
        check(a.getSlots().getFirst().getSecond() != alice.player.getMainHandItem() && b.getSlots().getFirst().getSecond() != bob.player.getMainHandItem(), "mirrored_items_are_detached_copies");
        var unrelated = new ClientboundSetEquipmentPacket(-1, packet.getSlots());
        check(net.citizensnpcs.util.EquipmentPackets.rewrite(npc.getEntity(), alice.player, unrelated) == unrelated, "other_entity_equipment_untouched");
        mirror.setEquipmentFunction((viewer, slot) -> slot == EquipmentSlot.MAINHAND ? new ItemStack(Items.AMETHYST_SHARD) : null);
        var custom = (ClientboundSetEquipmentPacket) net.citizensnpcs.util.EquipmentPackets.rewrite(npc.getEntity(), alice.player, packet);
        check(custom.getSlots().getFirst().getSecond().is(Items.AMETHYST_SHARD)
                && custom.getSlots().stream().skip(1).allMatch(slot -> slot.getSecond().isEmpty()), "custom_equipment_function_and_null_slots");
        mirror.setEquipmentFunction(null);
        check(human(npc).getMainHandItem().is(Items.GOLD_INGOT) && alice.player.getMainHandItem().is(Items.DIAMOND), "projection_preserves_native_inventories");
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); passed++; LoggerFactory.getLogger("citizens").info("[SKINREFRESHAUDIT] PASS {}", label); }

    private static Actor actor(MinecraftServer server, UUID id, String name, double x) {
        var defaults = ClientInformation.createDefault();
        var information = new ClientInformation(defaults.language(), 6, defaults.chatVisibility(), defaults.chatColors(),
                defaults.modelCustomisation(), defaults.mainHand(), defaults.textFilteringEnabled(), defaults.allowsListing());
        Actor actor = new Actor(); actor.server = server; actor.player = new ServerPlayer(server, server.overworld(), new GameProfile(id, name), information);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        actor.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                connection.configurePacketHandler(channel.pipeline());
                channel.pipeline().addLast("skin-refresh-capture", new ChannelOutboundHandlerAdapter() {
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
    private static final class Info {
        final GameProfile profile; boolean listed;
        Info(GameProfile profile) { this.profile = profile; }
    }
    private static final class Replica {
        final GameProfile profile; final Info cached;
        final Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
        int metadata, attributes;
        Replica(Info info) { profile = info.profile; cached = info; }
    }
    private static final class Actor {
        ServerPlayer player; MinecraftServer server; EmbeddedChannel channel; int pendingBatches;
        final List<Packet<?>> packets = new ArrayList<>();
        final Map<UUID, Info> info = new HashMap<>();
        final Map<Integer, Replica> entities = new HashMap<>();
        final Map<Integer, List<Integer>> mounts = new HashMap<>();
        String name() { return player.getGameProfile().getName(); }
        Replica replica(NPC npc) { return entities.get(original.getOrDefault(npc, npc.getEntity()).getId()); }
        void capture(Packet<?> packet) {
            if (packet instanceof ClientboundBundlePacket bundle) { bundle.subPackets().forEach(this::capture); return; }
            packets.add(packet);
            if (packet instanceof ClientboundPlayerInfoUpdatePacket update) {
                RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
                try {
                    ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.encode(buffer, update);
                    var decoded = ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.decode(buffer);
                    for (var entry : decoded.newEntries()) info.putIfAbsent(entry.profileId(), new Info(entry.profile()));
                    for (var entry : decoded.entries()) if (decoded.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED)) {
                        Info current = info.get(entry.profileId());
                        if (current == null) errors.add("list_update_without_profile_" + name()); else current.listed = entry.listed();
                    }
                } finally { buffer.release(); }
            }
            if (packet instanceof ClientboundPlayerInfoRemovePacket remove) for (UUID id : remove.profileIds()) info.remove(id);
            if (packet instanceof ClientboundAddEntityPacket add && add.getType() == EntityType.PLAYER) {
                Info current = info.get(add.getUUID());
                if (current == null) errors.add("player_spawn_without_profile_" + name());
                else {
                    if (entities.containsKey(add.getId())) errors.add("duplicate_player_spawn_" + name());
                    entities.put(add.getId(), new Replica(current));
                }
            }
            if (packet instanceof ClientboundRemoveEntitiesPacket remove) for (int id : remove.getEntityIds()) {
                entities.remove(id); mounts.remove(id); mounts.replaceAll((vehicle, riders) -> riders.stream().filter(r -> r != id).toList());
            }
            if (packet instanceof ClientboundSetPassengersPacket mount) mounts.put(mount.getVehicle(), Arrays.stream(mount.getPassengers()).boxed().toList());
            if (packet instanceof ClientboundSetEntityDataPacket data && entities.containsKey(data.id())) entities.get(data.id()).metadata++;
            if (packet instanceof ClientboundUpdateAttributesPacket data && entities.containsKey(data.getEntityId())) entities.get(data.getEntityId()).attributes++;
            if (packet instanceof ClientboundSetEquipmentPacket equipment && entities.containsKey(equipment.getEntity())) {
                for (var slot : equipment.getSlots()) entities.get(equipment.getEntity()).equipment.put(slot.getFirst(), slot.getSecond().copy());
            }
            if (packet instanceof ClientboundChunkBatchFinishedPacket) pendingBatches++;
        }
        void pump() {
            channel.runPendingTasks();
            while (pendingBatches > 0) { pendingBatches--; player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(16)); }
            Object output; while ((output = channel.readOutbound()) != null) ReferenceCountUtil.release(output);
        }
        boolean watches(ServerLevel level) throws Exception {
            var method = ChunkMap.class.getDeclaredMethod("isChunkTracked", ServerPlayer.class, int.class, int.class); method.setAccessible(true);
            return (boolean) method.invoke(level.getChunkSource().chunkMap, player, 0, 0);
        }
        int entityId(NPC npc) { return original.getOrDefault(npc, npc.getEntity()).getId(); }
        long births(NPC npc) { pump(); return packets.stream().filter(p -> p instanceof ClientboundAddEntityPacket add && add.getId() == entityId(npc)).count(); }
        long removals(NPC npc) { pump(); return packets.stream().filter(p -> p instanceof ClientboundRemoveEntitiesPacket remove && remove.getEntityIds().contains(entityId(npc))).count(); }
        long updates(NPC npc) { pump(); return packets.stream().filter(p -> p instanceof ClientboundPlayerInfoUpdatePacket update && update.entries().stream().anyMatch(e -> e.profileId().equals(npc.getMinecraftUniqueId()))).count(); }
        ClientboundPlayerInfoUpdatePacket.Entry rawProfile(NPC npc) {
            return packets.stream().filter(p -> p instanceof ClientboundPlayerInfoUpdatePacket update && update.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER))
                    .map(p -> (ClientboundPlayerInfoUpdatePacket) p).flatMap(p -> p.entries().stream()).filter(e -> e.profileId().equals(npc.getMinecraftUniqueId())).findFirst().orElseThrow();
        }
        boolean refreshOrder(NPC npc) {
            int order = 0;
            for (Packet<?> packet : packets) {
                if (packet instanceof ClientboundRemoveEntitiesPacket remove && remove.getEntityIds().contains(entityId(npc))) { if (order != 0) return false; order = 1; }
                if (packet instanceof ClientboundPlayerInfoRemovePacket remove && remove.profileIds().contains(npc.getMinecraftUniqueId())) { if (order != 1) return false; order = 2; }
                if (packet instanceof ClientboundPlayerInfoUpdatePacket update && update.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)
                        && update.entries().stream().anyMatch(e -> e.profileId().equals(npc.getMinecraftUniqueId()))) { if (order != 2) return false; order = 3; }
                if (packet instanceof ClientboundAddEntityPacket add && add.getId() == entityId(npc)) { if (order != 3) return false; order = 4; }
            }
            return order == 4;
        }
    }
}
