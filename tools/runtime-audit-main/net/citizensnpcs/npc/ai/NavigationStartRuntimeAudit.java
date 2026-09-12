package net.citizensnpcs.npc.ai;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.util.Location;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class NavigationStartRuntimeAudit {
    private static boolean ran;
    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (ran || net.citizensnpcs.audit.FixtureRuntimeAudit.elapsedTicks(event.getServer()) < 90) return;
        ran = true;
        var registry = CitizensAPI.createAnonymousNPCRegistry(new MemoryNPCDataStore());
        var npc = registry.createNPC(EntityType.PIG, "NavigationStartAudit");
        MCNavigationStrategy strategy = null;
        try {
            var level = event.getServer().overworld();
            if (!npc.spawn(new Location(level, 62, -60, 102))) throw new AssertionError("spawn failed");
            var mob = (Mob) npc.getEntity();
            strategy = new MCNavigationStrategy(npc, new Location(level, 66, -60, 102),
                    npc.getNavigator().getDefaultParameters());
            mob.setOnGround(false);
            check(mob.getNavigation().createPath(new BlockPos(66, -60, 102), 0) == null,
                    "vanilla_refuses_request_after_ground_flag_reset");
            strategy.update();
            check(strategy.getCancelReason() == null && mob.getNavigation().getPath() != null
                    && !mob.getNavigation().isDone(), "deferred_first_request_restores_ground_flag");
            LoggerFactory.getLogger("citizens").info("[NAVSTARTAUDIT] COMPLETE 2/2");
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[NAVSTARTAUDIT] FAILED", failure);
        } finally {
            if (strategy != null) strategy.stop();
            npc.destroy();
        }
    }
    private static void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        LoggerFactory.getLogger("citizens").info("[NAVSTARTAUDIT] PASS {}", name);
    }
}
