package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import com.mojang.authlib.GameProfile;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.*;
import net.citizensnpcs.api.npc.*;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.trait.*;
import net.citizensnpcs.npc.NPCDeaths;
import net.citizensnpcs.util.EntityPacketTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.network.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class DragonDeathRuntimeAudit {
    private static final String REGISTRY = "dragon-death-audit-memory";
    private static final List<NPC> npcs = new ArrayList<>();
    private static final List<Case> cases = new ArrayList<>();
    private static final Map<NPC, Integer> deaths = new IdentityHashMap<>(), spawns = new IdentityHashMap<>();
    private static final Map<LivingEntity, Integer> xp = new IdentityHashMap<>();
    private static NPCRegistry registry;
    private static ServerLevel level;
    private static Actor viewer, outsider;
    private static EnderDragon vanilla, dormant;
    private static EnderDragon cancel;
    private static Consumer<NPCDeathEvent> deathCallback;
    private static final List<Runnable> finalChecks = new ArrayList<>();
    private static final List<Runnable> at25 = new ArrayList<>();
    private static int start = -1, deadline, passed, sequence;
    private static boolean forced, done;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void cancel(LivingDeathEvent event) {
        if (event.getEntity() == cancel) { cancel.setHealth(10); event.setCanceled(true); }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void death(NPCDeathEvent event) {
        deaths.merge(event.getNPC(), 1, Integer::sum);
        if (deathCallback != null) deathCallback.accept(event);
    }
    @SubscribeEvent
    public static void spawn(NPCSpawnEvent event) { spawns.merge(event.getNPC(), 1, Integer::sum); }
    @SubscribeEvent
    public static void experience(LivingExperienceDropEvent event) {
        if (event.getEntity() instanceof EnderDragon) {
            xp.merge(event.getEntity(), event.getDroppedExperience(), Integer::sum);
            event.setDroppedExperience(0); // Observe native awards without leaving fixture orbs behind.
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        MinecraftServer server = event.getServer(); level = server.overworld();
        try {
            if (!forced) {
                check(Files.isRegularFile(Path.of("dragon-death-audit-fixture.txt")), "isolated_fixture");
                check(!CitizensAPI.getNPCRegistry().iterator().hasNext(), "empty_default_registry");
                for (int x = -2; x <= 6; x++) for (int z = -2; z <= 3; z++) level.setChunkForced(x, z, true);
                forced = true; deadline = server.getTickCount() + 1000;
            }
            if (server.getTickCount() > deadline) {
                LoggerFactory.getLogger("citizens").error("[DRAGONDEATHAUDIT] STATE start={} cases={}", start,
                        cases.stream().map(c -> c.label + ": hp=" + c.dragon.getHealth() + " phase=" + c.dragon.getPhaseManager().getCurrentPhase().getPhase()
                                + " counter=" + c.dragon.dragonDeathTime + " xyz=" + c.dragon.position() + " removed=" + c.dragon.isRemoved()
                                + " completeAt=" + c.completeAt + " done=" + c.done + " spawned=" + c.npc.isSpawned()).toList());
                throw new AssertionError("fixture_timeout");
            }
            if (!level.isPositionEntityTicking(new BlockPos(4, -58, 4))
                    || !level.isPositionEntityTicking(new BlockPos(36, -48, 4))) return;
            if (viewer != null) viewer.pump();
            if (outsider != null) outsider.pump();
            if (start < 0) {
                start = server.getTickCount(); registry = CitizensAPI.createInMemoryNPCRegistry(REGISTRY);
                level.getGameRules().getRule(GameRules.RULE_DOMOBLOOT).set(true, server);
                viewer = actor(server, "DragonDeathViewer"); viewer.player.getAbilities().invulnerable = true;
                outsider = actor(server, "DragonDeathHidden"); outsider.player.getAbilities().invulnerable = true;
                dormant = (EnderDragon) npc(true, "Dormant", false).getEntity();
                for (boolean packet : new boolean[] { false, true }) {
                    for (String mode : List.of("direct", "near", "far", "phase", "canceled", "hover")) {
                        NPC npc = npc(packet, mode, mode.equals("far"));
                        EnderDragon dragon = (EnderDragon) npc.getEntity();
                        int delay = mode.equals("near") ? 0 : 5;
                        npc.data().set(NPC.Metadata.RESPAWN_DELAY, delay);
                        if (mode.equals("near") || mode.equals("far") || mode.equals("canceled"))
                            dragon.getPhaseManager().setPhase(EnderDragonPhase.HOLDING_PATTERN);
                        if (mode.equals("canceled")) {
                            cancel = dragon;
                            dragon.hurt(dragon.head, dragon.damageSources().playerAttack(viewer.player), Float.MAX_VALUE);
                            cancel = null;
                            check(npc.getEntity() == dragon && dragon.isAlive() && deaths.getOrDefault(npc, 0) == 0,
                                    "canceled_native_death_retains_dragon_" + packet);
                            dragon.invulnerableTime = 0;
                        }
                        if (mode.equals("phase")) dragon.getPhaseManager().setPhase(EnderDragonPhase.DYING);
                        else if (mode.equals("direct")) { dragon.setHealth(0); dragon.die(dragon.damageSources().genericKill()); }
                        else {
                            check(dragon.hurt(dragon.head, dragon.damageSources().playerAttack(viewer.player), Float.MAX_VALUE),
                                    "lethal_damage_accepted_" + mode + packet);
                            check(mode.equals("hover") ? dragon.getHealth() == 0
                                            : dragon.getPhaseManager().getCurrentPhase().getPhase() == EnderDragonPhase.DYING && dragon.getHealth() == 1,
                                    "native_post_damage_transition_" + mode + packet);
                        }
                        if (!mode.equals("phase")) check(npc.getEntity() == null && !npc.isSpawned(), "accepted_death_detaches_" + mode + packet);
                        cases.add(new Case(npc, dragon, packet, delay, mode + packet));
                    }
                    NPC direct = npc(packet, "Kill", false);
                    EnderDragon killed = (EnderDragon) direct.getEntity(); direct.data().set(NPC.Metadata.RESPAWN_DELAY, 0);
                    killed.kill();
                    check(killed.isRemoved() && direct.getEntity() == null && deaths.getOrDefault(direct, 0) == 1,
                            "direct_kill_releases_controller_" + packet);
                    finalChecks.add(() -> check(direct.isSpawned() && spawns.get(direct) == 1 && xp.getOrDefault(killed, 0) == 0,
                            "direct_kill_respawns_without_dragon_animation_xp_" + packet));
                    for (boolean destroy : new boolean[] { false, true }) {
                        NPC callbackNpc = npc(packet, "Callback" + destroy, false);
                        EnderDragon old = (EnderDragon) callbackNpc.getEntity(); callbackNpc.data().set(NPC.Metadata.RESPAWN_DELAY, 0);
                        deathCallback = e -> {
                            if (e.getNPC() != callbackNpc) return;
                            deathCallback = null;
                            if (destroy) callbackNpc.destroy();
                            else { callbackNpc.despawn(); callbackNpc.spawn(new Location(level, 8, -58, 8)); }
                        };
                        old.hurt(old.head, old.damageSources().playerAttack(viewer.player), Float.MAX_VALUE);
                        deathCallback = null;
                        Entity replacement = callbackNpc.getEntity();
                        check(old.isRemoved() && (destroy ? replacement == null : replacement != null && replacement != old),
                                "damage_callback_result_survives_" + packet + destroy);
                        finalChecks.add(() -> check(callbackNpc.getEntity() == replacement && !NPCDeaths.isVisual(old),
                                "obsolete_dragon_work_cannot_replace_callback_result_" + packet + destroy));
                    }
                }
                vanilla = EntityType.ENDER_DRAGON.create(level); vanilla.setPos(4, -58, 4);
                check(level.addFreshEntity(vanilla), "vanilla_control_admitted");
                vanilla.setHealth(0); vanilla.die(vanilla.damageSources().genericKill());
                privacyAndOwnership();
            }
            if (server.getTickCount() == start) return;
            if (server.getTickCount() - start == 25) at25.forEach(Runnable::run);
            boolean complete = true;
            for (Case sample : cases) complete &= sample.tick(server.getTickCount());
            if (complete && server.getTickCount() - start > 210) {
                finalChecks.forEach(Runnable::run);
                check(dormant.tickCount == 0 && dormant.isAlive(), "ordinary_live_virtual_dragon_is_not_ticked");
                check(vanilla.isRemoved() && vanilla.dragonDeathTime == 200 && xp.getOrDefault(vanilla, 0) == 500,
                        "vanilla_death_sequence_and_experience_unchanged");
                close(server);
                LoggerFactory.getLogger("citizens").info("[DRAGONDEATHAUDIT] COMPLETE {} checks", passed);
            }
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[DRAGONDEATHAUDIT] FAILED", failure);
            close(server);
        }
    }

    private static NPC npc(boolean packet, String mode, boolean far) {
        NPC npc = registry.createNPC(EntityType.ENDER_DRAGON, "DragonAudit" + sequence++ + mode); npcs.add(npc);
        npc.getOrAddTrait(LookClose.class).setEnabled(false); npc.setProtected(false);
        if (packet) npc.getOrAddTrait(PacketNPC.class);
        check(npc.spawn(new Location(level, far ? 36 : 4, far ? -48 : -58, 4)), "spawn_" + npc.getName());
        npc.getEntity().setNoGravity(true);
        // Keep the flight path clear of the other controls and their later live replacements.
        if (far) ((EnderDragon) npc.getEntity()).setFightOrigin(new BlockPos(64, 0, 0));
        if (packet) { npc.getTraitNullable(PacketNPC.class).run(); check(npc.getTraitNullable(PacketNPC.class).getPacketTracker().isLinked(viewer.player), "virtual_viewer_paired_" + mode); }
        spawns.put(npc, 0);
        return npc;
    }

    private static void privacyAndOwnership() {
        NPC hidden = npc(true, "Private", false);
        EnderDragon dragon = (EnderDragon) hidden.getEntity();
        hidden.getOrAddTrait(net.citizensnpcs.api.trait.trait.PlayerFilter.class).addPlayer(outsider.player.getUUID());
        dragon.setPos(-16, -58, -16);
        hidden.getTraitNullable(PacketNPC.class).run();
        float previousHealth = dragon.getHealth(); dragon.setHealth(37);
        int healthId = dragon.getEntityData().getNonDefaultValues().stream()
                .filter(v -> v.value() instanceof Float f && f == 37).findFirst().orElseThrow().id();
        dragon.setHealth(previousHealth);
        hidden.data().set(NPC.Metadata.RESPAWN_DELAY, 0);
        dragon.setHealth(0); dragon.die(dragon.damageSources().genericKill());
        cases.add(new Case(hidden, dragon, true, 0, "private"));
        finalChecks.add(() -> {
            long visibleSounds = viewer.packets.stream().filter(p -> p instanceof ClientboundLevelEventPacket e && e.getType() == 1028
                    && e.getPos().equals(new BlockPos(-16, -58, -16))).count();
            long hiddenSounds = outsider.packets.stream().filter(p -> p instanceof ClientboundLevelEventPacket e && e.getType() == 1028
                    && e.getPos().equals(new BlockPos(-16, -58, -16))).count();
            check(visibleSounds == 1 && hiddenSounds == 0, "virtual_death_sound_is_private");
            check(viewer.packets.stream().filter(p -> p instanceof ClientboundSetEntityDataPacket m && m.id() == dragon.getId())
                    .map(p -> (ClientboundSetEntityDataPacket) p).flatMap(p -> p.packedItems().stream())
                    .anyMatch(v -> v.id() == healthId && v.value() instanceof Float f && f == 0), "virtual_client_receives_zero_health");
        });
        for (boolean packet : new boolean[] { false, true }) {
            for (boolean replace : new boolean[] { false, true }) {
                NPC manual = npc(packet, "Manual" + replace, false);
                EnderDragon old = (EnderDragon) manual.getEntity();
                manual.data().set(NPC.Metadata.RESPAWN_DELAY, 0);
                old.setHealth(0); old.die(old.damageSources().genericKill());
                at25.add(() -> {
                    manual.despawn();
                    if (replace) manual.spawn(new Location(level, 10, -58, 10));
                    Entity result = manual.getEntity(); int ticks = old.dragonDeathTime;
                    check(old.isRemoved() && !NPCDeaths.isVisual(old), "manual_operation_retires_dragon_visual_" + packet + replace);
                    finalChecks.add(() -> check(manual.getEntity() == result && old.dragonDeathTime == ticks
                            && xp.getOrDefault(old, 0) == 0, "manual_operation_cancels_obsolete_dragon_work_" + packet + replace));
                });
            }
            for (int delay : new int[] { -1, 0, 1 }) {
                NPC guard = npc(packet, "Sentinel" + delay, false);
                var data = new net.citizensnpcs.api.util.MemoryDataKey(); data.setInt("respawnTime", delay); data.setInt("healRate", 0);
                SentinelTrait sentinel = net.citizensnpcs.api.persistence.PersistenceLoader.load(new SentinelTrait(), data);
                guard.addTrait(sentinel);
                EnderDragon old = (EnderDragon) guard.getEntity();
                old.setHealth(0); old.die(old.damageSources().genericKill());
                cases.add(new Case(guard, old, packet, delay < 0 ? -2 : delay == 0 ? -1 : 20, "sentinel" + delay + packet));
            }
        }
    }

    private static final class Case {
        final NPC npc;
        final EnderDragon dragon;
        final boolean packet;
        final int delay;
        final String label;
        int completeAt = -1, lastCounter;
        boolean done, observedZero;
        Case(NPC npc, EnderDragon dragon, boolean packet, int delay, String label) {
            this.npc = npc; this.dragon = dragon; this.packet = packet; this.delay = delay; this.label = label;
        }
        boolean tick(int tick) {
            if (done) return true;
            if (completeAt < 0) {
                if (dragon.isRemoved()) {
                    check(dragon.dragonDeathTime == 200, "complete_native_200_tick_animation_" + label);
                    check(xp.getOrDefault(dragon, 0) == 500, "native_experience_awards_" + label);
                    check(deaths.getOrDefault(npc, 0) == 1, "exactly_one_npc_death_" + label);
                    check(!NPCDeaths.isVisual(dragon), "completed_visual_released_" + label);
                    if (packet) check(viewer.removals(dragon) == 1, "virtual_remove_once_" + label);
                    completeAt = tick;
                } else {
                    int count = dragon.dragonDeathTime;
                    check(count == lastCounter || count == lastCounter + 1, "native_counter_never_double_ticks_" + label);
                    if (count > 0) {
                        check(npc.getEntity() == null && !npc.isSpawned() && spawns.get(npc) == 0,
                                "respawn_waits_for_native_completion_" + label);
                        if (!observedZero) {
                            check(!net.neoforged.neoforge.event.EventHooks.canEntityGrief(level, dragon), "corpse_keeps_npc_grief_policy_" + label);
                            observedZero = true;
                        }
                    }
                    if (packet) check(level.getEntity(dragon.getUUID()) != dragon, "virtual_corpse_never_enters_level_" + label);
                    lastCounter = count;
                }
            }
            if (completeAt >= 0) {
                if (delay < 0) {
                    boolean registered = npc.getOwningRegistry().getByUniqueId(npc.getUniqueId()) == npc;
                    check(delay == -1 ? registered && !npc.isSpawned() : tick == completeAt ? registered : !registered,
                            "sentinel_native_completion_policy_" + label);
                    done = tick >= completeAt + 3;
                    return done;
                }
                if (tick < completeAt + delay) check(!npc.isSpawned() && spawns.get(npc) == 0, "not_early_respawn_" + label);
                else {
                    check(npc.isSpawned() && npc.getEntity() != dragon && spawns.get(npc) == 1, "respawn_after_native_completion_and_delay_" + label);
                    if (tick >= completeAt + delay + 3) done = true;
                }
            }
            return done;
        }
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++;
    }
    private static void close(MinecraftServer server) {
        if (done) return;
        done = true; cancel = null; deathCallback = null;
        try {
            for (NPC npc : npcs) if (npc.getOwningRegistry().getByUniqueId(npc.getUniqueId()) == npc) npc.destroy();
            if (vanilla != null) vanilla.discard();
            if (registry != null) CitizensAPI.removeNamedNPCRegistry(REGISTRY);
            if (viewer != null) { server.getPlayerList().remove(viewer.player); viewer.channel.finishAndReleaseAll(); }
            if (outsider != null) { server.getPlayerList().remove(outsider.player); outsider.channel.finishAndReleaseAll(); }
            if (forced) for (int x = -2; x <= 6; x++) for (int z = -2; z <= 3; z++) level.setChunkForced(x, z, false);
        } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[DRAGONDEATHAUDIT] FAILED cleanup", failure); }
        finally { server.halt(false); }
    }

    private static Actor actor(MinecraftServer server, String name) {
        Actor actor = new Actor();
        ClientInformation information = ClientInformation.createDefault();
        actor.player = new ServerPlayer(server, level, new GameProfile(UUID.randomUUID(), name), information);
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
