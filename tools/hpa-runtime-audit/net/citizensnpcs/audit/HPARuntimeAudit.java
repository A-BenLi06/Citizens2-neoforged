package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.astar.pathfinder.ChunkSnapshotBlockSource;
import net.citizensnpcs.api.hpastar.HPAGraph;
import net.citizensnpcs.api.npc.*;
import net.citizensnpcs.api.trait.trait.*;
import net.citizensnpcs.api.util.*;
import net.citizensnpcs.commands.HPADebugService;
import net.citizensnpcs.trait.*;
import net.citizensnpcs.trait.waypoint.*;
import net.citizensnpcs.util.Messages;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class HPARuntimeAudit {
    private static MinecraftServer server;
    private static ServerLevel level;
    private static NPCRegistry registry, named;
    private static Actor alice, bob;
    private static CommandSourceStack source;
    private static final List<Actor> actors = new ArrayList<>();
    private static final List<NPC> npcs = new ArrayList<>();
    private static final Map<BlockPos, BlockState> changedBlocks = new LinkedHashMap<>();
    private static final Queue<Step> steps = new ArrayDeque<>();
    private static BooleanSupplier waiting = () -> true;
    private static boolean done, forced, initialized;
    private static int deadline, passed, stepNumber, sequence;
    private static NPC current;
    private static Waypoints waypoints;
    private static WaypointProvider provider;
    private static Probe probe;
    private static ThreadPoolExecutor worker;
    private static CountDownLatch release, entered;
    private static Future<?> oldRequest;
    private static PermissionUtil.PermissionResolver permissions;
    private static CompletableFuture<List<List<BlockPos>>> snapshotResult;
    private static int messageCount, tickAtStart;
    @FunctionalInterface private interface Step { void run() throws Exception; }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        server = event.getServer(); level = server.overworld();
        try {
            if (!forced) {
                check(Files.isRegularFile(Path.of("hpa-audit-fixture.txt")), "isolated_fixture");
                check(!CitizensAPI.getNPCRegistry().iterator().hasNext(), "empty_registry");
                for (int x = 0; x <= 5; x++) level.setChunkForced(x, 0, true);
                forced = true; deadline = server.getTickCount() + 3600;
            }
            if (server.getTickCount() > deadline) throw new AssertionError("timeout_step_" + stepNumber);
            if (!level.isPositionEntityTicking(new BlockPos(4, -60, 4))) return;
            for (int x = 0; x <= 5; x++) if (level.getChunkSource().getChunkNow(x, 0) == null) return;
            if (!initialized) { initialized = true; setup(); }
            for (Actor actor : actors) if (!actor.player.hasDisconnected()) actor.pump();
            if (!waiting.getAsBoolean()) return;
            waiting = () -> true;
            if (!steps.isEmpty()) { stepNumber++; steps.remove().run(); return; }
            close();
            LoggerFactory.getLogger("citizens").info("[HPAAUDIT] COMPLETE {} checks", passed);
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[HPAAUDIT] FAILED step=" + stepNumber + " passed=" + passed, failure); close();
        }
    }

    private static void setup() {
        registry = CitizensAPI.getNPCRegistry(); permissions = PermissionUtil.getPermissionResolver();
        alice = actor("HpaAlice", UUID.randomUUID()); bob = actor("HpaBob", UUID.randomUUID());
        source = alice.player.createCommandSourceStack();
        snapshots(); commands(); successfulCommands(); ownership(); shutdown();
    }

    private static void snapshots() {
        steps.add(() -> {
            for (int x = 8; x <= 12; x++) { block(x, -61, 8, Blocks.STONE.defaultBlockState()); block(x, 299, 8, Blocks.STONE.defaultBlockState()); }
            block(14, -60, 14, Blocks.WATER.defaultBlockState());
            ChunkSnapshotBlockSource snapshot = ChunkSnapshotBlockSource.capture(level, 0, 0, 16, 16);
            check(snapshot.getCapturedChunkCount() == 1, "snapshot_loaded_chunk_count");
            check(snapshot.getMinBuildHeight() == level.getMinBuildHeight() && snapshot.getMaxBuildHeight() == level.getMaxBuildHeight(), "snapshot_native_height_bounds");
            check(snapshot.getCollisionBox(8, 299, 8).equals(new BoundingBox(8, 299, 8, 9, 300, 9)), "snapshot_collision_coordinates");
            check(snapshot.getFluidState(new BlockPos(14, -60, 14)).is(net.minecraft.tags.FluidTags.WATER), "snapshot_native_fluid");
            check(snapshot.getBlockEntity(BlockPos.ZERO) == null, "snapshot_has_no_live_block_entities");
            block(10, -61, 8, Blocks.AIR.defaultBlockState()); block(10, 299, 8, Blocks.AIR.defaultBlockState());
            check(snapshot.getBlockAt(10, -61, 8).is(Blocks.STONE) && snapshot.getBlockAt(10, 299, 8).is(Blocks.STONE), "palettes_do_not_follow_live_edits");
            check(!snapshot.isBlockAvailable(16, -60, 8) && snapshot.getBlockAt(16, -60, 8).isAir(), "uncaptured_is_distinct_from_known_air");
            check(!snapshot.isBlockAvailable(8, level.getMaxBuildHeight(), 8), "snapshot_rejects_out_of_height_data");
            check(level.getChunkSource().getChunkNow(12000, 12000) == null, "far_chunk_initially_absent");
            check(ChunkSnapshotBlockSource.capture(level, 192000, 192000, 192016, 192016).getCapturedChunkCount() == 0, "unloaded_snapshot_empty");
            check(level.getChunkSource().getChunkNow(12000, 12000) == null, "capture_does_not_load_chunks");
            snapshotResult = CompletableFuture.supplyAsync(() -> {
                if (server.isSameThread()) throw new AssertionError("expected_background_thread");
                try { ChunkSnapshotBlockSource.capture(level, 0, 0, 16, 16); throw new AssertionError("off_thread_capture_accepted"); }
                catch (IllegalStateException expected) {}
                HPAGraph graph = new HPAGraph(snapshot, 0, 0, 0); graph.addClusters(0, 0);
                return List.of(graph.findPath(new BlockPos(8, -60, 8), new BlockPos(12, -60, 8)).getBlocks(),
                        graph.findPath(new BlockPos(8, 300, 8), new BlockPos(12, 300, 8)).getBlocks());
            });
            waiting = snapshotResult::isDone;
        });
        steps.add(() -> {
            var paths = snapshotResult.join();
            check(paths.get(0).size() == 5 && paths.get(1).size() == 5, "background_paths_use_negative_and_high_snapshot_layers");
            restoreBlocks();
        });
    }

    private static void commands() {
        steps.add(() -> {
            current = npc(false, false); select(current);
            PermissionUtil.Attachment grant = PermissionUtil.grantTemporary(alice.player, List.of("citizens.admin"));
            try { reject("wp hpa"); } finally { grant.remove(); }
            check(field(HPADebugService.class, null, "session") == null, "permission_grant_does_not_replace_operator_requirement");
            server.getPlayerList().op(alice.player.getGameProfile()); server.getPlayerList().op(bob.player.getGameProfile());
            reject("waypoints hpa unexpected"); reject("wp hpa --unknown value");
            CommandSourceStack console = server.createCommandSourceStack(); CitizensAPI.getDefaultNPCSelector().select(console, current);
            try { server.getCommands().getDispatcher().execute("wp hpa", console); throw new AssertionError("console_accepted"); }
            catch (CommandSyntaxException expected) {
                check(expected.getRawMessage().getString().contains(TextParser.strip(Translator.translate(
                        net.citizensnpcs.api.command.CommandMessages.MUST_BE_INGAME))), "console_requires_player_without_cast_failure");
            }
            current.getOrAddTrait(CurrentLocation.class).setLocation(new Location(server.getLevel(Level.NETHER), 4, 70, 4));
            reject("wp hpa");
            current.getOrAddTrait(CurrentLocation.class).setLocation(new Location(level, Double.NaN, -60, 4)); reject("wp hpa");
            at(current, 600, -60, 4); reject("wp hpa");
            // Moving an admitted player can itself load chunks. Keep the player still and move only stored NPC data.
            check(level.getChunkSource().getChunkNow(12, 0) == null, "unspawned_endpoint_initially_unloaded");
            at(current, 192, -60, 4); reject("wp hpa");
            check(level.getChunkSource().getChunkNow(12, 0) == null, "rejected_command_does_not_load_endpoint");
            at(current, 4, -60, 4); alice.player.setPos(12, -60, 4);
            Setting.HPA_DEBUG_MAX_REQUESTS.set(0); reject("wp hpa"); Setting.HPA_DEBUG_MAX_REQUESTS.set(4);
            Setting.HPA_DEBUG_TIMEOUT.set("invalid"); reject("wp hpa");
            Setting.HPA_DEBUG_TIMEOUT.set("PT-1000000000H"); reject("wp hpa"); Setting.HPA_DEBUG_TIMEOUT.set("30s");
            check(!current.hasTrait(Waypoints.class), "invalid_hpa_does_not_allocate_waypoints"); current.destroy();
        });
    }

    private static void successfulCommands() {
        for (String mode : List.of("unspawned", "world", "packet", "cross-region", "high", "unreachable")) {
            steps.add(() -> {
                boolean spawned = mode.equals("world") || mode.equals("packet");
                current = npc(mode.equals("packet"), spawned); select(current); alice.player.messages.clear();
                if (spawned) {
                    waypoints = current.getOrAddTrait(Waypoints.class); waypoints.setWaypointProvider("guided");
                    provider = waypoints.getCurrentProvider(); provider.setPaused(true);
                    current.getNavigator().getDefaultParameters().locationStrategyFactory((npc, parameters, target) -> probe = new Probe(target));
                    current.getNavigator().setTarget(new Location(level, 20, -60, 4));
                }
                if (mode.equals("cross-region")) alice.player.setPos(84, -60, 4);
                if (mode.equals("high")) {
                    for (int x = 4; x <= 12; x++) block(x, 299, 4, Blocks.STONE.defaultBlockState());
                    at(current, 4, 300, 4); alice.player.setPos(12, 300, 4);
                }
                if (mode.equals("unreachable")) { at(current, 4, 280, 4); alice.player.setPos(12, 280, 4); }
                tickAtStart = server.getTickCount(); run(mode.equals("world") ? "waypoints hpa" : "wp hpa");
                check(count(alice, Messages.HPA_STARTED) == 1, "native_hpa_feedback_" + mode);
                waiting = () -> emptyRequests();
            });
            steps.add(() -> {
                check(count(alice, mode.equals("unreachable") ? Messages.HPA_NO_PATH : Messages.HPA_COMPLETE) == 1, "native_hpa_result_" + mode);
                check(server.getTickCount() > tickAtStart, "server_ticks_continue_during_hpa_" + mode);
                if (mode.equals("world") || mode.equals("packet")) {
                    check(current.getNavigator().getPathStrategy() == probe, "hpa_preserves_current_navigation_" + mode);
                    check(waypoints.getCurrentProvider() == provider && provider.isPaused(), "hpa_preserves_waypoint_provider_" + mode);
                } else check(!current.hasTrait(Waypoints.class), "hpa_has_no_waypoint_side_effect_" + mode);
                current.destroy(); alice.player.setPos(12, -60, 4); restoreBlocks();
            });
        }
    }

    private static void ownership() {
        holdWorker();
        steps.add(() -> {
            current = npc(false, false); select(current); alice.player.messages.clear();
            run("wp hpa"); oldRequest = task(alice.player);
            at(current, 600, -60, 4); reject("wp hpa"); at(current, 4, -60, 4);
            check(task(alice.player) == oldRequest && !oldRequest.isCancelled(), "invalid_request_preserves_pending_request");
            Setting.HPA_DEBUG_MAX_REQUESTS.set(1);
            CitizensAPI.getDefaultNPCSelector().select(bob.player.createCommandSourceStack(), current);
            reject("wp hpa", bob.player.createCommandSourceStack());
            check(requests().size() == 1, "global_request_admission_is_bounded");
            run("waypoints hpa");
            check(oldRequest.isCancelled() && task(alice.player) != oldRequest, "new_request_cancels_old_request");
            check(worker.getQueue().size() == 1, "replacement_removes_cancelled_queued_work");
            Setting.HPA_DEBUG_MAX_REQUESTS.set(4); release.countDown(); waiting = () -> emptyRequests();
        });
        steps.add(() -> { check(count(alice, Messages.HPA_COMPLETE) == 1, "only_latest_request_reports_completion"); current.destroy(); });

        for (String change : List.of("destroy", "registry", "op", "permission", "provider-failure", "world", "session")) {
            holdWorker();
            steps.add(() -> {
                if (change.equals("registry")) { named = CitizensAPI.createInMemoryNPCRegistry("hpa-owned"); current = npc(named, false, false); }
                else current = npc(false, false);
                select(current); alice.player.messages.clear(); run("wp hpa"); oldRequest = task(alice.player);
                switch (change) {
                    case "destroy" -> current.destroy();
                    case "registry" -> CitizensAPI.removeNamedNPCRegistry("hpa-owned");
                    case "op" -> server.getPlayerList().deop(alice.player.getGameProfile());
                    case "permission" -> {
                        // Registered nodes use the native handler; its default resolver consults declared parent grants.
                        PermissionUtil.setPermissionResolver((player, permission) -> player == alice.player ? Boolean.FALSE
                                : permissions == null ? null : permissions.check(player, permission));
                        check(!PermissionUtil.hasPermission(alice.player, "citizens.admin"), "native_permission_denial_installed");
                    }
                    case "provider-failure" -> PermissionUtil.setPermissionResolver((player, permission) -> { throw new IllegalStateException("hpa-audit-injected-permission-failure"); });
                    case "world" -> current.getOrAddTrait(CurrentLocation.class).setLocation(new Location(server.getLevel(Level.NETHER), 4, 70, 4));
                    case "session" -> {
                        Actor previous = alice; server.getPlayerList().remove(previous.player); previous.channel.finishAndReleaseAll();
                        actors.remove(previous); alice = actor("HpaAlice", previous.player.getUUID()); source = alice.player.createCommandSourceStack();
                    }
                }
                int cancellationDeadline = server.getTickCount() + 5;
                waiting = () -> {
                    if (oldRequest.isCancelled()) return true;
                    if (server.getTickCount() > cancellationDeadline) throw new AssertionError("stale_request_not_promptly_cancelled_" + change);
                    return false;
                };
            });
            steps.add(() -> {
                check(oldRequest.isCancelled() && worker.getQueue().isEmpty(), "stale_request_released_" + change);
                check(count(alice, Messages.HPA_COMPLETE) == 0, "stale_result_suppressed_" + change);
                check(count(alice, Messages.HPA_TIMEOUT) == 0, "stale_cancellation_is_not_timeout_" + change);
                if (change.equals("provider-failure")) check(count(alice, Messages.HPA_FAILED) == 1, "provider_exception_reports_failure_without_stopping_server");
                release.countDown(); PermissionUtil.setPermissionResolver(permissions); server.getPlayerList().op(alice.player.getGameProfile());
                current.destroy(); named = null;
            });
        }

        holdWorker();
        steps.add(() -> {
            current = npc(false, false); select(current); alice.player.messages.clear();
            Setting.HPA_DEBUG_TIMEOUT.set("0.001s"); run("wp hpa"); oldRequest = task(alice.player);
            waiting = oldRequest::isCancelled;
        });
        steps.add(() -> {
            check(oldRequest.isCancelled() && count(alice, Messages.HPA_TIMEOUT) == 1, "timeout_cancels_and_reports_request");
            check(worker.getQueue().isEmpty(), "timeout_releases_queue"); release.countDown();
            Setting.HPA_DEBUG_TIMEOUT.set("30s"); current.destroy();
        });
    }

    private static void shutdown() {
        holdWorker();
        steps.add(() -> {
            current = npc(false, false); select(current); run("wp hpa"); oldRequest = task(alice.player);
            HPADebugService.stopping(new ServerStoppingEvent(server));
            check(oldRequest.isCancelled() && worker.isShutdown(), "shutdown_cancels_requests_and_stops_worker");
            check(field(HPADebugService.class, null, "session") == null, "shutdown_releases_server_session");
            release.countDown(); waiting = worker::isTerminated;
        });
        steps.add(() -> { check(worker.isTerminated(), "shutdown_worker_terminates"); current.destroy(); });
    }

    private static void holdWorker() {
        steps.add(() -> {
            Object session = field(HPADebugService.class, null, "session"); worker = (ThreadPoolExecutor) field(session.getClass(), session, "worker");
            entered = new CountDownLatch(1); release = new CountDownLatch(1);
            CountDownLatch gate = release, started = entered;
            worker.execute(() -> { started.countDown(); try { gate.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } });
            waiting = () -> entered.getCount() == 0;
        });
    }

    private static Object field(Class<?> type, Object instance, String name) {
        try { var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(instance); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static Map<?, ?> requests() {
        Object session = field(HPADebugService.class, null, "session");
        return session == null ? Map.of() : (Map<?, ?>) field(session.getClass(), session, "requests");
    }
    private static boolean emptyRequests() { return requests().isEmpty(); }
    private static Future<?> task(ServerPlayer player) {
        Object request = requests().get(player); if (request == null) throw new AssertionError("missing_request");
        return (Future<?>) field(request.getClass(), request, "task");
    }
    private static int count(Actor actor, String key) {
        String marker = "HPA_AUDIT_ARGUMENT";
        String translated = Messaging.stripColor(Messaging.tr(key, marker, marker, marker, marker, marker, marker));
        String prefix = translated.split(marker, 2)[0].trim();
        return (int) actor.player.messages.stream().filter(message -> message.contains(prefix)).count();
    }
    private static void block(int x, int y, int z, BlockState state) {
        BlockPos pos = new BlockPos(x, y, z); changedBlocks.putIfAbsent(pos, level.getBlockState(pos)); level.setBlock(pos, state, 3);
    }
    private static void restoreBlocks() { changedBlocks.forEach((pos, state) -> level.setBlock(pos, state, 3)); changedBlocks.clear(); }
    private static NPC npc(boolean packet, boolean spawn) { return npc(registry, packet, spawn); }
    private static NPC npc(NPCRegistry target, boolean packet, boolean spawn) {
        NPC npc = target.createNPC(EntityType.COW, "HpaOpt" + sequence++); npcs.add(npc);
        npc.getOrAddTrait(Owner.class).setOwner(alice.player.getUUID()); npc.getOrAddTrait(LookClose.class).setEnabled(false);
        at(npc, 4, -60, 4); if (packet) npc.getOrAddTrait(PacketNPC.class);
        if (spawn) { check(npc.spawn(new Location(level, 4, -60, 4)), "fixture_spawn"); npc.getEntity().setNoGravity(true); }
        return npc;
    }
    private static void at(NPC npc, double x, double y, double z) { npc.getOrAddTrait(CurrentLocation.class).setLocation(new Location(level, x, y, z)); }
    private static void select(NPC npc) { CitizensAPI.getDefaultNPCSelector().select(source, npc); }
    private static void run(String command) throws Exception { check(server.getCommands().getDispatcher().execute(command, source) == 1, "dispatch_" + command); alice.pump(); }
    private static void reject(String command) throws Exception {
        reject(command, source);
    }
    private static void reject(String command, CommandSourceStack sender) throws Exception {
        try { check(server.getCommands().getDispatcher().execute(command, sender) == 0, "reject_" + command); }
        catch (CommandSyntaxException expected) { check(true, "reject_" + command); }
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); passed++; }

    private static final class Probe extends net.citizensnpcs.api.ai.AbstractPathStrategy {
        final Location target;
        Probe(Location target) { super(net.citizensnpcs.api.ai.TargetType.LOCATION); this.target = target; }
        public Location getCurrentDestination() { return target; }
        public Location getTargetAsLocation() { return target; }
        public Iterable<Vec3> getPath() { return List.of(new Vec3(target.getX(), target.getY(), target.getZ())); }
        public boolean update() { return false; }
        public void stop() {}
    }

    private static void close() {
        if (done) return; done = true;
        try {
            if (release != null) release.countDown();
            HPADebugService.stopping(new ServerStoppingEvent(server));
            PermissionUtil.setPermissionResolver(permissions);
            Setting.HPA_DEBUG_MAX_REGIONS.set(4); Setting.HPA_DEBUG_MAX_REQUESTS.set(4); Setting.HPA_DEBUG_TIMEOUT.set("30s");
            for (NPC npc : npcs) npc.destroy();
            if (named != null) CitizensAPI.removeNamedNPCRegistry("hpa-owned");
            for (Actor actor : actors) {
                server.getPlayerList().deop(actor.player.getGameProfile()); server.getPlayerList().remove(actor.player); actor.channel.finishAndReleaseAll();
            }
            restoreBlocks(); for (int x = 0; x <= 5; x++) if (forced) level.setChunkForced(x, 0, false);
        } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[HPAAUDIT] FAILED cleanup", failure); }
        finally { server.halt(false); }
    }

    private static Actor actor(String name, UUID uuid) {
        Actor actor = new Actor(); ClientInformation information = ClientInformation.createDefault();
        actor.player = new AuditPlayer(name, uuid); Connection connection = new Connection(PacketFlow.SERVERBOUND);
        actor.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                connection.configurePacketHandler(channel.pipeline());
                channel.pipeline().addLast("hpa-audit-capture", new ChannelOutboundHandlerAdapter() {
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
        actor.player.setPos(12, -60, 4); actor.player.setNoGravity(true); actors.add(actor); return actor;
    }
    private static final class AuditPlayer extends ServerPlayer {
        final List<String> messages = new ArrayList<>();
        AuditPlayer(String name, UUID uuid) { super(HPARuntimeAudit.server, HPARuntimeAudit.level, new GameProfile(uuid, name), ClientInformation.createDefault()); }
        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
    }
    private static final class Actor {
        AuditPlayer player; EmbeddedChannel channel; int batches;
        final List<Integer> teleports = new ArrayList<>();
        void capture(Packet<?> packet) {
            if (packet instanceof ClientboundBundlePacket bundle) { bundle.subPackets().forEach(this::capture); return; }
            if (packet instanceof ClientboundChunkBatchFinishedPacket) batches++;
            if (packet instanceof ClientboundPlayerPositionPacket position) teleports.add(position.getId());
        }
        void pump() {
            channel.runPendingTasks();
            while (batches > 0) { batches--; player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(16)); }
            for (int id : List.copyOf(teleports)) player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(id));
            teleports.clear(); Object value; while ((value = channel.readOutbound()) != null) ReferenceCountUtil.release(value);
        }
    }
}
