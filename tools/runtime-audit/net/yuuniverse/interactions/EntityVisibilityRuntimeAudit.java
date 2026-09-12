package net.yuuniverse.interactions;

import java.util.Set;
import net.citizensnpcs.api.CitizensAPI;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

/** Diagnostic snapshots around the datapack assertions; never substitutes for an assertion. */
@EventBusSubscriber(modid = "interactions")
public final class EntityVisibilityRuntimeAudit {
    private static final Set<String> NAMES = Set.of("P7HoloBob", "P7DisgBob", "P7FishBob",
            "P8SaveBob", "P8TplBob", "P8PlainBob", "P10RotBob", "P10RotPlainBob", "PathA", "PathB", "PathC");

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        int tick = net.citizensnpcs.audit.FixtureRuntimeAudit.elapsedTicks(event.getServer());
        if (tick != 79 && tick != 120 && tick != 200 && tick != 699) return;
        for (var npc : CitizensAPI.getNPCRegistry()) {
            if (!NAMES.contains(npc.getName())) continue;
            var entity = npc.getEntity();
            LoggerFactory.getLogger("interactions").info(
                    "[ENTITYVISIBILITY] tick={} npc={} spawned={} position={} removed={} visible={}",
                    tick, npc.getName(), npc.isSpawned(), entity == null ? null : entity.position(),
                    entity == null ? null : entity.isRemoved(),
                    entity != null && event.getServer().overworld().getEntity(entity.getUUID()) == entity);
            if (npc.getName().startsWith("Path") && entity instanceof net.minecraft.world.entity.Mob mob) {
                var path = mob.getNavigation().getPath();
                LoggerFactory.getLogger("interactions").info(
                        "[PATHDIAGNOSTIC] tick={} npc={} navigating={} done={} path={} progress={} reachable={}",
                        tick, npc.getName(), npc.getNavigator().isNavigating(), mob.getNavigation().isDone(),
                        path, path == null ? null : path.getNextNodeIndex(), path == null ? null : path.canReach());
            }
        }
        if (tick == 699) {
            for (var entity : event.getServer().overworld().getAllEntities()) {
                if (entity.hasCustomName() && entity.getCustomName().getString().equals("PathB")) {
                    var owner = net.citizensnpcs.npc.NPCRegistries.lookup(entity);
                    LoggerFactory.getLogger("interactions").info("[PATHDIAGNOSTIC] PathB entity={} position={} owner={}",
                            entity.getUUID(), entity.position(), owner == null ? null : owner.getId());
                }
            }
        }
    }
}
