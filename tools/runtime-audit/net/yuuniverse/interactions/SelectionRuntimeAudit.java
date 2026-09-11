package net.yuuniverse.interactions;

import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "interactions")
public final class SelectionRuntimeAudit {
    private static boolean ran;
    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (ran || event.getServer().getTickCount() < 80) return;
        ran = true;
        Session session = null;
        ServerPlayer player = null;
        try {
            var server = event.getServer();
            var output = new ArrayList<Component>();
            player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), "SelectAudit"),
                    ClientInformation.createDefault()) {
                @Override public void sendSystemMessage(Component message) { output.add(message); }
            };
            player.setPos(0, -55, 0);
            player.setYRot(0);
            player.setXRot(0);
            player.connection = new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), player,
                    CommonListenerCookie.createInitial(player.getGameProfile(), false));
            player.serverLevel().addNewPlayer(player);
            var selection = new AtomicReference<>(SelectionSettings.DEFAULT);
            var engine = new Session.Engine() {
                public Actions actions() { return new Actions(new ItemLibrary(), new Economy()); }
                public ProgressStore progress() { return new ProgressStore(new java.io.File("config/selection-audit-progress")); }
                public DialogueSettings settings() {
                    return new DialogueSettings(false, false, false, List.of(), false, true, true, false, selection.get());
                }
                public DialogueMessages messages() {
                    return new DialogueMessages(null, null, null, null, List.of("%options%"), "ROW %number% %text%", "SELECT %number% %text%");
                }
            };
            var story = new Conversation();
            story.blockMovement = true;
            var node = new Conversation.Node("conversation1");
            var line = new Conversation.Line();
            line.time = 0;
            line.text.add("Question");
            line.actions.add("player_command_as_op: give @s minecraft:paper 1");
            line.lastActions.add("player_command_as_op: give @s minecraft:diamond 1");
            node.lines.add(line);
            for (String reward : List.of("emerald", "gold_ingot", "book")) {
                var option = new Conversation.Option();
                option.text = reward;
                option.actions.add("player_command_as_op: give @s minecraft:" + reward + " 1");
                node.options.add(option);
            }
            session = new Session(engine, story, node, player, null);
            for (int i = 0; i < 3; i++) session.tick();
            check(output.stream().anyMatch(message -> message.getString().equals("SELECT 1 emerald")), "first_option_selected_and_formatted");
            move(player, 0, -0.1);
            check(index(session) == 1 && player.getX() == 0 && player.getZ() == 0, "backward_input_selects_next_without_moving");
            move(player, 0, -0.1);
            check(index(session) == 1, "movement_repeat_delay_prevents_double_step");
            resetDelay(session);
            move(player, 0, 0.1);
            check(index(session) == 0, "forward_input_selects_previous");
            resetDelay(session);
            move(player, 0.1, 0);
            check(index(session) == 0, "sideways_input_does_not_select");
            selection.set(new SelectionSettings(true, SelectionSettings.Mode.SCROLL, true));
            resetDelay(session);
            player.connection.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(8));
            check(index(session) == 2 && player.getInventory().selected == 0, "scroll_wraps_backward_and_keeps_hotbar");
            resetDelay(session);
            player.connection.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(1));
            check(index(session) == 0, "scroll_wraps_forward");
            selection.set(new SelectionSettings(true, SelectionSettings.Mode.SCROLL, false));
            resetDelay(session);
            player.connection.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(8));
            check(index(session) == 0, "disabled_overflow_stops_at_first_option");
            selection.set(new SelectionSettings(false, SelectionSettings.Mode.SCROLL, true));
            player.connection.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(1));
            check(player.getInventory().selected == 1, "disabled_selection_restores_hotbar_input");
            selection.set(new SelectionSettings(true, SelectionSettings.Mode.SCROLL, true));
            resetDelay(session);
            player.connection.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(2));
            player.connection.handlePlayerCommand(new ServerboundPlayerCommandPacket(player,
                    ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
            check(!session.isAwaitingChoice() && player.getInventory().countItem(Items.GOLD_INGOT) == 0,
                    "sneak_confirmation_defers_reward");
            session.tick();
            check(session.isFinished() && player.getInventory().countItem(Items.GOLD_INGOT) == 1,
                    "sneak_confirms_selected_reward_once");
            check(player.getInventory().countItem(Items.PAPER) == 1 && player.getInventory().countItem(Items.DIAMOND) == 1,
                    "selection_redraw_does_not_repeat_dialogue_actions");
            story.blockMovement = false;
            session = new Session(engine, story, node, player, null);
            for (int i = 0; i < 3; i++) session.tick();
            player.connection.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(3));
            check(player.getInventory().selected == 3, "unlocked_dialogue_does_not_capture_selection");
            session.end(false);
            story.blockMovement = true;
            session = new Session(engine, story, node, player, null);
            for (int i = 0; i < 3; i++) session.tick();
            check(session.cycleSelection(1, 1000) && !session.cycleSelection(1, 1199)
                    && session.cycleSelection(1, 1200) && index(session) == 2, "repeat_delay_accepts_exact_200ms_boundary");
            LoggerFactory.getLogger("interactions").info("[SELECTIONAUDIT] COMPLETE 14/14");
        } catch (Throwable failure) {
            LoggerFactory.getLogger("interactions").error("[SELECTIONAUDIT] FAILED", failure);
        } finally {
            if (session != null) session.end(false);
            if (player != null) player.serverLevel().removePlayerImmediately(player,
                    net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        }
    }

    private static int index(Session session) throws ReflectiveOperationException {
        var field = Session.class.getDeclaredField("selectedOption");
        field.setAccessible(true);
        return field.getInt(session);
    }
    private static void resetDelay(Session session) throws ReflectiveOperationException {
        var field = Session.class.getDeclaredField("selectionDelay");
        field.setAccessible(true);
        field.setLong(session, 0);
    }
    private static void move(ServerPlayer player, double dx, double dz) throws ReflectiveOperationException {
        player.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(player.getX() + dx, player.getY(), player.getZ() + dz, false));
        var field = ServerGamePacketListenerImpl.class.getDeclaredField("awaitingTeleport");
        field.setAccessible(true);
        player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(field.getInt(player.connection)));
    }
    private static void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        LoggerFactory.getLogger("interactions").info("[SELECTIONAUDIT] PASS {}", name);
    }
}
