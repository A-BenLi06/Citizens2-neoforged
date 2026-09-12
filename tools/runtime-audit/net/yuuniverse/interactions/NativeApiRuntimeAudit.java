package net.yuuniverse.interactions;

import java.util.ArrayList;
import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.trait.ArmorStandTrait;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.SneakTrait;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

/** Actual registry/API persistence checks; all objects live in an in-memory registry and are removed afterward. */
@EventBusSubscriber(modid = "interactions")
public final class NativeApiRuntimeAudit {
    private static boolean ran;

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (ran || net.citizensnpcs.audit.FixtureRuntimeAudit.elapsedTicks(event.getServer()) < 30) return;
        ran = true;
        boolean lookDefault = Setting.DEFAULT_LOOK_CLOSE.asBoolean();
        var registry = CitizensAPI.createAnonymousNPCRegistry(new MemoryNPCDataStore());
        var created = new ArrayList<NPC>();
        try {
            Setting.DEFAULT_LOOK_CLOSE.set(false);
            NPC actor = registry.createNPC(EntityType.PIG, "NativeApiAudit");
            created.add(actor);
            check(!actor.hasTrait(LookClose.class), "lookclose_disabled_default");
            actor.setSneaking(true);
            check(actor.hasTrait(SneakTrait.class) && actor.getTraitNullable(SneakTrait.class).isSneaking(),
                    "sneak_api_remembers_unspawned_state");
            var location = new Location(event.getServer().overworld(), 1, -60, 1);
            check(actor.spawn(location) && actor.getEntity().isShiftKeyDown(), "sneak_applies_on_spawn");
            var saved = new MemoryDataKey();
            actor.save(saved);
            check(saved.getBoolean("traits.sneak.sneaking"), "sneak_saved_with_npc");
            actor.despawn();
            check(actor.spawn(location) && actor.getEntity().isShiftKeyDown(), "sneak_survives_respawn");
            actor.setSneaking(false);
            check(!actor.getEntity().isShiftKeyDown() && !actor.getTraitNullable(SneakTrait.class).isSneaking(),
                    "sneak_api_can_disable");
            Setting.DEFAULT_LOOK_CLOSE.set(true);
            NPC looking = registry.createNPC(EntityType.PIG, "LookDefaultAudit");
            created.add(looking);
            check(looking.hasTrait(LookClose.class), "lookclose_enabled_default");
            NPC stand = registry.createNPC(EntityType.ARMOR_STAND, "StandDefaultAudit");
            created.add(stand);
            check(stand.hasTrait(ArmorStandTrait.class), "armorstand_default_trait");
            LoggerFactory.getLogger("interactions").info("[NATIVEAPIAUDIT] COMPLETE 8/8");
        } catch (Throwable failure) {
            LoggerFactory.getLogger("interactions").error("[NATIVEAPIAUDIT] FAILED", failure);
        } finally {
            Setting.DEFAULT_LOOK_CLOSE.set(lookDefault);
            for (NPC npc : created) npc.destroy();
        }
    }

    private static void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        LoggerFactory.getLogger("interactions").info("[NATIVEAPIAUDIT] PASS {}", name);
    }
}
