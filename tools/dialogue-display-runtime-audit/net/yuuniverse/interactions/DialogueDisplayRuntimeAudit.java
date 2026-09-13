package net.yuuniverse.interactions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.util.EntityPacketTracker;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "interactions")
public final class DialogueDisplayRuntimeAudit {
    private static boolean forced, finished;
    private static int passed;

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (finished) return;
        var server = event.getServer(); var level = server.overworld();
        if (!forced) { level.setChunkForced(0, 0, true); forced = true; }
        if (!level.areEntitiesLoaded(ChunkPos.asLong(0, 0)) || !level.isPositionEntityTicking(new BlockPos(1, 0, 1))) {
            if (server.getTickCount() > 1200) {
                finished = true; LoggerFactory.getLogger("interactions").error("[DIALOGUEDISPLAYAUDIT] FAILED fixture loading"); server.halt(false);
            }
            return;
        }
        finished = true;
        State state = new State(server);
        try {
            state.run();
            LoggerFactory.getLogger("interactions").info("[DIALOGUEDISPLAYAUDIT] COMPLETE {} checks", passed);
        } catch (Throwable failure) {
            LoggerFactory.getLogger("interactions").error("[DIALOGUEDISPLAYAUDIT] FAILED", failure);
        } finally {
            try { state.close(); } catch (Throwable failure) { LoggerFactory.getLogger("interactions").error("[DIALOGUEDISPLAYAUDIT] FAILED cleanup", failure); }
            server.halt(false);
        }
    }

    private static final class State {
        final MinecraftServer server;
        final ServerLevel level;
        final List<AuditPlayer> players = new ArrayList<>();
        final List<Session> sessions = new ArrayList<>();
        AuditPlayer alice, bob;
        Entity npc;
        State(MinecraftServer server) { this.server = server; level = server.overworld(); }

        void run() throws Exception {
            if (!Files.exists(Path.of("dialogue-display-audit-fixture.txt")) || CitizensAPI.getNPCRegistry().iterator().hasNext())
                throw new AssertionError("Display audit requires its own empty fixture");
            alice = player("DisplayAlice"); bob = player("DisplayBob");
            npc = EntityType.VILLAGER.create(level); npc.setPos(3, -60, 1);
            var engine = new EngineState();
            Conversation story = story();
            var a = session(engine, story, alice);
            var b = session(new EngineState(), story, bob);
            alice.clear(); bob.clear();
            a.tick(); alice.pump(); bob.pump();
            ServerBossEvent bar = bar(a); UUID barId = bar.getId();
            check(bar.getPlayers().equals(Set.of(alice)), "bossbar_links_only_session_owner");
            check(bar.getName().getString().equals("Talk: Speaker") && bar.getColor() == BossEvent.BossBarColor.BLUE
                    && bar.getOverlay() == BossEvent.BossBarOverlay.NOTCHED_10 && bar.getProgress() == 1,
                    "legacy_bossbar_style_title_and_constant_progress");
            check(alice.bars.containsKey(barId) && !bob.bars.containsKey(barId), "bossbar_packet_is_private");
            check(alice.chat.stream().map(Component::getString).anyMatch(text -> text.contains("Hello DisplayAlice")),
                    "hologram_does_not_replace_chat_delivery");
            List<ArmorStand> first = holograms(a);
            check(first.size() == 3, "empty_hologram_rows_reserve_space_without_an_entity");
            check(first.get(0).getCustomName().getString().equals("Hello DisplayAlice")
                    && first.get(1).getCustomName().getString().equals("Line ")
                    && first.get(2).getCustomName().getString().equals("Tail"), "hologram_placeholders_and_control_markers");
            check(first.stream().allMatch(entity -> entity.isMarker() && entity.isInvisible() && entity.isNoGravity()
                    && entity.isCustomNameVisible() && level.getEntity(entity.getId()) == null), "holograms_are_virtual_marker_nameplates");
            double top = -60 + 2.7 + 3 * 0.2;
            for (int i = 0; i < first.size(); i++) {
                int row = i == 2 ? 3 : i;
                var stand = first.get(i);
                double shownY = stand.getY() + stand.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, stand.getYRot()).y + 0.5;
                check(Math.abs(shownY - (top - row * 0.2)) < 1E-6, "hologram_nameplate_anchor_row_" + row);
            }
            Set<Integer> firstIds = ids(first);
            check(alice.spawnIds().containsAll(firstIds) && bob.spawnIds().stream().noneMatch(firstIds::contains), "hologram_spawn_packets_are_private");
            AuditPlayer late = player("DisplayLate"); late.pump();
            check(late.spawnIds().stream().noneMatch(firstIds::contains), "late_joiner_does_not_receive_private_holograms");

            b.tick(); bob.pump();
            check(!bar(b).getId().equals(barId) && !ids(holograms(b)).equals(firstIds), "simultaneous_sessions_own_distinct_display_ids");
            check(holograms(b).getFirst().getCustomName().getString().equals("Hello DisplayBob"), "simultaneous_text_uses_each_player_context");
            a.skipDialogue(false); a.tick(); a.tick();
            check(a.isAwaitingChoice() && bar.getName().getString().equals("Choose: Speaker") && bar.getProgress() == 1,
                    "options_switch_bossbar_title_and_fill_progress");
            alice.clear(); a.cycleSelection(1, System.currentTimeMillis()); alice.pump();
            check(ids(holograms(a)).equals(firstIds) && alice.spawnIds().isEmpty(), "option_redraw_keeps_existing_hologram_entities");
            check(a.choose(1), "dialogue_choice_accepted");
            a.tick(); alice.pump();
            check(bar.getName().getString().equals("Talk: Speaker") && holograms(a).size() == 1,
                    "next_node_updates_both_displays");
            check(alice.removedIds().containsAll(firstIds) && first.stream().allMatch(Entity::isRemoved), "replaced_holograms_are_removed");
            Set<Integer> finalIds = ids(holograms(a));
            a.end(true); alice.pump();
            check(!alice.bars.containsKey(barId) && alice.removedIds().containsAll(finalIds) && holograms(a).isEmpty(), "normal_end_clears_bossbar_and_hologram");
            check(!b.isFinished() && !holograms(b).isEmpty() && bar(b) != null, "ending_one_session_preserves_another");
            a.end(false); check(holograms(a).isEmpty(), "display_cleanup_is_idempotent");

            timed();
            offsetsAndFailures();
            controllerCleanup();
            bob.setPos(30, -60, 1); b.tick(); bob.pump();
            check(b.isFinished() && holograms(b).isEmpty() && bar(b) == null, "range_exit_clears_displays");
            respawn();
        }

        void timed() throws Exception {
            var engine = new EngineState();
            engine.settings = settings(new BossBarSettings(true, BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS, true));
            Conversation story = story(); story.first().lines.getFirst().time = 4;
            Session timed = session(engine, story, alice); timed.tick();
            check(bar(timed).getProgress() == 0 && bar(timed).getColor() == BossEvent.BossBarColor.RED
                    && bar(timed).getOverlay() == BossEvent.BossBarOverlay.PROGRESS, "timed_bossbar_starts_empty_with_configured_style");
            for (int i = 0; i < 19; i++) timed.tick();
            check(bar(timed).getProgress() == 0, "timed_bossbar_waits_for_second_boundary");
            timed.tick(); check(bar(timed).getProgress() == 0.25F, "timed_bossbar_advances_at_one_second");
            for (int i = 0; i < 20; i++) timed.tick();
            check(bar(timed).getProgress() == 0.5F, "timed_bossbar_progress_tracks_current_line");
            timed.skipDialogue(false); timed.tick(); timed.tick();
            check(timed.isAwaitingChoice() && bar(timed).getProgress() == 1, "manual_skip_enters_full_options_bar");
            engine.settings = settings(new BossBarSettings(false, BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS, true));
            timed.tick(); check(bar(timed) == null, "disabled_bossbar_is_removed");
            engine.settings = settings(new BossBarSettings(true, BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.NOTCHED_6, true));
            timed.tick(); check(bar(timed).getProgress() == 1 && bar(timed).getColor() == BossEvent.BossBarColor.GREEN,
                    "reenabled_bossbar_uses_current_session_phase");
            timed.end(false);

            Conversation manual = story(); manual.first().lines.getFirst().time = -1;
            Session waiting = session(engine, manual, alice); waiting.tick();
            for (int i = 0; i < 50; i++) waiting.tick();
            check(!waiting.isAwaitingChoice() && bar(waiting).getProgress() == 0, "indefinite_line_does_not_fabricate_timer_progress");
            waiting.end(false);
        }

        void offsetsAndFailures() throws Exception {
            Conversation shifted = story(); shifted.hologram = new HologramSettings(true, 3.5, 1.25);
            Session offset = session(new EngineState(), shifted, alice); offset.tick();
            ArmorStand line = holograms(offset).getFirst();
            check(Math.abs(line.getX() - 3) < 1E-6 && Math.abs(line.getZ() - 2.25) < 1E-6, "horizontal_offset_uses_viewer_relative_direction");
            double oldX = line.getX(); npc.setPos(4, -60, 1);
            offset.tick(); check(holograms(offset).getFirst().getX() == oldX, "hologram_anchor_stays_at_conversation_start");
            offset.end(false); npc.setPos(3, -60, 1);

            Conversation invalid = story(); invalid.first().lines.getFirst().text.add("json:{broken");
            Session failure = session(new EngineState(), invalid, alice); failure.tick(); alice.pump();
            check(failure.isFinished() && bar(failure) == null && holograms(failure).isEmpty(), "render_failure_cleans_displays");
            Conversation json = story(); json.first().lines.getFirst().text.clear();
            json.first().lines.getFirst().text.add("json:{\"text\":\"JSON display\",\"color\":\"gold\"}");
            Session rich = session(new EngineState(), json, alice); rich.tick();
            check(holograms(rich).getFirst().getCustomName().getString().equals("JSON display"), "json_hologram_uses_component_content");
            rich.end(false);
            Conversation plain = story(); plain.hologram = HologramSettings.DEFAULT;
            Session noHologram = session(new EngineState(), plain, alice); noHologram.tick();
            check(holograms(noHologram).isEmpty() && bar(noHologram) != null, "disabled_hologram_does_not_disable_bossbar");
            noHologram.end(false);
            Session noNpc = new Session(new EngineState(), story(), story().first(), alice, null); sessions.add(noNpc); noNpc.tick();
            check(holograms(noNpc).isEmpty() && bar(noNpc) != null, "missing_npc_has_no_hologram_anchor"); noNpc.end(false);
            Session dimension = session(new EngineState(), story(), alice); dimension.tick();
            alice.teleportTo(server.getLevel(Level.NETHER), 1, 64, 1, Set.of(), 0, 0); dimension.tick();
            check(dimension.isFinished() && bar(dimension) == null && holograms(dimension).isEmpty(), "dimension_change_clears_displays");
            alice.teleportTo(level, 1, -60, 1, Set.of(), 0, 0);
        }

        @SuppressWarnings("unchecked")
        void controllerCleanup() throws Exception {
            var controller = new InteractionsMod(); NeoForge.EVENT_BUS.unregister(controller);
            Map<UUID, Session> managed = (Map<UUID, Session>) field(controller, "sessions");
            Session first = session(new EngineState(), story(), alice); first.tick(); managed.put(alice.getUUID(), first);
            controller.onLogout(new PlayerEvent.PlayerLoggedOutEvent(alice));
            check(first.isFinished() && bar(first) == null && holograms(first).isEmpty() && managed.isEmpty(), "logout_controller_closes_owned_displays");
            Session reload = session(new EngineState(), story(), alice); reload.tick(); managed.put(alice.getUUID(), reload);
            controller.onRegisterCommands(new RegisterCommandsEvent(server.getCommands().getDispatcher(), Commands.CommandSelection.DEDICATED,
                    CommandBuildContext.simple(server.registryAccess(), FeatureFlags.DEFAULT_FLAGS)));
            server.getCommands().getDispatcher().execute("interactions reload", server.createCommandSourceStack().withPermission(4));
            check(reload.isFinished() && bar(reload) == null && holograms(reload).isEmpty() && managed.isEmpty(), "reload_controller_closes_owned_displays");
            Session stop = session(new EngineState(), story(), alice); stop.tick(); managed.put(alice.getUUID(), stop);
            controller.onServerStopping(new ServerStoppingEvent(server));
            check(stop.isFinished() && bar(stop) == null && holograms(stop).isEmpty() && managed.isEmpty(), "shutdown_controller_closes_owned_displays");
        }

        void respawn() throws Exception {
            Session session = session(new EngineState(), story(), alice); session.tick();
            Set<Integer> entityIds = ids(holograms(session)); UUID barId = bar(session).getId();
            alice.clear();
            ServerPlayer replacement = server.getPlayerList().respawn(alice, false, Entity.RemovalReason.KILLED);
            replacement.setPos(1, -60, 1);
            session.tick(); alice.pump();
            check(session.player() == replacement && !session.isFinished(), "respawn_rebinds_session_to_current_player");
            check(bar(session).getPlayers().equals(Set.of(replacement)) && bar(session).getId().equals(barId),
                    "respawn_relinks_the_same_private_bossbar");
            check(ids(holograms(session)).equals(entityIds) && alice.spawnIds().containsAll(entityIds),
                    "respawn_resends_existing_private_holograms");
            replacement.setPos(30, -60, 1); session.tick();
            check(session.isFinished() && bar(session) == null && holograms(session).isEmpty(),
                    "rebound_player_range_is_checked_after_respawn");
        }

        Session session(EngineState engine, Conversation story, AuditPlayer player) {
            Session result = new Session(engine, story, story.first(), player, npc); sessions.add(result); return result;
        }

        AuditPlayer player(String name) {
            var player = new AuditPlayer(server, level, name); players.add(player); player.setPos(1, -60, 1);
            var connection = new Connection(PacketFlow.SERVERBOUND);
            player.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
                @Override protected void initChannel(Channel channel) {
                    connection.configurePacketHandler(channel.pipeline());
                    channel.pipeline().addLast("display-audit-capture", new ChannelOutboundHandlerAdapter() {
                        @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                            if (message instanceof Packet<?> packet) player.capture(packet);
                            super.write(context, message, promise);
                        }
                    });
                }
            });
            NetworkRegistry.configureMockConnection(connection);
            var cookie = new CommonListenerCookie(player.getGameProfile(), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
            connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
            server.getPlayerList().placeNewPlayer(connection, player, cookie); return player;
        }

        void close() {
            for (Session session : sessions) session.end(false);
            for (AuditPlayer player : players) {
                ServerPlayer current = server.getPlayerList().getPlayer(player.getUUID());
                if (current != null) server.getPlayerList().remove(current);
                if (player.channel != null) player.channel.finishAndReleaseAll();
            }
        }
    }

    private static Conversation story() {
        Conversation story = new Conversation(); story.name = "{centered}&bSpeaker"; story.source = "display.yml";
        story.blockMovement = true; story.hologram = new HologramSettings(true, 2.7, 0);
        var node = new Conversation.Node("conversation1"); var line = new Conversation.Line(); line.time = 2;
        line.text.addAll(List.of("&aHello %player%", "{centered}Line %next%", "", "Tail")); node.lines.add(line);
        var option = new Conversation.Option(); option.text = "Next"; option.startConversation = "conversation2"; node.options.add(option);
        var second = new Conversation.Option(); second.text = "Stop"; node.options.add(second);
        var target = new Conversation.Node("conversation2"); var targetLine = new Conversation.Line(); targetLine.time = -1;
        targetLine.text.add("After choice %next%"); target.lines.add(targetLine);
        story.nodes.put(node.key, node); story.nodes.put(target.key, target); return story;
    }

    private static DialogueSettings settings(BossBarSettings bar) {
        return new DialogueSettings(false, false, false, List.of(), false, true, true, false, SelectionSettings.DEFAULT,
                ConversationStartClick.RIGHT_CLICK, bar);
    }

    private static final class EngineState implements Session.Engine {
        DialogueSettings settings = DialogueDisplayRuntimeAudit.settings(BossBarSettings.DEFAULT);
        final DialogueMessages messages = new DialogueMessages(null, null, null, null, null, null, null, null,
                "Talk: %name%", "Choose: %name%");
        final Actions actions = new Actions(new ItemLibrary(), new Economy());
        final ProgressStore progress = new ProgressStore(new java.io.File("config/display-audit-players"));
        public DialogueSettings settings() { return settings; }
        public DialogueMessages messages() { return messages; }
        public Actions actions() { return actions; }
        public ProgressStore progress() { return progress; }
    }

    private static ServerBossEvent bar(Session session) throws Exception { return (ServerBossEvent) field(field(session, "bossBar"), "bar"); }
    @SuppressWarnings("unchecked")
    private static List<ArmorStand> holograms(Session session) throws Exception {
        List<EntityPacketTracker> trackers = (List<EntityPacketTracker>) field(field(session, "hologram"), "trackers");
        List<ArmorStand> result = new ArrayList<>();
        for (EntityPacketTracker tracker : trackers) result.add((ArmorStand) field(tracker, "entity"));
        return result;
    }
    private static Set<Integer> ids(List<ArmorStand> entities) { return entities.stream().map(Entity::getId).collect(java.util.stream.Collectors.toSet()); }
    private static Object field(Object object, String name) throws Exception { var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); passed++; LoggerFactory.getLogger("interactions").info("[DIALOGUEDISPLAYAUDIT] PASS {}", name); }

    private static final class AuditPlayer extends ServerPlayer {
        EmbeddedChannel channel;
        final List<Packet<?>> packets = new ArrayList<>();
        final List<Component> chat = new ArrayList<>();
        final Map<UUID, Component> bars = new HashMap<>();
        AuditPlayer(MinecraftServer server, ServerLevel level, String name) { super(server, level, new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault()); }
        @Override public void sendSystemMessage(Component message) { chat.add(message); }
        void capture(Packet<?> packet) {
            if (packet instanceof ClientboundBundlePacket bundle) { bundle.subPackets().forEach(this::capture); return; }
            packets.add(packet);
            if (packet instanceof ClientboundBossEventPacket boss) boss.dispatch(new ClientboundBossEventPacket.Handler() {
                public void add(UUID id, Component name, float progress, BossEvent.BossBarColor color, BossEvent.BossBarOverlay style,
                        boolean darken, boolean music, boolean fog) { bars.put(id, name); }
                public void remove(UUID id) { bars.remove(id); }
                public void updateName(UUID id, Component name) { bars.put(id, name); }
            });
        }
        void pump() { channel.runPendingTasks(); }
        void clear() { pump(); packets.clear(); chat.clear(); }
        Set<Integer> spawnIds() { pump(); return packets.stream().filter(ClientboundAddEntityPacket.class::isInstance)
                .map(ClientboundAddEntityPacket.class::cast).map(ClientboundAddEntityPacket::getId).collect(java.util.stream.Collectors.toSet()); }
        Set<Integer> removedIds() { pump(); return packets.stream().filter(ClientboundRemoveEntitiesPacket.class::isInstance)
                .map(ClientboundRemoveEntitiesPacket.class::cast).flatMap(packet -> packet.getEntityIds().stream()).collect(java.util.stream.Collectors.toSet()); }
    }
}
