package net.yuuniverse.interactions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;
import net.yuuniverse.interactions.DialogueDisplayRuntimeAudit.AuditPlayer;

/** Delays advance on actual server events, after the synchronous presentation checks have completed. */
final class ScheduledActionRuntimeAudit {
    private static final Component NPC = Component.literal("Scheduled speaker");
    private final MinecraftServer server;
    private final BiFunction<String, UUID, AuditPlayer> factory;
    private final List<InteractionsMod> controllers = new ArrayList<>();
    private final Map<Integer, AuditPlayer> bars = new LinkedHashMap<>();
    private InteractionsMod engine, reload, stopped;
    private Actions actions;
    private AuditPlayer sequencePlayer, dynamicPlayer, respawnPlayer, reconnectPlayer, costPlayer, zeroPlayer, snapshotPlayer;
    private AuditPlayer sessionPlayer, failedPlayer, logoutPlayer, lifecyclePlayer, overlayPlayer, witness;
    private AuditPlayer interruptedPlayer, optionPlayer, activeFailurePlayer, endedBarPlayer, longPlayer;
    private ActionExecution sequence, terminal, dynamic, respawn, reconnect, cost, zero, snapshot, reloaded, shutdown;
    private ActionExecution longWait;
    private ServerPlayer replacement;
    private Session session, failureSession;
    private Session optionSession, activeFailureSession;
    private DialogueActionBar status;
    private DialogueActionBar endedStatus;
    private int elapsed, passed, notifications;
    private final DialogueMessages messages = new DialogueMessages(null, null, null, null, null, null, null, null,
            null, null, "Talk %name%", "Choose %name%");

    ScheduledActionRuntimeAudit(MinecraftServer server, BiFunction<String, UUID, AuditPlayer> factory) {
        this.server = server; this.factory = factory;
    }

    void start() throws Exception {
        engine = controller(); actions = engine.actions();
        sequencePlayer = player("WaitSequence"); dynamicPlayer = player("WaitDynamic");
        respawnPlayer = player("WaitRespawn"); reconnectPlayer = player("WaitReconnect");
        costPlayer = player("WaitCost"); zeroPlayer = player("WaitZero"); snapshotPlayer = player("WaitSnapshot");
        sessionPlayer = player("WaitSession"); failedPlayer = player("WaitFailure"); logoutPlayer = player("WaitLogout");
        lifecyclePlayer = player("WaitLifecycle"); overlayPlayer = player("WaitOverlay"); witness = player("WaitWitness");
        interruptedPlayer = player("WaitInterrupted"); optionPlayer = player("WaitOption"); activeFailurePlayer = player("WaitActiveFail");
        endedBarPlayer = player("BarEnded"); longPlayer = player("WaitLong");

        sequence = actions.executeAll(List.of(give("paper", 1), "wait_ticks: 3", give("paper", 2), "wait: 1", give("paper", 4)), sequencePlayer, NPC);
        sequence.whenComplete(result -> notifications++);
        check(sequence.pending() && count(sequencePlayer, Items.PAPER) == 1 && notifications == 0, "wait_runs_prefix_and_keeps_outcome_pending");
        terminal = actions.executeAll(List.of("wait_ticks: 2"), sequencePlayer, NPC);
        zero = actions.executeAll(List.of("wait_ticks: 0", give("charcoal", 1)), zeroPlayer, NPC);
        check(terminal.pending() && zero.pending() && count(zeroPlayer, Items.CHARCOAL) == 0, "terminal_and_zero_waits_yield");
        dynamicPlayer.setExperienceLevels(1);
        dynamic = actions.executeAll(List.of("wait_ticks: 2", "player_command_as_op: give @s minecraft:paper %player_level%"), dynamicPlayer, NPC);
        respawn = actions.executeAll(List.of("wait_ticks: 4", give("emerald", 2)), respawnPlayer, NPC);
        actions.runAll(List.of("actionbar: Respawn action;6"), respawnPlayer, NPC);
        reconnect = actions.executeAll(List.of("wait_ticks: 4", give("diamond", 2)), reconnectPlayer, NPC);
        costPlayer.getInventory().add(new ItemStack(Items.PAPER));
        cost = actions.executeAll(List.of("wait_ticks: 2", payment(), give("diamond", 1)), costPlayer, NPC);
        var source = new ArrayList<>(List.of("wait_ticks: 3", give("copper_ingot", 1)));
        snapshot = actions.executeAll(source, snapshotPlayer, NPC); source.set(1, give("dirt", 1));
        invalidPreflight();

        Conversation story = story("timed-success", List.of("wait_ticks: 5", give("gold_ingot", 1)));
        story.first().lines.getFirst().lastActions.addAll(List.of("wait_ticks: 8", give("iron_ingot", 1)));
        session = session(engine, story, sessionPlayer); session.tick();
        check(!engine.progress().hasSeen(sessionPlayer.getUUID(), "timed-success.conversation1.dialogue1"), "saved_line_waits_for_initial_actions");
        Conversation failing = story("timed-failure", List.of("wait_ticks: 4", "player_command_as_op: clear @s minecraft:stone 1", give("diamond", 1)));
        failureSession = session(engine, failing, failedPlayer); failureSession.tick();

        Conversation interrupted = story("timed-interrupt", List.of("wait_ticks: 3", give("copper_ingot", 1)));
        interrupted.first().interruptActions.addAll(List.of("wait_ticks: 2", give("iron_ingot", 1)));
        Session interruptedSession = session(engine, interrupted, interruptedPlayer); interruptedSession.tick(); interruptedSession.end(false);
        Conversation choice = story("timed-choice", List.of()); choice.first().lines.clear();
        var option = new Conversation.Option(); option.text = "Continue"; option.startConversation = "conversation2";
        option.actions.addAll(List.of("wait_ticks: 4", give("gold_ingot", 1))); choice.first().options.add(option);
        var next = new Conversation.Node("conversation2"); choice.nodes.put(next.key, next);
        optionSession = session(engine, choice, optionPlayer); optionSession.tick();
        check(optionSession.choose(1), "waiting_option_can_be_selected");
        Conversation activeFailure = story("timed-active-failure", List.of("wait_ticks: 2", "player_command_as_op: clear @s minecraft:stone 1"));
        activeFailure.first().lines.getFirst().time = -1;
        activeFailure.first().interruptActions.add(give("coal", 1));
        activeFailureSession = session(engine, activeFailure, activeFailurePlayer); activeFailureSession.tick();

        Conversation logoutStory = story("timed-logout", List.of("wait_ticks: 3", give("diamond", 1)));
        logoutStory.first().interruptActions.addAll(List.of(give("coal", 1), "wait_ticks: 1", give("coal", 1)));
        Session loggingOut = session(engine, logoutStory, logoutPlayer); loggingOut.tick();
        engine.onLogout(new PlayerEvent.PlayerLoggedOutEvent(logoutPlayer));
        check(loggingOut.isFinished() && count(logoutPlayer, Items.COAL) == 1, "logout_runs_immediate_interrupt_then_cancels_delays");

        reload = controller();
        reload.onRegisterCommands(new RegisterCommandsEvent(server.getCommands().getDispatcher(), Commands.CommandSelection.DEDICATED,
                CommandBuildContext.simple(server.registryAccess(), FeatureFlags.REGISTRY.allFlags())));
        var old = reload.actions().executeAll(List.of("wait_ticks: 2", give("diamond", 1)), lifecyclePlayer, NPC);
        reload.actions().runAll(List.of("actionbar: Reload overlay;40"), lifecyclePlayer, NPC); lifecyclePlayer.clear();
        var reentrant = reload.actions().executeAll(List.of("console_command: interactions reload", give("diamond", 1)), lifecyclePlayer, NPC);
        check(old.result() == ActionExecution.Result.CANCELLED && reentrant.result() == ActionExecution.Result.CANCELLED
                && count(lifecyclePlayer, Items.DIAMOND) == 0, "reload_cancels_queued_and_current_command_batch");
        check(lifecyclePlayer.actionBars().equals(List.of("")), "reload_clears_owned_timed_actionbar");
        reloaded = reload.actions().executeAll(List.of("wait_ticks: 2", give("lapis_lazuli", 1)), lifecyclePlayer, NPC);
        check(reloaded.pending(), "reload_accepts_new_configuration_batches");

        stopped = controller();
        Conversation stopStory = story("timed-stop", List.of("wait_ticks: 2", give("diamond", 1)));
        stopStory.first().interruptActions.addAll(List.of(give("coal", 1), "wait_ticks: 1", give("coal", 1)));
        Session stopping = session(stopped, stopStory, lifecyclePlayer); stopping.tick();
        shutdown = stopped.actions().executeAll(List.of("wait_ticks: 2", give("diamond", 1)), lifecyclePlayer, NPC);
        stopped.actions().runAll(List.of("actionbar: Shutdown overlay;40"), lifecyclePlayer, NPC); lifecyclePlayer.clear();
        stopped.onServerStopping(new ServerStoppingEvent(server));
        check(stopping.isFinished() && shutdown.result() == ActionExecution.Result.CANCELLED && count(lifecyclePlayer, Items.COAL) == 1
                && stopped.actions().executeAll(List.of(give("diamond", 1)), lifecyclePlayer, NPC).result() == ActionExecution.Result.CANCELLED,
                "shutdown_cancels_delays_and_rejects_later_batches");
        check(lifecyclePlayer.actionBars().equals(List.of("")), "shutdown_clears_owned_timed_actionbar");

        for (int duration : List.of(-1, 0, 40, 41, 80, 90)) {
            AuditPlayer player = player("Bar" + duration); bars.put(duration, player);
            player.clear();
            check(actions.runAll(List.of("actionbar: &a%player_name%;" + duration), player, NPC)
                    && player.actionBars().equals(List.of(player.getGameProfile().getName())), "actionbar_immediate_payload_" + duration);
            player.clear();
        }
        status = new DialogueActionBar(overlayPlayer, actions.actionBars());
        status.tick(true, messages, "Guide", false);
        actions.runAll(List.of("actionbar: Short;2", "actionbar: Overlay;90"), overlayPlayer, NPC);
        overlayPlayer.clear(); respawnPlayer.clear(); witness.clear();
        endedStatus = new DialogueActionBar(endedBarPlayer, actions.actionBars());
        endedStatus.tick(true, messages, "Guide", false);
        actions.runAll(List.of("actionbar: Survives status;6"), endedBarPlayer, NPC); endedBarPlayer.clear();
        longWait = actions.executeAll(List.of("wait: 2147483647", give("diamond", 1)), longPlayer, NPC);
        check(longWait.pending() && actions.runAll(List.of("actionbar: Long;2147483647"), longPlayer, NPC),
                "maximum_durations_schedule_without_allocating_per_refresh_tasks");
        check(actions.validateAll(List.of("wait: 2147483647", "actionbar: Long;2147483647"), sequencePlayer, NPC),
                "maximum_wait_and_actionbar_durations_do_not_overflow_preflight");
    }

    boolean tick(ServerTickEvent.Post event) throws Exception {
        elapsed++;
        if (elapsed == 1) {
            dynamicPlayer.setExperienceLevels(3); costPlayer.getInventory().clearContent();
            endedStatus.close();
        }
        if (elapsed == 2) {
            replacement = server.getPlayerList().respawn(respawnPlayer, false, Entity.RemovalReason.KILLED);
            replacement.setPos(1, -60, 1);
            UUID id = reconnectPlayer.getUUID(); String name = reconnectPlayer.getGameProfile().getName();
            server.getPlayerList().remove(reconnectPlayer);
            reconnectPlayer = factory.apply(name, id);
        }
        for (InteractionsMod controller : controllers) controller.onServerTick(event);
        status.tick(true, messages, "Guide", elapsed >= 20);

        if (elapsed < 3) check(sequence.pending() && count(sequencePlayer, Items.PAPER) == 1, "wait_ticks_not_early_" + elapsed);
        if (elapsed == 1) {
            check(zero.result() == ActionExecution.Result.SUCCEEDED && count(zeroPlayer, Items.CHARCOAL) == 1, "zero_wait_resumes_on_next_server_tick");
            check(count(logoutPlayer, Items.COAL) == 1 && count(logoutPlayer, Items.DIAMOND) == 0, "logout_interrupt_wait_is_cancelled");
            check(endedBarPlayer.actionBars().isEmpty(), "closing_status_does_not_clear_timed_action");
            check(optionSession.isFinished() && count(optionPlayer, Items.GOLD_INGOT) == 0
                    && !engine.progress().hasSeen(optionPlayer.getUUID(), "timed-choice.conversation2.completed"),
                    "option_wait_does_not_delay_route_but_defers_completion");
        }
        if (elapsed == 2) {
            check(terminal.result() == ActionExecution.Result.SUCCEEDED, "terminal_wait_completes_at_deadline");
            check(dynamic.result() == ActionExecution.Result.SUCCEEDED && count(dynamicPlayer, Items.PAPER) == 3, "delayed_placeholders_use_current_player_state");
            check(cost.result() == ActionExecution.Result.FAILED && count(costPlayer, Items.DIAMOND) == 0, "resume_rechecks_payment_before_reward");
            check(reconnect.result() == ActionExecution.Result.CANCELLED && count(reconnectPlayer, Items.DIAMOND) == 0, "old_batch_does_not_follow_relogin_with_same_uuid");
            check(respawn.pending() && respawnPlayer.actionBars().equals(List.of("Respawn action")), "actionbar_rebinds_actual_respawn_without_resetting_expiry");
            check(reloaded.result() == ActionExecution.Result.SUCCEEDED && count(lifecyclePlayer, Items.LAPIS_LAZULI) == 1
                    && count(lifecyclePlayer, Items.DIAMOND) == 0 && count(lifecyclePlayer, Items.COAL) == 1, "reload_new_batch_runs_and_shutdown_batch_stays_cancelled");
            check(session.isFinished() && failureSession.isFinished()
                    && !engine.progress().hasSeen(sessionPlayer.getUUID(), "timed-success.conversation1.completed"),
                    "dialogue_timer_finishes_independently_without_premature_completion");
            check(count(interruptedPlayer, Items.IRON_INGOT) == 1 && count(interruptedPlayer, Items.COPPER_INGOT) == 0,
                    "interrupt_actions_keep_their_independent_waits");
            check(activeFailureSession.isFinished() && count(activeFailurePlayer, Items.COAL) == 1
                    && !engine.progress().hasSeen(activeFailurePlayer.getUUID(), "timed-active-failure.conversation1.completed"),
                    "delayed_failure_interrupts_still_active_session_once");
        }
        if (elapsed == 3) {
            check(sequence.pending() && count(sequencePlayer, Items.PAPER) == 3 && notifications == 0, "wait_ticks_resumes_exactly_then_seconds_wait_begins");
            check(snapshot.result() == ActionExecution.Result.SUCCEEDED && count(snapshotPlayer, Items.COPPER_INGOT) == 1
                    && count(snapshotPlayer, Items.DIRT) == 0, "queued_actions_are_an_immutable_snapshot");
            check(count(interruptedPlayer, Items.COPPER_INGOT) == 1
                    && !engine.progress().hasSeen(interruptedPlayer.getUUID(), "timed-interrupt.conversation1.completed"),
                    "ordinary_interruption_preserves_accepted_batches_without_completion");
        }
        if (elapsed == 4) {
            check(respawn.result() == ActionExecution.Result.SUCCEEDED && count(replacement, Items.EMERALD) == 2
                    && count(respawnPlayer, Items.EMERALD) == 0, "delayed_actions_follow_actual_respawn_player");
            check(!engine.progress().hasSeen(failedPlayer.getUUID(), "timed-failure.conversation1.completed")
                    && !engine.progress().hasSeen(failedPlayer.getUUID(), "timed-failure.conversation1.dialogue1")
                    && count(failedPlayer, Items.DIAMOND) == 0, "late_command_failure_stops_rewards_and_saved_progress");
        }
        if (elapsed == 5) check(count(sessionPlayer, Items.GOLD_INGOT) == 1
                && engine.progress().hasSeen(sessionPlayer.getUUID(), "timed-success.conversation1.dialogue1")
                && !engine.progress().hasSeen(sessionPlayer.getUUID(), "timed-success.conversation1.completed"),
                "initial_batch_saves_line_but_waits_for_last_actions_before_completion");
        if (elapsed == 5) check(count(optionPlayer, Items.GOLD_INGOT) == 1
                && engine.progress().hasSeen(optionPlayer.getUUID(), "timed-choice.conversation2.completed"), "option_progress_waits_for_action_success");
        if (elapsed == 7) {
            check(respawnPlayer.actionBars().equals(List.of("")), "respawn_actionbar_keeps_original_clear_deadline");
            check(endedBarPlayer.actionBars().equals(List.of("")), "timed_action_clears_after_status_has_ended");
        }
        if (elapsed == 9) check(count(sessionPlayer, Items.IRON_INGOT) == 1
                && engine.progress().hasSeen(sessionPlayer.getUUID(), "timed-success.conversation1.completed"), "normal_end_delayed_actions_complete_and_record_progress");
        if (elapsed == 22) check(sequence.pending() && count(sequencePlayer, Items.PAPER) == 3, "seconds_wait_is_twenty_ticks_not_early");
        if (elapsed == 23) check(sequence.result() == ActionExecution.Result.SUCCEEDED && count(sequencePlayer, Items.PAPER) == 7
                && notifications == 1, "seconds_wait_finishes_once_at_twenty_ticks");
        if (elapsed == 91) check(overlayPlayer.actionBars().equals(List.of("Choose Guide")), "actionbar_expiry_restores_latest_status_phase");
        else if (elapsed == 10 || elapsed == 50) check(overlayPlayer.actionBars().equals(List.of("Overlay")), "actionbar_owned_refresh_" + elapsed);
        else if (elapsed < 91 && !overlayPlayer.actionBars().isEmpty())
            throw new AssertionError("Status/older clear overwrote timed action at " + elapsed);

        for (var entry : bars.entrySet()) {
            int duration = entry.getKey(); var player = entry.getValue();
            List<String> expected = duration >= 0 && elapsed == duration + 1 ? List.of("")
                    : duration > 40 && elapsed < duration && elapsed > 0 && (duration - elapsed) % 40 == 0
                    ? List.of(player.getGameProfile().getName()) : List.of();
            if (!expected.isEmpty() || elapsed == 1 || elapsed == 40 || elapsed == 91)
                check(player.actionBars().equals(expected), "actionbar_exact_schedule_" + duration + "_tick_" + elapsed);
            else if (!player.actionBars().isEmpty()) throw new AssertionError("Unexpected ActionBar refresh " + duration + " at " + elapsed);
            player.clear();
        }
        if (!witness.actionBars().isEmpty()) throw new AssertionError("Timed packets leaked at " + elapsed);
        overlayPlayer.clear(); respawnPlayer.clear();
        if (elapsed < 92) return false;
        check(witness.actionBars().isEmpty(), "timed_packets_remain_private_for_full_lifetime");
        check(longWait.pending() && count(longPlayer, Items.DIAMOND) == 0, "long_wait_does_not_overflow_into_immediate_execution");
        check(count(activeFailurePlayer, Items.COAL) == 1, "delayed_failure_does_not_repeat_interrupt_actions");
        status.close();
        for (InteractionsMod controller : controllers) controller.onServerStopping(new ServerStoppingEvent(server));
        check(notifications == 1, "completion_listener_is_not_repeated_on_shutdown");
        LoggerFactory.getLogger("interactions").info("[SCHEDULEDACTIONAUDIT] COMPLETE {} checks", passed);
        return true;
    }

    private void invalidPreflight() {
        for (String action : List.of("wait: -1", "wait: 1.5", "wait_ticks: 2147483648", "wait_ticks: NaN",
                "actionbar: text;1.5", "actionbar: text", "actionbar: text;-2", "actionbar: text;2147483648")) {
            zeroPlayer.getInventory().clearContent(); zeroPlayer.getInventory().add(new ItemStack(Items.PAPER));
            var failed = actions.executeAll(List.of(payment(), action, give("diamond", 1)), zeroPlayer, NPC);
            check(failed.result() == ActionExecution.Result.FAILED && count(zeroPlayer, Items.PAPER) == 1
                    && count(zeroPlayer, Items.DIAMOND) == 0, "invalid_timing_preserves_payment_" + action);
        }
        var failed = actions.executeAll(List.of(payment(), "wait_ticks: 2", "console_command: si give missing 1"), zeroPlayer, NPC);
        check(failed.result() == ActionExecution.Result.FAILED && count(zeroPlayer, Items.PAPER) == 1,
                "full_batch_preflight_checks_after_wait_before_payment");
    }

    private InteractionsMod controller() throws Exception {
        var controller = new InteractionsMod(); NeoForge.EVENT_BUS.unregister(controller);
        var field = InteractionsMod.class.getDeclaredField("actions"); field.setAccessible(true);
        field.set(controller, new Actions(new ItemLibrary(), new Economy())); controllers.add(controller); return controller;
    }

    @SuppressWarnings("unchecked")
    private Session session(InteractionsMod controller, Conversation story, AuditPlayer player) throws Exception {
        var session = new Session(controller, story, story.first(), player, null);
        var field = InteractionsMod.class.getDeclaredField("sessions"); field.setAccessible(true);
        ((Map<UUID, Session>) field.get(controller)).put(player.getUUID(), session); return session;
    }

    private static Conversation story(String id, List<String> actions) {
        var story = new Conversation(); story.source = id + ".yml"; story.name = id; story.saveProgress = true;
        var node = new Conversation.Node("conversation1"); var line = new Conversation.Line();
        line.key = "dialogue1"; line.time = 0.05; line.saveToPlayer = true; line.text.add(id); line.actions.addAll(actions);
        node.lines.add(line); story.nodes.put(node.key, node); return story;
    }

    private AuditPlayer player(String name) { return factory.apply(name, UUID.randomUUID()); }
    private static String give(String item, int amount) { return "player_command_as_op: give @s minecraft:" + item + " " + amount; }
    private static String payment() { return "remove_item: %checkitem_remove_mat:minecraft:paper,amt:1%"; }
    private static int count(ServerPlayer player, net.minecraft.world.item.Item item) { return player.getInventory().countItem(item); }
    private void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        passed++; LoggerFactory.getLogger("interactions").info("[SCHEDULEDACTIONAUDIT] PASS {}", name);
    }
}
