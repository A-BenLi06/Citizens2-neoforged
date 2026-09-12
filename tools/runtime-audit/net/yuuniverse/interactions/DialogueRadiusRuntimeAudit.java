package net.yuuniverse.interactions;

import java.io.File;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "interactions")
public final class DialogueRadiusRuntimeAudit {
    private static boolean ran;
    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (ran || net.citizensnpcs.audit.FixtureRuntimeAudit.elapsedTicks(event.getServer()) < 40) return;
        ran = true;
        try {
            var level = event.getServer().overworld();
            var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "RadiusAudit"));
            player.setPos(0, -60, 0);
            var npc = EntityType.PIG.create(level);
            npc.setPos(3, -60, 0);
            var progress = new ProgressStore(new File("config/radius-audit-progress"));
            var actions = new Actions(new ItemLibrary(), new Economy());
            Session.Engine engine = new Session.Engine() {
                public Actions actions() { return actions; }
                public ProgressStore progress() { return progress; }
            };
            var story = new Conversation();
            story.endRadius = 3;
            var node = new Conversation.Node("conversation1");
            var line = new Conversation.Line();
            line.time = 100;
            node.lines.add(line);
            Session boundary = new Session(engine, story, node, player, npc);
            boundary.tick();
            check(!boundary.isFinished(), "exact_boundary_keeps_session");
            npc.setPos(3.01, -60, 0);
            boundary.tick();
            check(boundary.isFinished(), "past_boundary_ends_session_without_extra_half_block");
            story.endRadius = 0;
            npc.setPos(100, -60, 0);
            Session unlimited = new Session(engine, story, node, player, npc);
            unlimited.tick();
            check(!unlimited.isFinished(), "zero_radius_disables_distance_exit");
            npc.discard();
            unlimited.tick();
            check(unlimited.isFinished(), "removed_npc_still_ends_session");
            LoggerFactory.getLogger("interactions").info("[RADIUSAUDIT] COMPLETE 4/4");
        } catch (Throwable failure) {
            LoggerFactory.getLogger("interactions").error("[RADIUSAUDIT] FAILED", failure);
        }
    }
    private static void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        LoggerFactory.getLogger("interactions").info("[RADIUSAUDIT] PASS {}", name);
    }
}
