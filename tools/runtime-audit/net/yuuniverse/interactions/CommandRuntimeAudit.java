package net.yuuniverse.interactions;

import java.io.File;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.mojang.authlib.GameProfile;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ArgumentSignatures;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "interactions")
public final class CommandRuntimeAudit {
    private static boolean ran;

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (ran || event.getServer().getTickCount() < 55) return;
        ran = true;
        Session session = null;
        Session replacement = null;
        try {
            var server = event.getServer();
            var player = new ServerPlayer(server, server.overworld(),
                    new GameProfile(UUID.randomUUID(), "CommandAudit"), ClientInformation.createDefault());
            var listener = new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), player,
                    CommonListenerCookie.createInitial(player.getGameProfile(), false));
            player.connection = listener;
            var calls = new AtomicInteger();
            server.getCommands().getDispatcher().register(Commands.literal("dialogueauditcounter")
                    .executes(context -> calls.incrementAndGet()));
            var unsigned = ServerGamePacketListenerImpl.class.getDeclaredMethod("performUnsignedChatCommand", String.class);
            unsigned.setAccessible(true);
            var signed = ServerGamePacketListenerImpl.class.getDeclaredMethod("performSignedChatCommand",
                    ServerboundChatCommandSignedPacket.class, LastSeenMessages.class);
            signed.setAccessible(true);
            var packet = new ServerboundChatCommandSignedPacket("dialogueauditcounter", java.time.Instant.now(), 0,
                    ArgumentSignatures.EMPTY, new LastSeenMessages.Update(0, new java.util.BitSet()));
            unsigned.invoke(listener, "dialogueauditcounter");
            check(calls.get() == 1, "unsigned_executes_without_dialogue");
            var settings = new AtomicReference<>(DialogueSettings.DEFAULT);
            var engine = new Session.Engine() {
                public Actions actions() { return new Actions(new ItemLibrary(), new Economy()); }
                public ProgressStore progress() { return new ProgressStore(new File("config/command-audit-progress")); }
                public DialogueSettings settings() { return settings.get(); }
            };
            var story = new Conversation();
            var node = new Conversation.Node("conversation1");
            session = new Session(engine, story, node, player, null);
            unsigned.invoke(listener, "dialogueauditcounter");
            check(calls.get() == 1, "unsigned_input_blocked");
            signed.invoke(listener, packet, LastSeenMessages.EMPTY);
            check(calls.get() == 1, "signed_input_blocked");
            server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "dialogueauditcounter");
            check(calls.get() == 2, "internal_player_command_still_executes");
            settings.set(new DialogueSettings(false, false, false, List.of("/dialogueauditcounter")));
            unsigned.invoke(listener, "dialogueauditcounter");
            check(calls.get() == 3, "whitelist_allows_unsigned_input");
            signed.invoke(listener, packet, LastSeenMessages.EMPTY);
            check(calls.get() == 4, "whitelist_allows_signed_input");
            settings.set(new DialogueSettings(false, false, true, List.of()));
            unsigned.invoke(listener, "dialogueauditcounter");
            check(calls.get() == 5, "allow_commands_setting_applies");
            settings.set(DialogueSettings.DEFAULT);
            check(!DialogueCommands.isBlocked(player.getUUID(), "interactions choose 1"), "option_click_remains_allowed");
            replacement = new Session(engine, story, node, player, null);
            session.end(false);
            unsigned.invoke(listener, "dialogueauditcounter");
            check(calls.get() == 5, "old_session_does_not_release_replacement");
            replacement.end(false);
            unsigned.invoke(listener, "dialogueauditcounter");
            check(calls.get() == 6, "commands_restored_after_dialogue");
            LoggerFactory.getLogger("interactions").info("[COMMANDAUDIT] COMPLETE 10/10");
        } catch (Throwable failure) {
            LoggerFactory.getLogger("interactions").error("[COMMANDAUDIT] FAILED", failure);
        } finally {
            if (session != null) session.end(false);
            if (replacement != null) replacement.end(false);
        }
    }

    private static void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        LoggerFactory.getLogger("interactions").info("[COMMANDAUDIT] PASS {}", name);
    }
}
