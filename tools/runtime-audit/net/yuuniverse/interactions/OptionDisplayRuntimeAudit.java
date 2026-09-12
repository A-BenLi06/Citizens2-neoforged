package net.yuuniverse.interactions;

import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "interactions")
public final class OptionDisplayRuntimeAudit {
    private static boolean ran;

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (ran || net.citizensnpcs.audit.FixtureRuntimeAudit.elapsedTicks(event.getServer()) < 75) return;
        ran = true;
        Session session = null;
        try {
            var output = new ArrayList<Component>();
            var player = new FakePlayer(event.getServer().overworld(), new GameProfile(UUID.randomUUID(), "DisplayAudit")) {
                @Override public void sendSystemMessage(Component message) { output.add(message); }
            };
            var messages = new AtomicReference<>(new DialogueMessages(null, null, "&6%number% => %text%", "Pick %option%",
                    List.of("Before", "%options%", "After")));
            var settings = new AtomicReference<>(new DialogueSettings(false, false, false, List.of(), false, true, true, false));
            var engine = new Session.Engine() {
                public Actions actions() { return new Actions(new ItemLibrary(), new Economy()); }
                public ProgressStore progress() { return new ProgressStore(new java.io.File("config/option-display-audit")); }
                public DialogueSettings settings() { return settings.get(); }
                public DialogueMessages messages() { return messages.get(); }
            };
            var node = new Conversation.Node("conversation1");
            var first = new Conversation.Option();
            first.text = "First %player%";
            node.options.add(first);
            var hidden = new Conversation.Option();
            hidden.text = "Hidden";
            hidden.requires.add("yes == no");
            node.options.add(hidden);
            var last = new Conversation.Option();
            last.text = "Third";
            last.actions.add("player_command_as_op: give @s minecraft:paper 1");
            node.options.add(last);
            var story = new Conversation();
            session = new Session(engine, story, node, player, null);
            session.tick();
            check(output.stream().map(Component::getString).toList().equals(List.of("Before", "1 => First DisplayAudit",
                    "2 => Third", "After")), "custom_layout_and_filtered_numbering");
            var option = output.get(2);
            check(option.getStyle().getClickEvent() != null
                    && option.getStyle().getClickEvent().getValue().equals("/interactions choose 2"), "click_uses_displayed_number");
            check(option.getStyle().getHoverEvent().getValue(HoverEvent.Action.SHOW_TEXT).getString().equals("Pick 2"),
                    "custom_hover_substitutes_option_number");
            check(option.toFlatList().stream().anyMatch(part -> part.getStyle().getColor() != null
                    && part.getStyle().getColor().getValue() == net.minecraft.ChatFormatting.GOLD.getColor()),
                    "legacy_option_color_preserved");
            session.chooseByText("2");
            session.tick();
            check(session.isFinished() && player.getInventory().countItem(Items.PAPER) == 1, "displayed_number_selects_correct_reward");
            output.clear();
            settings.set(new DialogueSettings(false, false, false, List.of(), false, true, false, false));
            session = new Session(engine, story, node, player, null);
            session.tick();
            check(output.stream().flatMap(message -> message.toFlatList().stream()).allMatch(part ->
                    part.getStyle().getClickEvent() == null && part.getStyle().getHoverEvent() == null), "disabled_clicks_have_no_click_or_hover");
            check(session.chooseByText("2"), "typed_choice_remains_available");
            session.end(false);
            output.clear();
            messages.set(DialogueMessages.DEFAULT);
            session = new Session(engine, story, node, player, null);
            session.tick();
            check(output.get(output.size() - 1).getString().equals("Enter an option number in chat."), "default_prompt_matches_typed_input");
            session.end(false);
            output.clear();
            settings.set(DialogueSettings.DEFAULT);
            session = new Session(engine, story, node, player, null);
            session.tick();
            check(output.get(0).getString().isEmpty()
                    && output.get(output.size() - 1).getString().equals("Click an option or enter its number in chat."),
                    "default_spacing_and_click_prompt");
            check(session.chooseByText("First DisplayAudit"), "typed_text_matches_expanded_player_name");
            session.end(false);
            output.clear();
            settings.set(new DialogueSettings(false, false, false, List.of(), false, true, true, false));
            messages.set(new DialogueMessages(null, null, null, null, null, null, null, "&6[%name%]"));
            story.name = "Guide";
            var dialogue = new Conversation.Node("speaker");
            var line = new Conversation.Line();
            line.text.addAll(List.of("First line", "Second line"));
            line.time = 100;
            dialogue.lines.add(line);
            session = new Session(engine, story, dialogue, player, null);
            session.tick();
            check(output.stream().map(Component::getString).toList().equals(List.of("[Guide]", "First line", "Second line")),
                    "speaker_heading_once_before_multiline_body");
            check(output.get(0).toFlatList().stream().anyMatch(part -> part.getStyle().getColor() != null
                    && part.getStyle().getColor().getValue() == net.minecraft.ChatFormatting.GOLD.getColor()),
                    "speaker_heading_preserves_configured_color");
            session.end(false);
            output.clear();
            line.showName = false;
            session = new Session(engine, story, dialogue, player, null);
            session.tick();
            check(output.stream().map(Component::getString).toList().equals(List.of("First line", "Second line")),
                    "show_name_false_omits_heading");
            session.end(false);
            output.clear();
            line.showName = true;
            messages.set(new DialogueMessages(null, null, null, null, null, null, null, ""));
            session = new Session(engine, story, dialogue, player, null);
            session.tick();
            check(output.size() == 2 && output.get(0).getString().equals("First line"), "empty_name_format_suppresses_heading");
            session.end(false);
            output.clear();
            messages.set(DialogueMessages.DEFAULT);
            settings.set(DialogueSettings.DEFAULT);
            line.text.clear();
            line.text.add("Continue %next%");
            session = new Session(engine, story, dialogue, player, null);
            session.tick();
            check(output.size() == 3 && output.get(0).getString().isEmpty()
                    && output.get(1).getString().equals("Guide :"), "dialogue_spacing_precedes_default_speaker_heading");
            check(output.get(2).toFlatList().stream().anyMatch(part -> part.getStyle().getClickEvent() != null
                    && part.getStyle().getClickEvent().getValue().equals("/interactions skipdialogue")),
                    "separate_heading_preserves_next_button");
            session.end(false);
            output.clear();
            settings.set(new DialogueSettings(false, false, false, List.of(), false, true, true, false));
            line.showName = false;
            line.text.clear();
            line.text.add("""
                    json:{"text":"Hello %player%","color":"gold","extra":[{"text":" json: link","clickEvent":{"action":"suggest_command","value":"/help %player%"},"hoverEvent":{"action":"show_text","contents":{"text":"For %player%"}}}]}
                    """.strip());
            session = new Session(engine, story, dialogue, player, null);
            session.tick();
            check(output.size() == 1 && output.get(0).getString().equals("Hello DisplayAudit json: link"),
                    "json_dialogue_expands_player_without_literal_serialization");
            check(output.get(0).toFlatList().stream().anyMatch(part -> part.getStyle().getColor() != null
                    && part.getStyle().getColor().getValue() == net.minecraft.ChatFormatting.GOLD.getColor()),
                    "json_dialogue_preserves_color");
            check(output.get(0).toFlatList().stream().anyMatch(part -> part.getStyle().getClickEvent() != null
                    && part.getStyle().getClickEvent().getValue().equals("/help DisplayAudit")), "json_dialogue_preserves_click");
            check(output.get(0).toFlatList().stream().anyMatch(part -> part.getStyle().getHoverEvent() != null
                    && part.getStyle().getHoverEvent().getValue(HoverEvent.Action.SHOW_TEXT).getString().equals("For DisplayAudit")),
                    "json_dialogue_preserves_hover");
            session.end(false);
            output.clear();
            line.text.add("json:{broken");
            player.getInventory().clearContent();
            player.getInventory().add(new net.minecraft.world.item.ItemStack(Items.PAPER, 2));
            line.actions.add("remove_item: %checkitem_remove_mat:minecraft:paper,amt:1%");
            line.actions.add("player_command_as_op: give @s minecraft:diamond 1");
            session = new Session(engine, story, dialogue, player, null);
            session.tick();
            check(session.isFinished() && player.getInventory().countItem(Items.PAPER) == 2
                    && player.getInventory().countItem(Items.DIAMOND) == 0, "invalid_json_ends_before_payment_and_reward");
            check(output.isEmpty(), "invalid_json_prevents_partial_dialogue_output");
            LoggerFactory.getLogger("interactions").info("[OPTIONDISPLAYAUDIT] COMPLETE 22/22");
        } catch (Throwable failure) {
            LoggerFactory.getLogger("interactions").error("[OPTIONDISPLAYAUDIT] FAILED", failure);
        } finally {
            if (session != null) session.end(false);
        }
    }

    private static void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        LoggerFactory.getLogger("interactions").info("[OPTIONDISPLAYAUDIT] PASS {}", name);
    }
}
