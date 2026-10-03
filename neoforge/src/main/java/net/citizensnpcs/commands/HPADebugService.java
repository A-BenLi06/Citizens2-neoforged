package net.citizensnpcs.commands;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.astar.pathfinder.ChunkSnapshotBlockSource;
import net.citizensnpcs.api.command.CommandMessages;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.command.exception.NoPermissionsException;
import net.citizensnpcs.api.hpastar.HPAGraph;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.util.Messages;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Server-owned, bounded HPA diagnostics. Only immutable block snapshots cross into the worker. */
@EventBusSubscriber(modid = "citizens")
public final class HPADebugService {
    private static Session session;

    private HPADebugService() {}

    public static void start(ServerPlayer player, NPC npc) throws CommandException {
        MinecraftServer server = player.getServer();
        if (!server.isSameThread()) throw new IllegalStateException("Start HPA diagnostics on the server thread");
        if (!server.getPlayerList().isOp(player.getGameProfile())) throw new CommandException(Messages.HPA_REQUIRES_OP);
        if (!PermissionUtil.hasPermission(player, "citizens.admin")) throw new NoPermissionsException();
        if (!currentPlayer(server, player) || npc == null || !currentNPC(npc, npc.getOwningRegistry()))
            throw new CommandException(Messages.NPC_NOT_FOUND);
        Location stored = npc.getStoredLocation();
        ServerLevel level = player.serverLevel();
        if (stored == null || stored.getWorld() == null || !Double.isFinite(stored.getX())
                || !Double.isFinite(stored.getY()) || !Double.isFinite(stored.getZ()))
            throw new CommandException(CommandMessages.INVALID_LOCATION);
        if (stored.getWorld() != level) throw new CommandException(Messages.HPA_DIFFERENT_WORLD);
        BlockPos start = new BlockPos(stored.getBlockX(), stored.getBlockY(), stored.getBlockZ());
        BlockPos goal = player.blockPosition().immutable();
        if (!level.isInWorldBounds(start) || !level.isInWorldBounds(goal)) throw new CommandException(CommandMessages.INVALID_LOCATION);

        int maxRegions, maxRequests;
        java.time.Duration duration;
        try {
            maxRegions = Setting.HPA_DEBUG_MAX_REGIONS.asInt();
            maxRequests = Setting.HPA_DEBUG_MAX_REQUESTS.asInt();
            duration = Setting.HPA_DEBUG_TIMEOUT.asDuration();
        } catch (RuntimeException invalid) {
            throw new CommandException(Messages.HPA_INVALID_SETTINGS);
        }
        if (maxRegions <= 0 || maxRequests <= 0 || duration.isNegative() || duration.isZero())
            throw new CommandException(Messages.HPA_DISABLED);
        long timeout;
        try { timeout = duration.toNanos(); }
        catch (ArithmeticException overflow) { timeout = Long.MAX_VALUE; }
        int targetX = Math.floorDiv(goal.getX() - start.getX(), HPAGraph.REGION_SIZE);
        int targetZ = Math.floorDiv(goal.getZ() - start.getZ(), HPAGraph.REGION_SIZE);
        int minX = Math.min(0, targetX), maxX = Math.max(0, targetX), minZ = Math.min(0, targetZ), maxZ = Math.max(0, targetZ);
        long count = ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1);
        if (count > maxRegions) throw new CommandException(Messages.HPA_REGION_LIMIT, count, maxRegions);
        if (session != null && session.server != server) {
            session.close();
            session = null;
        }
        if (session != null) {
            session.reap();
            if (!session.requests.containsKey(player) && session.requests.size() >= maxRequests)
                throw new CommandException(Messages.HPA_BUSY, maxRequests);
        }

        List<BlockPos> regions = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            regions.add(new BlockPos(start.getX() + x * HPAGraph.REGION_SIZE, 0, start.getZ() + z * HPAGraph.REGION_SIZE));
        }
        ChunkSnapshotBlockSource snapshot = ChunkSnapshotBlockSource.capture(level,
                start.getX() + minX * HPAGraph.REGION_SIZE, start.getZ() + minZ * HPAGraph.REGION_SIZE,
                start.getX() + (maxX + 1) * HPAGraph.REGION_SIZE, start.getZ() + (maxZ + 1) * HPAGraph.REGION_SIZE);
        if (!snapshot.isBlockAvailable(start.getX(), start.getY(), start.getZ())
                || !snapshot.isBlockAvailable(goal.getX(), goal.getY(), goal.getZ()))
            throw new CommandException(Messages.HPA_UNLOADED);
        if (session == null) session = new Session(server);
        Request previous = session.requests.get(player);
        if (previous != null) session.cancel(previous);
        Request request = new Request(session, player, npc, level, start, goal, snapshot.getCapturedChunkCount(), regions.size(), timeout);
        request.task = new FutureTask<>(() -> {
            Result result;
            try {
                HPAGraph graph = new HPAGraph(snapshot, start.getX(), start.getY(), start.getZ());
                graph.addClusters(regions);
                List<BlockPos> path = List.copyOf(graph.findPath(start, goal).getBlocks());
                result = new Result(path, graph.clusters.stream().mapToInt(List::size).sum(), graph.clusters.size(), null);
            } catch (CancellationException cancelled) {
                return null;
            } catch (RuntimeException failure) {
                result = new Result(List.of(), 0, 0, failure);
            }
            Result completed = result;
            if (!Thread.currentThread().isInterrupted()) {
                try { server.execute(() -> request.owner.complete(request, completed)); }
                catch (RejectedExecutionException stopped) { /* The owning server has stopped. */ }
            }
            return null;
        });
        session.requests.put(player, request);
        session.worker.execute(request.task);
        Messaging.sendTr(player.createCommandSourceStack(), Messages.HPA_STARTED, start.toShortString(), goal.toShortString(),
                request.chunks, request.regions);
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (session != null && session.server == event.getServer()) session.reap();
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        if (session == null || session.server != event.getServer()) return;
        Session previous = session;
        session = null;
        previous.close();
    }

    private static boolean currentPlayer(MinecraftServer server, ServerPlayer player) {
        return !player.isRemoved() && !player.hasDisconnected() && server.getPlayerList().getPlayer(player.getUUID()) == player;
    }

    private static boolean currentNPC(NPC npc, NPCRegistry registry) {
        if (!CitizensAPI.hasImplementation() || registry == null || npc.getOwningRegistry() != registry
                || registry.getById(npc.getId()) != npc || registry.getByUniqueId(npc.getUniqueId()) != npc) return false;
        for (NPCRegistry current : CitizensAPI.getNPCRegistries()) if (current == registry) return true;
        return false;
    }

    private static final class Session {
        final MinecraftServer server;
        final Map<ServerPlayer, Request> requests = new IdentityHashMap<>();
        final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, "Citizens HPA");
            thread.setDaemon(true);
            return thread;
        });

        Session(MinecraftServer server) { this.server = server; }

        boolean valid(Request request) {
            if (session != this || !currentPlayer(server, request.player)) return false;
            try {
                if (!currentNPC(request.npc, request.registry) || request.player.serverLevel() != request.level
                        || !server.getPlayerList().isOp(request.player.getGameProfile())
                        || !PermissionUtil.hasPermission(request.player, "citizens.admin")) return false;
                Location at = request.npc.getStoredLocation();
                return at != null && at.getWorld() == request.level;
            } catch (RuntimeException failure) {
                // Permission and custom NPC providers can fail after admission. Retire this job instead of failing a tick.
                boolean owned = requests.get(request.player) == request;
                cancel(request);
                if (owned) {
                    org.slf4j.LoggerFactory.getLogger("citizens").error("HPA request validation failed", failure);
                    Messaging.sendErrorTr(request.player.createCommandSourceStack(), Messages.HPA_FAILED);
                }
                return false;
            }
        }

        void complete(Request request, Result result) {
            if (requests.get(request.player) != request) return;
            if (!valid(request)) { cancel(request); return; }
            if (requests.get(request.player) != request) return;
            if (request.expired()) { timeout(request); return; }
            requests.remove(request.player);
            if (result.failure != null) {
                Messaging.sendErrorTr(request.player.createCommandSourceStack(), Messages.HPA_FAILED);
                org.slf4j.LoggerFactory.getLogger("citizens").error("HPA graph calculation failed", result.failure);
            } else if (result.path.isEmpty()) {
                Messaging.sendTr(request.player.createCommandSourceStack(), Messages.HPA_NO_PATH, request.start.toShortString(),
                        request.goal.toShortString(), request.chunks, request.regions);
            } else {
                Messaging.logTr(Messages.HPA_PATH, request.start.toShortString(), request.goal.toShortString(), result.path);
                Messaging.sendTr(request.player.createCommandSourceStack(), Messages.HPA_COMPLETE, result.path.size(), result.clusters,
                        result.levels, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - request.created));
            }
        }

        void reap() {
            for (Request request : List.copyOf(requests.values())) {
                if (!valid(request)) cancel(request);
                else if (request.expired()) timeout(request);
            }
        }

        void timeout(Request request) {
            if (requests.get(request.player) != request) return;
            cancel(request);
            Messaging.sendErrorTr(request.player.createCommandSourceStack(), Messages.HPA_TIMEOUT);
        }

        void cancel(Request request) {
            if (requests.get(request.player) == request) requests.remove(request.player);
            request.task.cancel(true);
            worker.remove(request.task);
        }

        void close() {
            for (Request request : List.copyOf(requests.values())) cancel(request);
            worker.shutdownNow();
        }
    }

    private static final class Request {
        final Session owner;
        final ServerPlayer player;
        final NPC npc;
        final NPCRegistry registry;
        final ServerLevel level;
        final BlockPos start, goal;
        final int chunks, regions;
        final long created = System.nanoTime(), timeout;
        FutureTask<Void> task;

        Request(Session owner, ServerPlayer player, NPC npc, ServerLevel level, BlockPos start, BlockPos goal,
                int chunks, int regions, long timeout) {
            this.owner = owner;
            this.player = player;
            this.npc = npc;
            registry = npc.getOwningRegistry();
            this.level = level;
            this.start = start;
            this.goal = goal;
            this.chunks = chunks;
            this.regions = regions;
            this.timeout = timeout;
        }

        boolean expired() { return System.nanoTime() - created >= timeout; }
    }

    private record Result(List<BlockPos> path, int clusters, int levels, RuntimeException failure) {}
}
