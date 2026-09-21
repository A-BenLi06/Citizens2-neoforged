package net.yuuniverse.interactions;

import java.io.File;
import java.util.List;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.yuuniverse.interactions.DialogueDisplayRuntimeAudit.AuditPlayer;
import org.slf4j.LoggerFactory;

/** Real inventory/progress predicates and all five session requirement gates. */
final class ConditionsRuntimeAudit {
    private static final String PAPER = "%checkitem_mat:minecraft:paper,amt:1%";
    private static final String GOLD = "%checkitem_mat:minecraft:gold_ingot,amt:1%";
    private static final String ELIGIBLE = GOLD + " == yes or %interactions_has_dialogue_ticket% == true";
    private static final String PAYMENT = "remove_item: %checkitem_remove_mat:minecraft:paper,amt:1%";
    private final AuditPlayer alice, bob;
    private final ProgressStore progress = new ProgressStore(new File("config/condition-audit-players"));
    private final Actions actions = new Actions(new ItemLibrary(), new Economy());
    private final Session.Engine engine = new Session.Engine() {
        public Actions actions() { return actions; }
        public ProgressStore progress() { return progress; }
        public DialogueSettings settings() { return DialogueSettings.DEFAULT; }
        public DialogueMessages messages() { return DialogueMessages.DEFAULT; }
    };
    private int passed;

    private ConditionsRuntimeAudit(AuditPlayer alice, AuditPlayer bob) { this.alice = alice; this.bob = bob; }

    static void run(AuditPlayer alice, AuditPlayer bob) throws Exception {
        var audit = new ConditionsRuntimeAudit(alice, bob);
        try {
            audit.predicates(); audit.unreadableProgress(); audit.routing(); audit.options();
            LoggerFactory.getLogger("interactions").info("[CONDITIONAUDIT] COMPLETE {} checks", audit.passed);
        } finally { audit.actions.reset(true); }
    }

    private boolean condition(String expression) { return Conditions.all(List.of(expression), alice, progress); }

    private void predicates() {
        alice.getInventory().clearContent(); bob.getInventory().clearContent(); alice.setExperienceLevels(3);
        ItemStack paper = new ItemStack(Items.PAPER, 3);
        paper.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Rank > 3 or Class == A checkitem_remove_3"))));
        alice.getInventory().add(paper);
        check(condition(PAPER + " and " + GOLD + " == yes and no"), "complete_item_operands_keep_literal_and");
        check(!Conditions.all(List.of(PAPER + " and " + GOLD + " == yes and no"), bob, progress), "inventory_scope_is_player_specific");
        check(condition(GOLD + " == yes or " + PAPER + " == yes"), "later_item_alternative_succeeds");
        check(condition("%checkitem_lorecontains:Rank > 3 or Class == A,amt:1% == yes"), "lore_operators_are_data");
        check(condition("%checkitem_remove_lorecontains:checkitem_remove_3,amt:1% == yes"), "remove_spelling_does_not_rewrite_lore");
        check(condition("%checkitem_lorecontains:checkitem_remove_{player_level},amt:1% == yes"), "brace_arguments_use_live_native_values");
        for (int i = 0; i < 3; i++)
            check(condition("%checkitem_remove_mat:minecraft:paper,amt:3% == yes"), "repeated_remove_predicate_is_read_only");
        check(alice.getInventory().countItem(Items.PAPER) == 3, "predicates_leave_inventory_unchanged");
        for (String token : List.of("%checkitem_unsupported:x%", "%checkitem_mat:minecraft:paper,amt:bad%",
                "%checkitem_mat:minecraft:paper,amt:0%", "%quests_missing%", "%unknown_%player_level%%")) {
            check(!condition(token + " == no"), "unknown_or_invalid_is_not_no_" + token);
            check(!condition("prefix " + token + " != other"), "unknown_or_invalid_cannot_satisfy_negation_" + token);
        }
        check(condition("%quests_missing% != 0 or " + PAPER + " == yes"), "unavailable_provider_does_not_block_valid_alternative");
        check(condition("Level %player_level% == Level 3"), "native_token_in_literal_text");
        progress.markSeen(alice.getUUID(), alice.getGameProfile().getName(), "ticket");
        check(condition("%interactions_has_dialogue_ticket% and " + PAPER + " == true and yes"), "progress_and_inventory_share_operand_expansion");
        check(!Conditions.all(List.of("%interactions_has_dialogue_ticket% == true"), bob, progress), "progress_scope_is_player_specific");
        check(!Conditions.all(List.of(PAPER + " == yes", GOLD + " == yes"), alice, progress), "requirement_list_is_anded");
    }

    private void unreadableProgress() throws Exception {
        var folder = java.nio.file.Path.of("config/condition-audit-corrupt");
        java.nio.file.Files.createDirectories(folder);
        var file = folder.resolve(alice.getUUID() + ".yml");
        String invalid = "saved_dialogues: []\ninfluence: [malformed]\n";
        java.nio.file.Files.writeString(file, invalid);
        var broken = new ProgressStore(folder.toFile()); broken.loadAll();
        check(!broken.isReadable(alice.getUUID()), "corrupt_progress_is_unreadable");
        check(!Conditions.all(List.of("%interactions_has_dialogue_ticket% == false"), alice, broken),
                "unreadable_progress_cannot_satisfy_absent_dialogue");
        check(Conditions.all(List.of("%interactions_has_dialogue_ticket% == false or " + PAPER + " == yes"), alice, broken),
                "unreadable_progress_still_allows_independent_item_alternative");
        check(java.nio.file.Files.readString(file).equals(invalid), "condition_does_not_rewrite_corrupt_progress");
    }

    private void routing() {
        var story = story("condition-route"); var node = story.first(); var line = node.lines.getFirst();
        var redirect = new Conversation.Conditional(); redirect.requires.add(ELIGIBLE); redirect.startConversation = "target";
        line.conditional.add(redirect); line.actions.add("player_command_as_op: give @s minecraft:diamond 1");
        var target = new Conversation.Node("target"); var targetLine = new Conversation.Line();
        targetLine.key = "dialogue1"; targetLine.time = -1; targetLine.text.add("Redirected");
        targetLine.actions.add("player_command_as_op: give @s minecraft:emerald 1"); target.lines.add(targetLine); story.nodes.put(target.key, target);
        alice.getInventory().clearContent();
        Session session = new Session(engine, story, node, alice, null);
        try {
            session.tick(); session.tick();
            check(alice.getInventory().countItem(Items.EMERALD) == 1 && alice.getInventory().countItem(Items.DIAMOND) == 0,
                    "conditional_or_redirect_precedes_source_rewards");
        } finally { session.end(false); }

        var gated = story("condition-line"); gated.first().lines.getFirst().requires.add(GOLD + " == yes or %player_level% > 10");
        gated.first().lines.getFirst().actions.add("player_command_as_op: give @s minecraft:diamond 1");
        gated.first().lines.getFirst().saveToPlayer = true;
        alice.getInventory().add(new ItemStack(Items.GOLD_INGOT));
        Session denied = new Session(engine, gated, gated.first(), alice, null);
        alice.getInventory().clearContent();
        try {
            denied.tick();
            check(alice.getInventory().countItem(Items.DIAMOND) == 0
                    && !progress.hasSeen(alice.getUUID(), "condition-line.conversation1.dialogue1"), "line_gate_reads_current_state_before_rewards_and_progress");
        } finally { denied.end(false); }
    }

    private void options() {
        var story = story("condition-option"); story.saveProgress = true;
        var option = new Conversation.Option(); option.text = "Claim"; option.requires.add(ELIGIBLE);
        option.actions.addAll(List.of(PAYMENT, "player_command_as_op: give @s minecraft:diamond 1"));
        var hidden = new Conversation.Option(); hidden.text = "Unavailable"; hidden.requires.add("%quests_missing% != 0");
        story.first().options.add(hidden); story.first().options.add(option);
        alice.getInventory().clearContent(); alice.getInventory().add(new ItemStack(Items.PAPER));
        // A new player has no saved branch; revoke its item branch after the menu was offered.
        bob.getInventory().add(new ItemStack(Items.PAPER)); bob.getInventory().add(new ItemStack(Items.GOLD_INGOT));
        Session stale = new Session(engine, story, story.first(), bob, null);
        try {
            ready(stale);
            check(stale.offeredCount() == 1, "unknown_option_hidden_and_or_option_filtered");
            bob.getInventory().items.stream().filter(stack -> stack.is(Items.GOLD_INGOT)).forEach(stack -> stack.setCount(0));
            check(!stale.choose(1) && bob.getInventory().countItem(Items.PAPER) == 1, "choice_rechecks_revoked_or_branches");
            progress.markSeen(bob.getUUID(), bob.getGameProfile().getName(), "ticket");
            check(stale.choose(1), "fresh_saved_progress_enables_original_offered_option"); stale.tick();
            check(bob.getInventory().countItem(Items.PAPER) == 0 && bob.getInventory().countItem(Items.DIAMOND) == 1
                    && progress.hasSeen(bob.getUUID(), "condition-option.conversation1.completed"), "accepted_alternative_pays_rewards_and_saves_once");
        } finally { stale.end(false); }

        // Use an unsaved progress key so the inventory branch is the only one available for Alice.
        story.source = "condition-pending.yml"; option.requires.clear();
        option.requires.add(GOLD + " == yes or %interactions_has_dialogue_pending-ticket% == true");
        alice.getInventory().add(new ItemStack(Items.GOLD_INGOT));
        Session pending = new Session(engine, story, story.first(), alice, null);
        try {
            ready(pending); check(pending.choose(1), "valid_choice_waits_for_session_tick");
            alice.getInventory().items.stream().filter(stack -> stack.is(Items.GOLD_INGOT)).forEach(stack -> stack.setCount(0)); pending.tick();
            check(pending.isFinished() && alice.getInventory().countItem(Items.PAPER) == 1
                    && alice.getInventory().countItem(Items.DIAMOND) == 0
                    && !progress.hasSeen(alice.getUUID(), "condition-pending.conversation1.completed"), "pending_execution_rechecks_before_payment_rewards_and_progress");
        } finally { pending.end(false); }
    }

    private static Conversation story(String id) {
        var story = new Conversation(); story.source = id + ".yml";
        var node = new Conversation.Node("conversation1"); var line = new Conversation.Line();
        line.key = "dialogue1"; line.time = 0; line.text.add("Requirements"); node.lines.add(line); story.nodes.put(node.key, node);
        return story;
    }
    private void ready(Session session) {
        for (int i = 0; i < 4 && !session.isAwaitingChoice() && !session.isFinished(); i++) session.tick();
        check(session.isAwaitingChoice(), "session_reaches_filtered_options");
    }
    private void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        passed++; LoggerFactory.getLogger("interactions").info("[CONDITIONAUDIT] PASS {}", name);
    }
}
