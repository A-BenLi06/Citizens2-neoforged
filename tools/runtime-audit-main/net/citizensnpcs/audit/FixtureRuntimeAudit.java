package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

/** Starts the opt-in fixture only after its chunk and entity data are ready. */
@EventBusSubscriber(modid = "citizens")
public final class FixtureRuntimeAudit {
    private static final Set<ChunkPos> chunks = new LinkedHashSet<>();
    private static int startedAt = -1;
    private static boolean failed;

    public static int elapsedTicks(MinecraftServer server) {
        return startedAt < 0 ? -1 : server.getTickCount() - startedAt;
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (startedAt >= 0 || failed) return;
        var server = event.getServer();
        var level = server.overworld();
        try {
            if (chunks.isEmpty()) {
                var run = server.getWorldPath(LevelResource.DATAPACK_DIR)
                        .resolve("npctest/data/npctest/function/run.mcfunction");
                var region = Pattern.compile("^forceload add (-?\\d+) (-?\\d+) (-?\\d+) (-?\\d+)$");
                for (String line : Files.readAllLines(run)) {
                    var match = region.matcher(line.strip());
                    if (!match.matches()) continue;
                    int x1 = Math.floorDiv(Integer.parseInt(match.group(1)), 16);
                    int z1 = Math.floorDiv(Integer.parseInt(match.group(2)), 16);
                    int x2 = Math.floorDiv(Integer.parseInt(match.group(3)), 16);
                    int z2 = Math.floorDiv(Integer.parseInt(match.group(4)), 16);
                    for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
                        for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) chunks.add(new ChunkPos(x, z));
                }
                if (chunks.isEmpty()) throw new IllegalStateException("Fixture declares no forced regions");
                for (var chunk : chunks) level.setChunkForced(chunk.x, chunk.z, true);
            }
            var pending = chunks.stream().filter(chunk -> !level.areEntitiesLoaded(chunk.toLong())
                    || !level.isPositionEntityTicking(new BlockPos(chunk.getMinBlockX(), 0, chunk.getMinBlockZ()))).toList();
            if (!pending.isEmpty()) {
                if (server.getTickCount() >= 1200) throw new IllegalStateException("Fixture loading timed out: " + pending);
                return;
            }
            startedAt = server.getTickCount();
            LoggerFactory.getLogger("citizens").info("[FIXTUREAUDIT] READY {} chunks at server tick {}", chunks.size(), startedAt);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withPermission(4), "function npctest:run");
        } catch (Exception failure) {
            failed = true;
            LoggerFactory.getLogger("citizens").error("[FIXTUREAUDIT] FAILED", failure);
            server.halt(false);
        }
    }
}
