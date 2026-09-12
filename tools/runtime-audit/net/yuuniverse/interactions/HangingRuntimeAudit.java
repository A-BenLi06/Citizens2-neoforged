package net.yuuniverse.interactions;

import java.util.ArrayList;
import java.util.List;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.util.Location;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "interactions")
public final class HangingRuntimeAudit {
    private static boolean ran;
    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (ran || net.citizensnpcs.audit.FixtureRuntimeAudit.elapsedTicks(event.getServer()) < 85) return;
        ran = true;
        var registry = CitizensAPI.createAnonymousNPCRegistry(new MemoryNPCDataStore());
        try {
            var level = event.getServer().overworld();
            for (var type : List.of(EntityType.PAINTING, EntityType.ITEM_FRAME, EntityType.GLOW_ITEM_FRAME)) {
                var npc = registry.createNPC(type, "HangingAudit");
                try {
                    if (!npc.spawn(new Location(level, 0, -40, 0))) throw new AssertionError("NPC spawn " + type);
                    var actor = npc.getEntity();
                    for (int i = 0; i < 105; i++) actor.tick();
                    check(!actor.isRemoved(), "unsupported_npc_survives_" + type);
                    npc.setProtected(false);
                    for (int i = 0; i < 105; i++) actor.tick();
                    check(!actor.isRemoved(), "vulnerable_npc_keeps_upstream_tick_behavior_" + type);
                } finally {
                    npc.destroy();
                }
                var vanilla = type.create(level);
                try {
                    vanilla.setPos(0, -40, 0);
                    vanilla.captureDrops(new ArrayList<>());
                    for (int i = 0; i < 105; i++) vanilla.tick();
                    check(vanilla.isRemoved(), "unsupported_vanilla_still_removed_" + type);
                } finally {
                    vanilla.discard();
                }
            }
            LoggerFactory.getLogger("interactions").info("[HANGINGAUDIT] COMPLETE 9/9");
        } catch (Throwable failure) {
            LoggerFactory.getLogger("interactions").error("[HANGINGAUDIT] FAILED", failure);
        }
    }
    private static void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        LoggerFactory.getLogger("interactions").info("[HANGINGAUDIT] PASS {}", name);
    }
}
