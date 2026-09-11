package net.yuuniverse.interactions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "interactions")
public final class SkipRuntimeAudit {
    private static boolean ran;

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (ran || event.getServer().getTickCount() < 60) return;
        ran = true;
        var controller = new InteractionsMod();
        NeoForge.EVENT_BUS.unregister(controller);
        Session session = null;
        try {
            var messages = new ArrayList<Component>();
            var player = new FakePlayer(event.getServer().overworld(), new GameProfile(UUID.randomUUID(), "SkipAudit")) {
                @Override public void sendSystemMessage(Component message) { messages.add(message); }
            };
            set(controller, "actions", new Actions(new ItemLibrary(), new Economy()));
            set(controller, "messages", new DialogueMessages("&aContinue", "&eProceed"));
            var story = new Conversation();
            var node = new Conversation.Node("conversation1");
            var line = new Conversation.Line();
            line.time = -1;
            line.text.add("Audit %next%");
            line.actions.add("player_command_as_op: give @s minecraft:paper 1");
            line.lastActions.add("player_command_as_op: give @s minecraft:diamond 1");
            node.lines.add(line);
            node.options.add(new Conversation.Option());
            session = new Session(controller, story, node, player, null);
            var sessionField = InteractionsMod.class.getDeclaredField("sessions");
            sessionField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var sessions = (Map<UUID, Session>) sessionField.get(controller);
            sessions.put(player.getUUID(), session);
            var dispatcher = new CommandDispatcher<CommandSourceStack>();
            controller.onRegisterCommands(new net.neoforged.neoforge.event.RegisterCommandsEvent(dispatcher,
                    net.minecraft.commands.Commands.CommandSelection.DEDICATED,
                    net.minecraft.commands.CommandBuildContext.simple(event.getServer().registryAccess(),
                            event.getServer().getWorldData().enabledFeatures())));
            check(!session.skipDialogue(false), "cannot_skip_before_line_starts");
            session.tick();
            check(player.getInventory().countItem(Items.PAPER) == 1, "initial_actions_run_once");
            for (int tick = 0; tick < 100; tick++) session.tick();
            check(!session.isFinished() && !session.isAwaitingChoice()
                    && player.getInventory().countItem(Items.DIAMOND) == 0, "negative_one_waits_for_manual_advance");
            check(messages.stream().anyMatch(message -> message.getString().contains("Audit Continue"))
                    && messages.stream().noneMatch(message -> message.getString().contains("%next%")), "next_marker_rendered");
            check(messages.stream().flatMap(message -> message.toFlatList().stream()).anyMatch(component ->
                    component.getStyle().getClickEvent() != null
                    && component.getStyle().getClickEvent().getValue().equals("/interactions skipdialogue")
                    && component.getStyle().getHoverEvent() != null), "next_button_has_command_and_hover");
            check(dispatcher.execute("interactions skipdialogue", player.createCommandSourceStack()) == 1
                    && player.getInventory().countItem(Items.DIAMOND) == 0, "command_defers_completion_actions");
            check(dispatcher.execute("interactions skipdialogue", player.createCommandSourceStack()) == 0,
                    "duplicate_click_is_not_queued");
            session.tick();
            check(player.getInventory().countItem(Items.DIAMOND) == 1
                    && player.getInventory().countItem(Items.PAPER) == 1, "skip_runs_last_actions_once");
            check(!session.skipDialogue(false), "finished_line_cannot_be_skipped_again");
            session.tick();
            check(session.isAwaitingChoice() && !session.skipDialogue(false), "skip_does_not_bypass_options");
            session.end(false);
            line.text.clear();
            line.text.add("Ordinary text");
            session = new Session(controller, story, node, player, null);
            session.tick();
            check(!session.skipDialogue(false) && !session.skipDialogue(true), "ordinary_line_not_skippable_by_default");
            set(controller, "settings", new DialogueSettings(false, false, false, List.of(), true));
            check(session.skipDialogue(true), "configured_npc_skip_accepts_ordinary_line");
            session.tick();
            session.tick();
            check(player.getInventory().countItem(Items.DIAMOND) == 2 && session.isAwaitingChoice(),
                    "npc_skip_completes_once_and_reaches_options");
            LoggerFactory.getLogger("interactions").info("[SKIPAUDIT] COMPLETE 13/13");
        } catch (Throwable failure) {
            LoggerFactory.getLogger("interactions").error("[SKIPAUDIT] FAILED", failure);
        } finally {
            if (session != null) session.end(false);
        }
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        LoggerFactory.getLogger("interactions").info("[SKIPAUDIT] PASS {}", name);
    }
}
