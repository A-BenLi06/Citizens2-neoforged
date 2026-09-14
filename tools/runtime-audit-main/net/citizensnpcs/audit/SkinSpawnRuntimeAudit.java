package net.citizensnpcs.audit;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.npc.entity.EntityHumanNPC;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

/** Checks the world entity index: player-only selectors intentionally omit NPCs removed from the world player list. */
@EventBusSubscriber(modid = "citizens")
public final class SkinSpawnRuntimeAudit {
    private static boolean checked;

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (checked || FixtureRuntimeAudit.elapsedTicks(event.getServer()) < 85) return;
        checked = true;
        var level = event.getServer().overworld();
        var bounds = new AABB(165, -62, 61, 168, -57, 64);
        for (var npc : CitizensAPI.getNPCRegistry()) {
            if (npc.getName().equals("P9SkinBob") && npc.isSpawned() && npc.getEntity() instanceof EntityHumanNPC player
                    && player.level() == level && player.isAlive() && bounds.intersects(player.getBoundingBox())
                    && level.getEntity(player.getUUID()) == player) {
                LoggerFactory.getLogger("citizens").info("[NPCTEST] PASS skin-player-npc-spawned");
                return;
            }
        }
        LoggerFactory.getLogger("citizens").error("[NPCTEST] FAIL skin-player-npc-spawned: no live indexed player NPC in the fixture bounds");
    }
}
