package net.yuuniverse.interactions;

import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

/** Opt-in dedicated-server probes using real inventories and the Minecraft command dispatcher. */
@EventBusSubscriber(modid = "interactions")
public final class ActionRuntimeAudit {
    private static boolean ran;

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (ran || event.getServer().getTickCount() < 20) return;
        ran = true;
        try {
            var player = new FakePlayer(event.getServer().overworld(),
                    new GameProfile(UUID.randomUUID(), "ActionAudit"));
            var actions = new Actions(new ItemLibrary(), new Economy());
            var name = Component.literal("Audit");
            check(actions.runAll(List.of("player_command_as_op: minecraft:give @s minecraft:paper 3"), player, name)
                    && player.getInventory().countItem(Items.PAPER) == 3, "namespaced_give");
            check(actions.runAll(List.of("remove_item: %checkitem_remove_mat:minecraft:paper,amt:2%",
                    "player_command_as_op: minecraft:give @s minecraft:diamond 1"), player, name)
                    && player.getInventory().countItem(Items.PAPER) == 1
                    && player.getInventory().countItem(Items.DIAMOND) == 1, "purchase_success");
            check(!actions.runAll(List.of("remove_item: %checkitem_remove_mat:minecraft:paper,amt:1%",
                    "console_command: si give missing 1"), player, name)
                    && player.getInventory().countItem(Items.PAPER) == 1, "missing_reward_preserves_payment");
            check(!actions.runAll(List.of("remove_item: %checkitem_remove_mat:minecraft:paper,amt:2%",
                    "player_command_as_op: give @s minecraft:diamond 1"), player, name)
                    && player.getInventory().countItem(Items.DIAMOND) == 1, "insufficient_items_no_reward");
            check(!actions.runAll(List.of("player_command_as_op: clear @s minecraft:stone 1",
                    "player_command_as_op: give @s minecraft:diamond 1"), player, name)
                    && player.getInventory().countItem(Items.DIAMOND) == 1, "command_failure_stops_reward");
            check(!actions.runAll(List.of("remove_item: %checkitem_remove_mat:minecraft:paper,amt:1%",
                    "player_command_as_op: give @s missing:item 1"), player, name)
                    && player.getInventory().countItem(Items.PAPER) == 1, "parse_failure_preserves_payment");
            check(!actions.runAll(List.of("remove_item: %checkitem_remove_mat:minecraft:paper,amt:1%",
                    "console_command: eco take ActionAudit 5"), player, name)
                    && player.getInventory().countItem(Items.PAPER) == 1, "missing_economy_preserves_payment");
            var progress = new ProgressStore(new java.io.File("config/action-audit-progress"));
            Session.Engine engine = new Session.Engine() {
                public Actions actions() { return actions; }
                public ProgressStore progress() { return progress; }
            };
            var story = new Conversation();
            var broken = new Conversation.Node("broken");
            var line = new Conversation.Line();
            line.actions.add("remove_item: %checkitem_remove_mat:minecraft:paper,amt:1%");
            line.lastActions.add("console_command: si give missing 1");
            broken.lines.add(line);
            var session = new Session(engine, story, broken, player, null);
            session.tick();
            check(session.isFinished() && player.getInventory().countItem(Items.PAPER) == 1,
                    "last_action_preflight_preserves_payment");

            var choice = new Conversation.Node("choice");
            var option = new Conversation.Option();
            option.actions.add("player_command_as_op: give @s minecraft:diamond 1");
            choice.options.add(option);
            session = new Session(engine, story, choice, player, null);
            session.tick();
            check(session.choose(1) && player.getInventory().countItem(Items.DIAMOND) == 1,
                    "selection_defers_command_execution");
            session.tick();
            check(session.isFinished() && player.getInventory().countItem(Items.DIAMOND) == 2,
                    "selected_command_executes_on_tick");
            LoggerFactory.getLogger("interactions").info("[ACTIONAUDIT] COMPLETE 10/10");
        } catch (Throwable failure) {
            LoggerFactory.getLogger("interactions").error("[ACTIONAUDIT] FAILED", failure);
        }
    }

    private static void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        LoggerFactory.getLogger("interactions").info("[ACTIONAUDIT] PASS {}", name);
    }
}
