package net.yuuniverse.interactions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

import net.citizensnpcs.api.util.PermissionUtil;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.handler.IPermissionHandler;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionDynamicContext;
import net.yuuniverse.interactions.DialogueDisplayRuntimeAudit.AuditPlayer;
import org.slf4j.LoggerFactory;

/** Real dispatcher, player data, session and delayed influence checks in the isolated display server. */
final class InfluenceRuntimeAudit {
    private static final String KEY = "faction.v1_伍德", OTHER = "other", SPACED = "faction with spaces";
    private static final String MARKER = "%interactions_influence_" + KEY + "%";
    private static final Component NPC = Component.literal("Shared display name");
    private static final String PAYMENT = "remove_item: %checkitem_remove_mat:minecraft:paper,amt:1%";
    private final MinecraftServer server;
    private final BiFunction<String, UUID, AuditPlayer> factory;
    private InteractionsMod controller;
    private ConversationLibrary library;
    private Influence influence;
    private Actions actions;
    private AuditPlayer alice, bob;
    private ActionExecution delayed, overflow;
    private int elapsed, passed;
    private boolean done;

    InfluenceRuntimeAudit(MinecraftServer server, BiFunction<String, UUID, AuditPlayer> factory) {
        this.server = server; this.factory = factory;
    }

    void start() throws Exception {
        alice = factory.apply("InfluenceAlice", UUID.randomUUID());
        bob = factory.apply("InfluenceBob", UUID.randomUUID());
        controller = new InteractionsMod(); NeoForge.EVENT_BUS.unregister(controller);
        var libraryField = InteractionsMod.class.getDeclaredField("library"); libraryField.setAccessible(true);
        library = (ConversationLibrary) libraryField.get(controller);
        Path folder = Path.of("config/influence-audit-conversations"); Files.createDirectories(folder);
        for (String key : List.of(KEY, OTHER, SPACED)) Files.writeString(folder.resolve(key + ".yml"),
                "name: Shared display name\nstarts_with: ['NPC named influence-audit']\nconversation:\n  conversation1:\n    dialogue:\n      dialogue1:\n        text: ['Influence']\n        time: -1\n");
        library.load(folder.toFile()); influence = controller.influence();
        actions = new Actions(new ItemLibrary(), new Economy(), influence);
        var actionsField = InteractionsMod.class.getDeclaredField("actions"); actionsField.setAccessible(true); actionsField.set(controller, actions);
        controller.onRegisterCommands(new RegisterCommandsEvent(server.getCommands().getDispatcher(), Commands.CommandSelection.DEDICATED,
                CommandBuildContext.simple(server.registryAccess(), FeatureFlags.DEFAULT_FLAGS)));
        check(library.size() == 3 && influence.get(alice, KEY) == 0, "loaded_filename_keys_default_to_zero");
        check(influence.remove(alice, KEY, 7) == -7 && influence.add(alice, KEY, 2) == -5,
                "native_api_allows_negative_influence");
        check(influence.get(bob, KEY) == 0 && influence.get(alice, OTHER) == 0 && influence.get(alice, KEY.toUpperCase()) == 0,
                "player_filename_and_case_are_independent");
        check(influence.set(alice, KEY, Integer.MIN_VALUE) == Integer.MIN_VALUE
                && influence.set(alice, KEY, Integer.MAX_VALUE) == Integer.MAX_VALUE, "set_supports_full_signed_range");
        invalid();
        check(actions.runAll(List.of("influence: set;2", "influence: add;" + MARKER), alice, NPC, KEY)
                && influence.get(alice, KEY) == 4, "sequential_influence_placeholders_see_prior_native_changes");
        check(Text.placeholders(MARKER + "/%interactions_influence_other%/" + MARKER + "/%unknown%", alice, controller.progress())
                .equals("4/0/4/%unknown%"), "multiple_placeholders_preserve_unknown_tokens");
        var json = Text.json("{\"text\":\"" + MARKER + "\",\"bold\":true,\"hoverEvent\":{\"action\":\"show_text\",\"contents\":\"" + MARKER + "\"}}",
                alice, controller.progress());
        check(json.getString().equals("4") && json.getStyle().isBold()
                && json.getStyle().getHoverEvent().getValue(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT).getString().equals("4"),
                "json_text_and_hover_share_progress_resolution");
        for (String condition : List.of(MARKER + " == 4", MARKER + " > 3", MARKER + " >= 4", MARKER + " < 5",
                MARKER + " <= 4", "%interactions_influence_other% < " + MARKER))
            check(Conditions.all(List.of(condition), alice, controller.progress()), "influence_requirement_" + condition);
        check(!Conditions.all(List.of("%unknown% != 0"), alice, controller.progress()), "unknown_requirement_cannot_grant_option");
        check(!Conditions.all(List.of("%unknown_" + MARKER + "% == 4"), alice, controller.progress()), "partial_unknown_expansion_stays_unresolved");
        commands();
        session();
        controller.progress().saveDirty();
        var restart = new ProgressStore(Path.of("config/interactions/players").toFile()); restart.loadAll();
        check(restart.getInfluence(alice.getUUID(), KEY) == influence.get(alice, KEY), "fresh_store_reads_saved_influence");

        influence.set(alice, KEY, 1); influence.set(bob, OTHER, 0);
        alice.getInventory().clearContent(); bob.getInventory().clearContent(); bob.getInventory().add(new ItemStack(Items.PAPER));
        delayed = actions.executeAll(List.of("wait_ticks: 2", "influence: add;" + MARKER,
                "player_command_as_op: give @s minecraft:diamond " + MARKER), alice, NPC, KEY);
        overflow = actions.executeAll(List.of("wait_ticks: 2", PAYMENT, "influence: add;1"), bob, NPC, OTHER);
        influence.set(alice, KEY, 3); influence.set(bob, OTHER, Integer.MAX_VALUE);
        check(delayed.pending() && overflow.pending(), "influence_batches_wait_without_recording_success");
    }

    private void invalid() {
        for (String action : List.of("influence: add;0", "influence: remove;-1", "influence: set;2147483648",
                "influence: add;1.5", "influence: reset;1", "influence: set;1;other", "influence: set;NaN", "influence: set;")) {
            alice.getInventory().clearContent(); alice.getInventory().add(new ItemStack(Items.PAPER));
            check(!actions.runAll(List.of(PAYMENT, action), alice, NPC, KEY)
                    && alice.getInventory().countItem(Items.PAPER) == 1 && influence.get(alice, KEY) == Integer.MAX_VALUE,
                    "invalid_influence_preserves_payment_" + action);
        }
        check(!actions.runAll(List.of(PAYMENT, "influence: add;1"), alice, NPC, KEY)
                && alice.getInventory().countItem(Items.PAPER) == 1, "overflow_rejects_before_payment");
        check(!actions.runAll(List.of(PAYMENT, "influence: set;2147483646", "influence: add;1", "influence: add;1"), alice, NPC, KEY)
                && influence.get(alice, KEY) == Integer.MAX_VALUE && alice.getInventory().countItem(Items.PAPER) == 1,
                "cumulative_preflight_detects_overflow_without_mutation");
        check(!actions.runAll(List.of(PAYMENT, "influence: set;2147483647", "influence: add;" + MARKER), alice, NPC, KEY)
                && alice.getInventory().countItem(Items.PAPER) == 1, "projected_placeholder_overflow_rejects_before_payment");
        check(!actions.runAll(List.of("influence: set;1"), alice, NPC), "missing_context_does_not_use_display_name");
        check(!actions.runAll(List.of(PAYMENT, "influence: set;1"), alice, NPC, "missing")
                && alice.getInventory().countItem(Items.PAPER) == 1, "unknown_conversation_rejects_before_payment");
        influence.set(alice, KEY, Integer.MIN_VALUE);
        check(!actions.runAll(List.of(PAYMENT, "influence: remove;1"), alice, NPC, KEY)
                && influence.get(alice, KEY) == Integer.MIN_VALUE && alice.getInventory().countItem(Items.PAPER) == 1,
                "underflow_rejects_before_payment");
    }

    private void commands() throws Exception {
        String target = alice.getGameProfile().getName() + " " + KEY;
        var console = server.createCommandSourceStack();
        check(command("set " + target + " -2", console) == 1 && influence.get(alice, KEY) == -2, "original_admin_set_syntax");
        check(command("add " + target + " 4", console) == 1 && influence.get(alice, KEY) == 2, "original_admin_add_syntax");
        check(command("remove " + target + " 3", console) == 1 && influence.get(alice, KEY) == -1, "original_admin_remove_syntax");
        check(command("get " + target, console) == 1 && influence.get(alice, KEY) == -1, "read_only_get_extension");
        check(command("set " + alice.getGameProfile().getName() + " \"" + SPACED + "\" 8", console) == 1
                && influence.get(alice, SPACED) == 8, "quoted_conversation_filename");
        for (String suffix : List.of("add " + target + " 0", "remove " + target + " -1", "set " + target + " 2147483648",
                "set NobodyOnline " + KEY + " 1", "set " + alice.getGameProfile().getName() + " missing 1"))
            check(command(suffix, console) == 0 && influence.get(alice, KEY) == -1, "invalid_admin_command_" + suffix);
        check(PermissionAPI.getRegisteredNodes().contains(PermissionUtil.register("interactions.admin")),
                "admin_permission_is_registered_with_neoforge");
        // Registered nodes are owned by PermissionAPI; the dynamic resolver only handles unregistered names.
        var handlerField = PermissionAPI.class.getDeclaredField("activeHandler"); handlerField.setAccessible(true);
        var previous = (IPermissionHandler) handlerField.get(null);
        var handler = new AuditPermissionHandler(previous); handlerField.set(null, handler);
        try {
            check(command("set " + target + " 50", alice.createCommandSourceStack().withPermission(4)) == 0
                    && influence.get(alice, KEY) == -1, "explicit_permission_denial_overrides_elevated_source");
            handler.allowed = true;
            check(command("set " + target + " 5", alice.createCommandSourceStack()) == 1
                    && influence.get(alice, KEY) == 5 && !alice.hasPermissions(2), "permission_grant_allows_nonoperator_administration");
            check(actions.runAll(List.of("player_command: interactions influence add " + target + " 2"), alice, NPC, KEY)
                    && influence.get(alice, KEY) == 7, "ordinary_action_command_uses_registered_admin_permission");
        } finally { handlerField.set(null, previous); }
        influence.set(alice, KEY, Integer.MAX_VALUE);
        check(command("add " + target + " 1", console) == 0 && influence.get(alice, KEY) == Integer.MAX_VALUE,
                "admin_overflow_reports_failure_without_mutation");
        alice.getInventory().clearContent(); alice.getInventory().add(new ItemStack(Items.PAPER));
        for (String malformed : List.of("set " + target + " not-a-number",
                "set " + alice.getGameProfile().getName() + " missing 1")) {
            check(!actions.runAll(List.of(PAYMENT, "console_command: interactions influence " + malformed), alice, NPC, KEY)
                    && alice.getInventory().countItem(Items.PAPER) == 1,
                    "owned_command_grammar_and_target_preflight_" + malformed);
        }
        var failed = actions.executeAll(List.of("console_command: interactions influence add " + target + " 1",
                "player_command_as_op: give @s minecraft:diamond 1"), alice, NPC, KEY);
        check(failed.result() == ActionExecution.Result.FAILED && alice.getInventory().countItem(Items.DIAMOND) == 0,
                "overflow_command_callback_fails_batch_and_stops_rewards");
        check(actions.runAll(List.of("influence: set;0", "console_command: interactions influence add " + target + " 1"), alice, NPC, KEY)
                && influence.get(alice, KEY) == 1, "owned_command_preflight_does_not_reject_future_valid_state");
        var failureStory = new Conversation(); failureStory.source = KEY + ".yml"; failureStory.saveProgress = true;
        var failureNode = new Conversation.Node("failedCommand"); var failureLine = new Conversation.Line();
        failureLine.key = "dialogue1"; failureLine.time = 0.05; failureLine.saveToPlayer = true; failureLine.text.add("Failure check");
        failureLine.actions.addAll(List.of("console_command: interactions influence add " + target + " 1",
                "player_command_as_op: give @s minecraft:diamond 1"));
        failureNode.lines.add(failureLine); failureStory.nodes.put(failureNode.key, failureNode);
        influence.set(alice, KEY, Integer.MAX_VALUE);
        var failedSession = new Session(controller, failureStory, failureNode, alice, null); failedSession.tick();
        check(failedSession.isFinished() && alice.getInventory().countItem(Items.DIAMOND) == 0
                && !controller.progress().hasSeen(alice.getUUID(), KEY + ".failedCommand.dialogue1")
                && !controller.progress().hasSeen(alice.getUUID(), KEY + ".failedCommand.completed"),
                "failed_influence_command_does_not_save_line_or_conversation");
    }

    private void session() {
        var story = library.byId(KEY); var node = story.first(); var line = node.lines.getFirst();
        node.optionsInDialogue = true; story.saveProgress = true; line.saveToPlayer = true;
        line.text.clear(); line.text.add("Value " + MARKER + " %next%"); line.text.add("%option_1%");
        line.actions.add("influence: add;2"); line.lastActions.add("influence: remove;1");
        var option = new Conversation.Option(); option.text = "Balance " + MARKER;
        option.requires.add(MARKER + " >= 1"); option.actions.add("influence: add;3"); node.options.add(option);
        node.interruptActions.add("influence: remove;2");
        influence.set(alice, KEY, 0); alice.clear();
        var session = new Session(controller, story, node, alice, null); session.tick(); alice.pump();
        check(influence.get(alice, KEY) == 2 && alice.chat.stream().anyMatch(c -> c.getString().startsWith("Value 0")),
                "session_renders_then_runs_context_bound_initial_action");
        check(session.skipDialogue(false), "influence_line_has_working_next_control");
        session.tick(); session.tick(); alice.pump();
        check(session.isAwaitingChoice() && session.offeredCount() == 1 && influence.get(alice, KEY) == 1
                && alice.chat.stream().anyMatch(c -> c.getString().contains("Balance 1")), "completion_updates_requirement_and_inline_label");
        check(session.chooseByText("Balance 1"), "typed_option_uses_expanded_influence_label"); session.tick();
        check(session.isFinished() && influence.get(alice, KEY) == 4
                && controller.progress().hasSeen(alice.getUUID(), KEY + ".conversation1.completed"),
                "option_action_uses_same_conversation_scope");
        var interrupted = new Session(controller, story, node, alice, null); interrupted.tick(); interrupted.end(false); interrupted.end(false);
        check(influence.get(alice, KEY) == 4, "interruption_applies_influence_once_with_context");
        check(influence.get(bob, KEY) == 0 && influence.get(alice, OTHER) == 0, "session_changes_do_not_leak_to_other_scopes");
    }

    boolean tick(ServerTickEvent.Post event) {
        if (done) return true;
        controller.onServerTick(event); elapsed++;
        if (elapsed == 1) check(influence.get(alice, KEY) == 3 && delayed.pending(), "influence_wait_has_no_early_effect");
        if (elapsed < 2) return false;
        check(delayed.result() == ActionExecution.Result.SUCCEEDED && influence.get(alice, KEY) == 6
                && alice.getInventory().countItem(Items.DIAMOND) == 6, "resumed_influence_and_command_placeholders_read_current_values");
        check(overflow.result() == ActionExecution.Result.FAILED && influence.get(bob, OTHER) == Integer.MAX_VALUE
                && bob.getInventory().countItem(Items.PAPER) == 1, "resumed_overflow_rejects_tail_before_payment");
        controller.onServerStopping(new ServerStoppingEvent(server)); done = true;
        LoggerFactory.getLogger("interactions").info("[INFLUENCEAUDIT] COMPLETE {} checks", passed);
        return true;
    }

    private int command(String arguments, CommandSourceStack source) throws Exception {
        try { return server.getCommands().getDispatcher().execute("interactions influence " + arguments, source); }
        catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { return 0; }
    }

    private void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        passed++; LoggerFactory.getLogger("interactions").info("[INFLUENCEAUDIT] PASS {}", name);
    }

    private static final class AuditPermissionHandler implements IPermissionHandler {
        private final IPermissionHandler delegate;
        boolean allowed;
        AuditPermissionHandler(IPermissionHandler delegate) { this.delegate = delegate; }
        public net.minecraft.resources.ResourceLocation getIdentifier() { return delegate.getIdentifier(); }
        public java.util.Set<PermissionNode<?>> getRegisteredNodes() { return delegate.getRegisteredNodes(); }
        @SuppressWarnings("unchecked")
        public <T> T getPermission(net.minecraft.server.level.ServerPlayer player, PermissionNode<T> node,
                PermissionDynamicContext<?>... context) {
            return node.getNodeName().equals("interactions.admin") ? (T) Boolean.valueOf(allowed)
                    : delegate.getPermission(player, node, context);
        }
        public <T> T getOfflinePermission(UUID player, PermissionNode<T> node, PermissionDynamicContext<?>... context) {
            return delegate.getOfflinePermission(player, node, context);
        }
    }
}
