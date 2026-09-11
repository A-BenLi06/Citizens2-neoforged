package net.yuuniverse.interactions;

import java.util.UUID;
import com.mojang.authlib.GameProfile;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "interactions")
public final class RoutingRuntimeAudit {
    private static boolean ran;

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (ran || event.getServer().getTickCount() < 65) return;
        ran = true;
        Session session = null;
        try {
            var player = new FakePlayer(event.getServer().overworld(), new GameProfile(UUID.randomUUID(), "RouteAudit"));
            var actions = new Actions(new ItemLibrary(), new Economy());
            var progress = new ProgressStore(new java.io.File("config/routing-audit-progress"));
            var engine = new Session.Engine() {
                public Actions actions() { return actions; }
                public ProgressStore progress() { return progress; }
            };
            var story = new Conversation();
            var source = new Conversation.Node("source");
            var target = new Conversation.Node("target");
            var donor = new Conversation.Node("donor");
            story.nodes.put(source.key, source);
            story.nodes.put(target.key, target);
            story.nodes.put(donor.key, donor);
            var first = line("paper");
            first.lastActions.add("player_command_as_op: give @s minecraft:diamond 1");
            first.startConversation = target.key;
            source.lines.add(first);
            source.options.add(new Conversation.Option());
            target.lines.add(line("emerald"));
            donor.lines.add(line("netherite_ingot"));
            var option = new Conversation.Option();
            option.actions.add("player_command_as_op: give @s minecraft:gold_ingot 1");
            donor.options.add(option);
            session = new Session(engine, story, source, player, null);
            session.tick();
            session.tick();
            check(player.getInventory().countItem(Items.PAPER) == 1
                    && player.getInventory().countItem(Items.DIAMOND) == 1, "line_actions_finish_before_jump");
            session.tick();
            check(!session.isAwaitingChoice() && !session.isFinished(), "automatic_jump_precedes_own_options");
            session.tick();
            check(player.getInventory().countItem(Items.EMERALD) == 1, "target_line_runs_automatically");
            for (int i = 0; i < 4; i++) session.tick();
            check(session.isFinished() && player.getInventory().countItem(Items.PAPER) == 1,
                    "target_end_does_not_reuse_source_route");

            player.getInventory().clearContent();
            first.startOptions = donor.key;
            session = new Session(engine, story, source, player, null);
            for (int i = 0; i < 3; i++) session.tick();
            check(session.isAwaitingChoice() && session.offeredCount() == 1, "borrowed_options_take_priority");
            check(player.getInventory().countItem(Items.EMERALD) == 0
                    && player.getInventory().countItem(Items.NETHERITE_INGOT) == 0, "borrowing_does_not_play_target_dialogues");
            check(session.choose(1), "borrowed_option_can_be_chosen");
            session.tick();
            check(session.isFinished() && player.getInventory().countItem(Items.GOLD_INGOT) == 1,
                    "borrowed_option_action_executes");

            player.getInventory().clearContent();
            first.startOptions = null;
            first.startConversation = "ignored_early_route";
            var last = line("iron_ingot");
            last.startConversation = target.key;
            source.lines.add(last);
            session = new Session(engine, story, source, player, null);
            for (int i = 0; i < 6; i++) session.tick();
            check(player.getInventory().countItem(Items.IRON_INGOT) == 1
                    && player.getInventory().countItem(Items.EMERALD) == 1, "only_terminal_line_routes");
            session.end(false);

            source.lines.clear();
            source.lines.add(first);
            player.getInventory().clearContent();
            player.getInventory().add(new ItemStack(Items.PAPER));
            first.actions.clear();
            first.actions.add("remove_item: %checkitem_remove_mat:minecraft:paper,amt:1%");
            session = new Session(engine, story, source, player, null);
            session.tick();
            check(session.isFinished() && player.getInventory().countItem(Items.PAPER) == 1,
                    "missing_route_rejected_before_payment");

            first.startConversation = target.key;
            first.actions.clear();
            first.lastActions.clear();
            first.lastActions.add("player_command_as_op: clear @s minecraft:stone 1");
            session = new Session(engine, story, source, player, null);
            for (int i = 0; i < 5; i++) session.tick();
            check(session.isFinished() && player.getInventory().countItem(Items.EMERALD) == 0,
                    "failed_last_action_stops_jump");

            player.getInventory().clearContent();
            first.lastActions.clear();
            first.actions.add("player_command_as_op: give @s minecraft:paper 1");
            target.lines.clear();
            var back = line("paper");
            back.startConversation = source.key;
            target.lines.add(back);
            session = new Session(engine, story, source, player, null);
            for (int i = 0; i < 20; i++) session.tick();
            check(!session.isFinished() && player.getInventory().countItem(Items.PAPER) == 7,
                    "cyclic_routes_advance_without_recursion");
            session.end(false);

            player.getInventory().clearContent();
            target.lines.clear();
            target.lines.add(line("emerald"));
            var redirect = new Conversation.Conditional();
            redirect.requires.add("yes == yes");
            redirect.startConversation = target.key;
            first.conditional.add(redirect);
            session = new Session(engine, story, source, player, null);
            session.tick();
            session.tick();
            check(player.getInventory().countItem(Items.PAPER) == 0
                    && player.getInventory().countItem(Items.EMERALD) == 1, "conditional_redirect_precedes_source_actions");
            session.end(false);

            player.getInventory().clearContent();
            redirect.requires.clear();
            redirect.requires.add("yes == no");
            session = new Session(engine, story, source, player, null);
            session.tick();
            check(player.getInventory().countItem(Items.PAPER) == 1
                    && player.getInventory().countItem(Items.EMERALD) == 0, "false_condition_plays_source_line");
            session.end(false);

            player.getInventory().clearContent();
            redirect.requires.clear();
            session = new Session(engine, story, source, player, null);
            session.tick();
            check(player.getInventory().countItem(Items.PAPER) == 1, "empty_conditional_requirements_are_ignored");
            session.end(false);

            player.getInventory().clearContent();
            redirect.requires.add("yes == yes");
            redirect.startConversation = "missing";
            session = new Session(engine, story, source, player, null);
            session.tick();
            check(session.isFinished() && player.getInventory().countItem(Items.PAPER) == 0,
                    "missing_conditional_target_stops_before_actions");

            redirect.startConversation = target.key;
            var later = new Conversation.Conditional();
            later.requires.add("yes == yes");
            later.startConversation = donor.key;
            first.conditional.add(later);
            session = new Session(engine, story, source, player, null);
            session.tick();
            session.tick();
            check(player.getInventory().countItem(Items.EMERALD) == 1
                    && player.getInventory().countItem(Items.NETHERITE_INGOT) == 0, "first_matching_conditional_wins");
            session.end(false);

            player.getInventory().clearContent();
            var reverse = new Conversation.Conditional();
            reverse.requires.add("yes == yes");
            reverse.startConversation = source.key;
            target.lines.get(0).conditional.add(reverse);
            session = new Session(engine, story, source, player, null);
            for (int i = 0; i < 20; i++) session.tick();
            check(!session.isFinished() && player.getInventory().isEmpty(), "conditional_cycles_do_not_recurse_or_run_actions");
            session.end(false);

            player.getInventory().clearContent();
            player.getInventory().add(new ItemStack(Items.PAPER));
            source.lines.clear();
            source.options.clear();
            var brokenOption = new Conversation.Option();
            brokenOption.startConversation = "missing_option_target";
            brokenOption.actions.add("remove_item: %checkitem_remove_mat:minecraft:paper,amt:1%");
            brokenOption.actions.add("player_command_as_op: give @s minecraft:diamond 1");
            source.options.add(brokenOption);
            story.source = "routing-audit.yml";
            story.saveProgress = true;
            session = new Session(engine, story, source, player, null);
            session.tick();
            session.choose(1);
            session.tick();
            check(session.isFinished() && player.getInventory().countItem(Items.PAPER) == 1
                    && player.getInventory().countItem(Items.DIAMOND) == 0, "missing_option_target_preserves_payment_and_reward");
            check(!progress.hasSeen(player.getUUID(), "routing-audit.source.completed"),
                    "missing_option_target_does_not_mark_completed");

            brokenOption.startConversation = target.key;
            target.lines.get(0).conditional.clear();
            session = new Session(engine, story, source, player, null);
            session.tick();
            session.choose(1);
            session.tick();
            check(!session.isFinished() && player.getInventory().countItem(Items.PAPER) == 0
                    && player.getInventory().countItem(Items.DIAMOND) == 1
                    && player.getInventory().countItem(Items.EMERALD) == 1, "valid_option_target_executes_actions_and_enters_node");
            LoggerFactory.getLogger("interactions").info("[ROUTINGAUDIT] COMPLETE 21/21");
        } catch (Throwable failure) {
            LoggerFactory.getLogger("interactions").error("[ROUTINGAUDIT] FAILED", failure);
        } finally {
            if (session != null) session.end(false);
        }
    }

    private static Conversation.Line line(String item) {
        var line = new Conversation.Line();
        line.time = 0;
        line.actions.add("player_command_as_op: give @s minecraft:" + item + " 1");
        return line;
    }

    private static void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        LoggerFactory.getLogger("interactions").info("[ROUTINGAUDIT] PASS {}", name);
    }
}
