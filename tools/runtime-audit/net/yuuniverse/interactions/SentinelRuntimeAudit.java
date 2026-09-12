package net.yuuniverse.interactions;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.trait.SentinelTrait;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

/** Tests the real trait and entity attributes, without adding guards to the saved NPC registry. */
@EventBusSubscriber(modid = "interactions")
public final class SentinelRuntimeAudit {
    private static boolean ran;

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (ran || net.citizensnpcs.audit.FixtureRuntimeAudit.elapsedTicks(event.getServer()) < 25) return;
        ran = true;
        var registry = CitizensAPI.createAnonymousNPCRegistry(new MemoryNPCDataStore());
        var npc = registry.createNPC(EntityType.PIG, "SentinelAudit");
        try {
            var values = new MemoryDataKey();
            values.setInt("healRate", 100);
            values.setDouble("health", 40);
            values.setBoolean("invincible", false);
            var trait = PersistenceLoader.load(new SentinelTrait(), values);
            npc.addTrait(trait);
            check(npc.spawn(new Location(event.getServer().overworld(), 0, -60, 0)), "spawn");
            var entity = (LivingEntity) npc.getEntity();
            check(entity.getMaxHealth() == 40 && entity.getHealth() == 40, "health_applied_at_spawn");
            check(!npc.isProtected() && !entity.isInvulnerable(), "non_invincible_guard_is_vulnerable");
            entity.setHealth(10);
            for (int i = 0; i < 100; i++) trait.run();
            check(entity.getHealth() == 10, "no_healing_before_legacy_threshold");
            trait.run();
            check(entity.getHealth() == 11, "heals_one_hit_point_after_threshold");
            entity.setHealth(39.5F);
            for (int i = 0; i < 101; i++) trait.run();
            check(entity.getHealth() == 40, "healing_capped_at_maximum");
            // Persistence intentionally omits default-valued fields; use a non-default rate for the round trip.
            values.setInt("healRate", 50);
            PersistenceLoader.load(trait, values);
            var saved = new MemoryDataKey();
            PersistenceLoader.save(trait, saved);
            check(saved.getInt("healRate") == 50, "heal_rate_roundtrip");
            values.setInt("healRate", 0);
            PersistenceLoader.load(trait, values);
            entity.setHealth(10);
            for (int i = 0; i < 202; i++) trait.run();
            check(entity.getHealth() == 10, "zero_rate_disables_healing");
            values.setBoolean("invincible", true);
            PersistenceLoader.load(trait, values);
            trait.onSpawn();
            check(npc.isProtected() && entity.isInvulnerable(), "invincibility_applied");
            values.setBoolean("invincible", false);
            PersistenceLoader.load(trait, values);
            trait.onSpawn();
            check(!npc.isProtected() && !entity.isInvulnerable(), "invincibility_can_be_disabled");
            LoggerFactory.getLogger("interactions").info("[SENTINELAUDIT] COMPLETE 10/10");
        } catch (Throwable failure) {
            LoggerFactory.getLogger("interactions").error("[SENTINELAUDIT] FAILED", failure);
        } finally {
            npc.destroy();
        }
    }

    private static void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        LoggerFactory.getLogger("interactions").info("[SENTINELAUDIT] PASS {}", name);
    }
}
