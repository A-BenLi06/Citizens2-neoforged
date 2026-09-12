package net.citizensnpcs.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.speech.event.NPCSpeechEvent;
import net.citizensnpcs.api.ai.speech.event.SpeechBystanderEvent;
import net.citizensnpcs.api.ai.speech.event.SpeechTargetedEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.trait.HologramTrait;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

/** Exercises the registered command and speech events in the isolated server, without a connected client. */
@EventBusSubscriber(modid = "citizens")
public final class SpeechRuntimeAudit {
    private static boolean ran;
    private static int passed;

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (ran || FixtureRuntimeAudit.elapsedTicks(event.getServer()) < 100) return;
        ran = true;
        var server = event.getServer();
        var level = server.overworld();
        var source = server.createCommandSourceStack().withPermission(4).withSuppressedOutput();
        var selector = CitizensAPI.getDefaultNPCSelector();
        NPC previousSelection = selector.getSelected(source);
        var registry = CitizensAPI.getNPCRegistry();
        var created = new ArrayList<NPC>();
        var players = new ArrayList<Recipient>();
        var settings = Map.<Setting, Object>of(
                Setting.CHAT_RANGE, Setting.CHAT_RANGE.asDouble(),
                Setting.CHAT_BYSTANDERS_HEAR_TARGETED_CHAT, Setting.CHAT_BYSTANDERS_HEAR_TARGETED_CHAT.asBoolean(),
                Setting.CHAT_FORMAT, Setting.CHAT_FORMAT.asString(),
                Setting.CHAT_FORMAT_TO_TARGET, Setting.CHAT_FORMAT_TO_TARGET.asString(),
                Setting.CHAT_FORMAT_TO_BYSTANDERS, Setting.CHAT_FORMAT_TO_BYSTANDERS.asString());
        SpeechRecorder recorder = null;
        try {
            Setting.CHAT_RANGE.set(2);
            Setting.CHAT_BYSTANDERS_HEAR_TARGETED_CHAT.set(false);
            Setting.CHAT_FORMAT.set("[<npc>]: <text>");
            Setting.CHAT_FORMAT_TO_TARGET.set("<npc>: <text>");
            Setting.CHAT_FORMAT_TO_BYSTANDERS.set("[<npc>] -> [<target>]: <text>");

            NPC actor = registry.createNPC(EntityType.PIG, "SpeechAudit");
            created.add(actor);
            // The old NPC 35 has this retired scalar outside its traitnames index.
            var legacy = new MemoryDataKey();
            legacy.setString("name", "SpeechAudit");
            legacy.setString("traitnames", "type");
            legacy.setString("traits.type", "PIG");
            legacy.setString("traits.speech", "chat");
            actor.load(legacy);
            check(actor.getRawName().equals("SpeechAudit") && legacy.getString("traits.speech").equals("chat")
                    && CitizensAPI.getTraitFactory().getTraitClass("speech") == null,
                    "legacy_unindexed_speech_scalar_loads_without_a_trait");
            NPC target = registry.createNPC(EntityType.PIG, "SpeechTarget");
            created.add(target);
            if (!actor.spawn(new Location(level, 80, -40, 40))
                    || !target.spawn(new Location(level, 82, -40, 40))) throw new AssertionError("NPC spawn failed");
            selector.select(source, actor);
            recorder = new SpeechRecorder(actor);
            NeoForge.EVENT_BUS.register(recorder);
            recorder.cancelSpeech = true;
            speak(server, source, "context");
            check(recorder.speeches.size() == 1 && recorder.speeches.getFirst().getContext().getTalker() != null
                    && recorder.speeches.getFirst().getContext().getTalker().getEntity() == actor.getEntity(),
                    "command_sets_talker_before_speech_event");

            Recipient near = recipient(level, players, "SpeechNear", 81);
            Recipient middle = recipient(level, players, "SpeechMiddle", 85);
            Recipient far = recipient(level, players, "SpeechFar", 89);
            check(players.stream().allMatch(player -> level.getEntity(player.getUUID()) == player)
                    && level.getEntity(actor.getEntity().getUUID()) == actor.getEntity(), "speech_fixture_entities_are_queryable");

            reset(players, recorder);
            speak(server, source, "hello %player%");
            check(near.messages.equals(List.of("[SpeechAudit]: hello SpeechNear"))
                    && middle.messages.isEmpty() && far.messages.isEmpty(), "broadcast_uses_npc_and_recipient_placeholders_with_chat_range");

            reset(players, recorder);
            speak(server, source, "private %player% --target SpeechFar");
            check(far.messages.equals(List.of("SpeechAudit: private SpeechFar"))
                    && near.messages.isEmpty() && middle.messages.isEmpty(), "named_target_receives_outside_chat_range_without_broadcast");

            reset(players, recorder);
            var playerSource = near.createCommandSourceStack().withPermission(4).withSuppressedOutput();
            selector.select(playerSource, actor);
            speak(server, playerSource, "recipient %player% --target SpeechFar");
            check(far.messages.equals(List.of("SpeechAudit: recipient SpeechFar")) && near.messages.isEmpty(),
                    "player_sender_does_not_replace_recipient_placeholders_early");
            selector.deselect(playerSource);

            reset(players, recorder);
            Setting.CHAT_BYSTANDERS_HEAR_TARGETED_CHAT.set(true);
            speak(server, source, "joined --target SpeechFar");
            check(far.messages.equals(List.of("SpeechAudit: joined"))
                    && near.messages.equals(List.of("[SpeechAudit] -> [SpeechFar]: joined")) && middle.messages.isEmpty(),
                    "configured_bystanders_receive_target_format_once");
            Setting.CHAT_BYSTANDERS_HEAR_TARGETED_CHAT.set(false);

            reset(players, recorder);
            speak(server, source, "to NPC --target " + target.getId());
            check(recorder.targets.size() == 1 && recorder.targets.getFirst().getTalkable().getEntity() == target.getEntity()
                    && silent(players), "numeric_target_routes_to_npc_speech_event");

            reset(players, recorder);
            speak(server, source, "around %player% --range 6");
            check(near.messages.equals(List.of("SpeechAudit: around SpeechNear"))
                    && middle.messages.equals(List.of("SpeechAudit: around SpeechMiddle")) && far.messages.isEmpty(),
                    "range_flag_selects_recipients_beyond_default_chat_range");
            check(recorder.targets.stream().noneMatch(e -> registry.isNPC(e.getTalkable().getEntity())),
                    "range_flag_excludes_npc_recipients");

            reset(players, recorder);
            speak(server, source, "compatible --type chat");
            check(near.messages.equals(List.of("[SpeechAudit]: compatible")), "retired_type_flag_remains_accepted_as_upstream");

            reset(players, recorder);
            recorder.cancelSpeech = true;
            speak(server, source, "canceled --range 6");
            check(silent(players) && recorder.targets.isEmpty() && recorder.bystanders.isEmpty(),
                    "speech_event_cancellation_prevents_delivery");

            reset(players, recorder);
            recorder.replacement = "edited %player%";
            speak(server, source, "original");
            check(near.messages.equals(List.of("[SpeechAudit]: edited SpeechNear")), "speech_event_can_replace_message");

            reset(players, recorder);
            recorder.cancelTarget = near;
            speak(server, source, "filtered --range 6");
            check(near.messages.isEmpty() && middle.messages.equals(List.of("SpeechAudit: filtered")),
                    "target_event_cancellation_is_per_recipient");

            reset(players, recorder);
            recorder.targetReplacement = "custom <npc> %player%";
            speak(server, source, "original --target SpeechFar");
            check(far.messages.equals(List.of("custom SpeechAudit SpeechFar")), "target_event_message_keeps_npc_context");

            reset(players, recorder);
            recorder.cancelBystander = near;
            speak(server, source, "hidden");
            check(silent(players) && !recorder.bystanders.isEmpty(), "bystander_event_cancellation_prevents_delivery");

            reset(players, recorder);
            var hologram = actor.getOrAddTrait(HologramTrait.class);
            hologram.addLine("Persistent");
            speak(server, source, "Bubble %player% --target SpeechFar --bubble 4t");
            check(hologram.getLines().equals(List.of("Persistent", "Bubble SpeechFar"))
                    && recorder.speeches.isEmpty() && silent(players), "bubble_flag_uses_target_placeholders_without_chat");
            var saved = new MemoryDataKey();
            hologram.save(saved);
            check(saved.getString("lines.0.text").equals("Persistent") && !saved.keyExists("lines.1"),
                    "speech_bubble_is_not_persisted");
            for (int i = 0; i < 3; i++) hologram.run();
            check(hologram.getLines().size() == 2, "speech_bubble_survives_until_requested_tick");
            hologram.run();
            check(hologram.getLines().equals(List.of("Persistent")), "speech_bubble_expires_at_requested_tick");

            reset(players, recorder);
            actor.despawn();
            speak(server, source, "unspawned --range 6");
            check(recorder.speeches.isEmpty() && silent(players), "unspawned_speaker_does_not_query_a_missing_entity");
            LoggerFactory.getLogger("citizens").info("[SPEECHAUDIT] COMPLETE {}/21", passed);
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[SPEECHAUDIT] FAILED", failure);
        } finally {
            if (recorder != null) NeoForge.EVENT_BUS.unregister(recorder);
            for (Recipient player : players) {
                try {
                    if (server.getPlayerList().getPlayer(player.getUUID()) == player) server.getPlayerList().remove(player);
                    else level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
                } catch (Throwable failure) {
                    LoggerFactory.getLogger("citizens").error("[SPEECHAUDIT] FAILED recipient cleanup", failure);
                } finally {
                    if (player.channel != null) player.channel.finishAndReleaseAll();
                }
            }
            for (NPC npc : created) npc.destroy();
            if (previousSelection == null) selector.deselect(source);
            else selector.select(source, previousSelection);
            settings.forEach(Setting::set);
        }
    }

    private static Recipient recipient(ServerLevel level, List<Recipient> players, String name, double x) {
        var player = new Recipient(level, name);
        players.add(player);
        player.setPos(x, -40, 40);
        // Use the normal online-player maps and lifecycle; an embedded transport keeps packets inside the fixture.
        var connection = new Connection(PacketFlow.SERVERBOUND);
        player.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                Connection.configureInMemoryPipeline(channel.pipeline(), PacketFlow.SERVERBOUND);
                connection.configurePacketHandler(channel.pipeline());
            }
        });
        NetworkRegistry.configureMockConnection(connection);
        var cookie = new CommonListenerCookie(player.getGameProfile(), 0, ClientInformation.createDefault(), false,
                ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(level.registryAccess(), cookie.connectionType())));
        level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    private static void speak(MinecraftServer server, CommandSourceStack source, String args) throws Exception {
        if (server.getCommands().getDispatcher().execute("npc speak " + args, source) != 1)
            throw new AssertionError("Speech command failed: " + args);
    }

    private static void reset(List<Recipient> players, SpeechRecorder recorder) {
        players.forEach(player -> player.messages.clear());
        recorder.speeches.clear();
        recorder.targets.clear();
        recorder.bystanders.clear();
        recorder.cancelSpeech = false;
        recorder.cancelTarget = recorder.cancelBystander = null;
        recorder.replacement = recorder.targetReplacement = null;
    }

    private static boolean silent(List<Recipient> players) {
        return players.stream().allMatch(player -> player.messages.isEmpty());
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        passed++;
        LoggerFactory.getLogger("citizens").info("[SPEECHAUDIT] PASS {}", name);
    }

    private static final class Recipient extends ServerPlayer {
        final List<String> messages = new ArrayList<>();
        EmbeddedChannel channel;
        Recipient(ServerLevel level, String name) {
            super(level.getServer(), level, new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault());
        }
        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
    }

    public static final class SpeechRecorder {
        final NPC actor;
        final List<NPCSpeechEvent> speeches = new ArrayList<>();
        final List<SpeechTargetedEvent> targets = new ArrayList<>();
        final List<SpeechBystanderEvent> bystanders = new ArrayList<>();
        boolean cancelSpeech;
        Entity cancelTarget, cancelBystander;
        String replacement, targetReplacement;
        SpeechRecorder(NPC actor) { this.actor = actor; }
        @SubscribeEvent public void speech(NPCSpeechEvent event) {
            if (event.getNPC() != actor) return;
            speeches.add(event);
            if (replacement != null) event.getContext().setMessage(replacement);
            event.setCanceled(cancelSpeech);
        }
        @SubscribeEvent public void target(SpeechTargetedEvent event) {
            if (speeches.stream().noneMatch(e -> e.getContext() == event.getContext())) return;
            targets.add(event);
            if (targetReplacement != null) event.setMessage(targetReplacement);
            if (event.getTalkable().getEntity() == cancelTarget) event.setCanceled(true);
        }
        @SubscribeEvent public void bystander(SpeechBystanderEvent event) {
            if (speeches.stream().noneMatch(e -> e.getContext() == event.getContext())) return;
            bystanders.add(event);
            if (event.getTalkable().getEntity() == cancelBystander) event.setCanceled(true);
        }
    }
}
