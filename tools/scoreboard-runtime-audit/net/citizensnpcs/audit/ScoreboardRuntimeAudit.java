package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCSpawnEvent;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.trait.trait.PlayerFilter;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.npc.entity.EntityHumanNPC;
import net.citizensnpcs.trait.PacketNPC;
import net.citizensnpcs.trait.ScoreboardTrait;
import net.citizensnpcs.util.NPCVisibility;
import net.citizensnpcs.util.Util;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

/** Real player admission/tracking with native client team semantics replayed from actual outgoing packets. */
@EventBusSubscriber(modid = "citizens")
public final class ScoreboardRuntimeAudit {
    private static boolean forced, done;
    private static int phase, nextTick, passed, deadline;
    private static NPCRegistry registry;
    private static Actor alice, bob, far;
    private static final List<Actor> actors = new ArrayList<>();
    private static final List<NPC> npcs = new ArrayList<>();
    private static final List<ScoreboardTrait> retiredTraits = new ArrayList<>();
    private static final List<String> errors = new ArrayList<>();
    private static final Set<NPC> canceled = new HashSet<>();
    private static NPC changing;
    private static String oldEntry;
    private static Actor retired;
    private static PlayerTeam operatorTeam;

    @SubscribeEvent public static void spawn(NPCSpawnEvent event) {
        if (canceled.contains(event.getNPC())) event.setCanceled(true);
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        MinecraftServer server = event.getServer(); ServerLevel level = server.overworld();
        if (!forced) { level.setChunkForced(0, 0, true); forced = true; deadline = server.getTickCount() + 1500; }
        try {
            for (Actor actor : actors) actor.pump();
            if (server.getTickCount() > deadline) throw new AssertionError("timed_out_phase_" + phase);
            if (!level.areEntitiesLoaded(0L) || !level.isPositionEntityTicking(new BlockPos(1, -60, 1))) return;
            if (server.getTickCount() < nextTick) return;
            if (phase == 1 && (!alice.watches(level, 0, 0) || !bob.watches(level, 0, 0))) return;
            nextTick = server.getTickCount() + 20;
            switch (phase++) {
                case 0 -> {
                    check(Files.isRegularFile(Path.of("scoreboard-audit-fixture.txt")) && !CitizensAPI.getNPCRegistry().iterator().hasNext(), "isolated_empty_fixture");
                    operatorTeam = server.getScoreboard().addPlayerTeam("operator-owned");
                    server.getScoreboard().addPlayerToTeam("Operator", operatorTeam); operatorTeam.setColor(ChatFormatting.GOLD);
                    alice = actor(server, UUID.randomUUID(), "TeamAlice", 6);
                    bob = actor(server, UUID.randomUUID(), "TeamBob", 8);
                    far = actor(server, UUID.randomUUID(), "TeamFar", 180);
                    registry = CitizensAPI.createNamedNPCRegistry("scoreboard-audit", new MemoryNPCDataStore());
                }
                case 1 -> {
                    for (boolean packet : List.of(false, true)) for (EntityType<?> type : List.of(EntityType.COW, EntityType.PLAYER)) {
                        NPC npc = registry.createNPC(type, (packet ? "Packet" : "World") + (type == EntityType.PLAYER ? "Player" : "Cow"));
                        npcs.add(npc); npc.data().set(NPC.Metadata.TRACKING_RANGE, 64);
                        npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, false);
                        npc.data().set(NPC.Metadata.COLLIDABLE, false);
                        npc.getOrAddTrait(ScoreboardTrait.class).setColor(ChatFormatting.RED);
                        npc.getOrAddTrait(PlayerFilter.class).addPlayer(bob.player.getUUID());
                        if (packet) npc.getOrAddTrait(PacketNPC.class);
                        check(npc.spawn(new Location(level, 4, -60, 4)), "spawn_" + npc.getName());
                    }
                }
                case 2 -> {
                    for (NPC npc : npcs) {
                        check(alice.spawns(npc) == 1 && bob.spawns(npc) == 0 && far.spawns(npc) == 0, "native_viewer_eligibility_" + npc.getName());
                        for (Actor actor : actors) {
                            state(actor, npc, ChatFormatting.RED, Team.Visibility.NEVER, Team.CollisionRule.NEVER);
                            check(actor.teamPackets(npc) == 1, "single_initial_add_" + actor.name() + npc.getName());
                        }
                        check(alice.teamBeforeSpawn(npc), "team_before_profile_and_entity_" + npc.getName());
                    }
                    check(errors.isEmpty(), "native_client_replay_initial_" + errors); clear();
                }
                case 3 -> {
                    for (NPC npc : npcs) for (Actor actor : actors) check(actor.teamPackets(npc) == 0, "unchanged_suppressed_" + actor.name() + npc.getName());
                    for (NPC npc : npcs) {
                        npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, "hover"); npc.data().set(NPC.Metadata.COLLIDABLE, true);
                        npc.getTrait(ScoreboardTrait.class).setColor(ChatFormatting.BLUE);
                    }
                    // Preparing one viewer early must not consume the property revision for other recipients.
                    npcs.getFirst().getTrait(ScoreboardTrait.class).prepareForViewer(alice.player);
                }
                case 4 -> {
                    for (NPC npc : npcs) for (Actor actor : actors) {
                        state(actor, npc, ChatFormatting.BLUE, Team.Visibility.ALWAYS, Team.CollisionRule.ALWAYS);
                        check(actor.teamPackets(npc) == 1, "one_properties_update_" + actor.name() + npc.getName());
                    }
                    for (NPC npc : npcs) npc.getTrait(ScoreboardTrait.class).setColor(null);
                    clear();
                }
                case 5 -> {
                    for (NPC npc : npcs) for (Actor actor : actors) state(actor, npc, ChatFormatting.RESET, Team.Visibility.ALWAYS, Team.CollisionRule.ALWAYS);
                    changing = npcs.get(1); oldEntry = entry(changing);
                    ((EntityHumanNPC) changing.getEntity()).setProfileOverride(new GameProfile(changing.getUniqueId(), "LiveEntry"));
                    clear();
                }
                case 6 -> {
                    for (Actor actor : actors) {
                        check(actor.board.getPlayersTeam(oldEntry) == null && actor.team(changing).getPlayers().equals(Set.of("LiveEntry")), "live_membership_replaced_" + actor.name());
                        check(actor.teamPackets(changing) == 2, "membership_remove_add_" + actor.name());
                    }
                    ((EntityHumanNPC) changing.getEntity()).setProfileOverride(null);
                    changing.setName("RenamedPlayer");
                    clear();
                }
                case 7 -> {
                    for (Actor actor : actors) {
                        check(actor.team(changing).getPlayers().equals(Set.of(entry(changing))), "respawn_rename_exact_membership_" + actor.name());
                        check(actor.board.getPlayersTeam(oldEntry) == null && actor.board.getPlayersTeam("LiveEntry") == null, "no_retired_names_" + actor.name());
                    }
                    for (NPC npc : npcs) {
                        ScoreboardTrait trait = npc.getTrait(ScoreboardTrait.class);
                        check(privateBoard(trait).getPlayerTeams().size() == 1 && members(trait).size() == 1
                                && privateBoard(trait).getPlayersTeam(entry(npc)).getPlayers().equals(Set.of(entry(npc))), "one_private_team_and_member_" + npc.getName());
                        trait.setColor(ChatFormatting.RESET);
                        boolean rejected = false;
                        try { trait.setColor(ChatFormatting.BOLD); } catch (IllegalArgumentException expected) { rejected = true; }
                        check(rejected && trait.getColor() == ChatFormatting.RESET, "styles_rejected_reset_allowed_" + npc.getName());
                        var data = new MemoryDataKey(); data.setString("color", "ITALIC");
                        PersistenceLoader.load(trait, data); trait.load(data);
                        check(trait.getColor() == null, "persisted_style_sanitized_" + npc.getName());
                    }
                    var source = server.createCommandSourceStack().withPermission(4);
                    CitizensAPI.getDefaultNPCSelector().select(source, changing);
                    check(server.getCommands().getDispatcher().execute("npc glowing --color reset", source) > 0
                            && changing.getTrait(ScoreboardTrait.class).getColor() == ChatFormatting.RESET, "command_accepts_reset");
                    boolean rejected = false;
                    try { server.getCommands().getDispatcher().execute("npc glowing --color bold", source); }
                    catch (CommandSyntaxException expected) { rejected = true; }
                    check(rejected, "command_rejects_formatting");
                    Setting.USE_SCOREBOARD_TEAMS.set(false); clear();
                }
                case 8 -> {
                    for (NPC npc : npcs) {
                        empty(npc.getTrait(ScoreboardTrait.class), npc);
                        for (Actor actor : actors) check(actor.team(npc) == null && actor.teamPackets(npc) == 1, "disabled_removes_once_" + actor.name() + npc.getName());
                    }
                    clear();
                }
                case 9 -> {
                    for (NPC npc : npcs) for (Actor actor : actors) check(actor.teamPackets(npc) == 0, "disabled_no_repeated_remove_" + actor.name() + npc.getName());
                    Setting.USE_SCOREBOARD_TEAMS.set(true); clear();
                }
                case 10 -> {
                    for (NPC npc : npcs) for (Actor actor : actors) {
                        state(actor, npc, ChatFormatting.RESET, Team.Visibility.ALWAYS, Team.CollisionRule.ALWAYS);
                        check(actor.teamPackets(npc) == 1, "reenable_fresh_add_" + actor.name() + npc.getName());
                    }
                    retired = alice;
                    UUID id = alice.player.getUUID();
                    server.getPlayerList().remove(alice.player); actors.remove(alice);
                    alice = actor(server, id, "TeamAlice", 6);
                    check(alice.player.connection != retired.player.connection, "same_uuid_new_play_listener");
                }
                case 11 -> {
                    for (NPC npc : npcs) {
                        state(alice, npc, ChatFormatting.RESET, Team.Visibility.ALWAYS, Team.CollisionRule.ALWAYS);
                        check(alice.teamPackets(npc) == 1, "reconnect_receives_add_" + npc.getName());
                        check(!sessions(npc.getTrait(ScoreboardTrait.class)).containsKey(retired.player.connection), "retired_session_pruned_" + npc.getName());
                    }
                    retired.channel.finishAndReleaseAll(); retired = null;
                    clear();
                    ServerPlayer before = alice.player;
                    before.setHealth(0);
                    before.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                    alice.player = before.connection.getPlayer();
                    check(alice.player != before && alice.player.connection == before.connection, "native_respawn_reuses_listener");
                    alice.player.setPos(6, -60, 4);
                }
                case 12 -> {
                    for (NPC npc : npcs) {
                        state(alice, npc, ChatFormatting.RESET, Team.Visibility.ALWAYS, Team.CollisionRule.ALWAYS);
                        check(alice.teamPackets(npc) == 0, "respawn_keeps_client_team_" + npc.getName());
                    }
                    clear(); alice.player.teleportTo(server.getLevel(Level.NETHER), 6, 100, 4, 0, 0);
                }
                case 13 -> {
                    check(alice.player.level().dimension() == Level.NETHER, "native_dimension_transfer");
                    for (NPC npc : npcs) {
                        check(!NPCVisibility.isTracked(npc.getEntity(), alice.player) && alice.teamPackets(npc) == 0, "dimension_keeps_global_team_" + npc.getName());
                        npc.getTrait(ScoreboardTrait.class).setColor(ChatFormatting.GREEN);
                    }
                    clear();
                }
                case 14 -> {
                    for (NPC npc : npcs) for (Actor actor : actors) state(actor, npc, ChatFormatting.GREEN, Team.Visibility.ALWAYS, Team.CollisionRule.ALWAYS);
                    clear(); alice.player.teleportTo(level, 6, -60, 4, 0, 0);
                    for (NPC npc : npcs) npc.removeTrait(PlayerFilter.class);
                }
                case 15 -> {
                    for (NPC npc : npcs) {
                        check(bob.spawns(npc) == 1 && NPCVisibility.isTracked(npc.getEntity(), bob.player), "filter_removal_pairs_existing_team_" + npc.getName());
                        for (Actor actor : actors) check(actor.teamPackets(npc) == 0, "reentry_no_duplicate_add_" + actor.name() + npc.getName());
                        retiredTraits.add(npc.getTrait(ScoreboardTrait.class));
                        npc.addTrait(new ScoreboardTrait());
                    }
                    clear();
                }
                case 16 -> {
                    for (int i = 0; i < npcs.size(); i++) {
                        NPC npc = npcs.get(i);
                        check(privateBoard(retiredTraits.get(i)).getPlayerTeams().isEmpty() && sessions(retiredTraits.get(i)).isEmpty(), "replaced_trait_disposed_" + i);
                        for (Actor actor : actors) state(actor, npc, ChatFormatting.RESET, Team.Visibility.ALWAYS, Team.CollisionRule.ALWAYS);
                        npc.despawn(); empty(npc.getTrait(ScoreboardTrait.class), npc);
                        check(npc.spawn(new Location(level, 4, -60, 4)), "respawn_same_trait_" + i);
                    }
                    clear();
                }
                case 17 -> {
                    for (NPC npc : npcs) for (Actor actor : actors) check(actor.team(npc).getPlayers().equals(Set.of(entry(npc))), "respawn_membership_exact_" + actor.name() + npc.getName());
                    for (boolean packet : List.of(false, true)) {
                        NPC npc = registry.createNPC(EntityType.COW, "Canceled" + packet);
                        npc.getOrAddTrait(ScoreboardTrait.class);
                        if (packet) npc.getOrAddTrait(PacketNPC.class);
                        canceled.add(npc);
                        npc.spawn(new Location(level, 4, -60, 4));
                        check(!npc.isSpawned(), "canceled_spawn_absent_" + packet);
                        empty(npc.getTrait(ScoreboardTrait.class), npc);
                        for (Actor actor : actors) { actor.pump(); check(actor.team(npc) == null, "canceled_team_removed_" + actor.name() + packet); }
                        canceled.remove(npc);
                        check(npc.spawn(new Location(level, 4, -60, 4)), "canceled_spawn_retry_" + packet);
                        npcs.add(npc);
                    }
                }
                case 18 -> {
                    for (NPC npc : npcs) {
                        ScoreboardTrait trait = npc.getTrait(ScoreboardTrait.class);
                        npc.destroy(); empty(trait, npc);
                        for (Actor actor : actors) { actor.pump(); check(actor.team(npc) == null, "destroy_removes_client_team_" + actor.name() + npc.getName()); }
                    }
                    check(Set.copyOf(server.getScoreboard().getPlayerTeams()).equals(Set.of(operatorTeam)), "server_scoreboard_not_polluted");
                    for (Actor actor : actors) check(actor.board.getPlayerTeam("operator-owned").getPlayers().equals(Set.of("Operator"))
                            && actor.board.getPlayerTeam("operator-owned").getColor() == ChatFormatting.GOLD, "operator_team_untouched_" + actor.name());
                    check(errors.isEmpty(), "all_native_replay_order_checks_" + errors);
                    LoggerFactory.getLogger("citizens").info("[SCOREBOARDAUDIT] COMPLETE {} checks", passed); done = true;
                }
            }
        } catch (Throwable failure) { done = true; LoggerFactory.getLogger("citizens").error("[SCOREBOARDAUDIT] FAILED phase " + phase, failure); }
        if (done) {
            try {
                Setting.USE_SCOREBOARD_TEAMS.set(true);
                for (NPC npc : npcs) if (registry.getByUniqueId(npc.getUniqueId()) != null) npc.destroy();
                for (Actor actor : actors) { server.getPlayerList().remove(actor.player); actor.channel.finishAndReleaseAll(); }
                if (retired != null) retired.channel.finishAndReleaseAll();
                server.getScoreboard().removePlayerTeam(operatorTeam);
            } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[SCOREBOARDAUDIT] FAILED cleanup", failure); }
            server.halt(false);
        }
    }

    private static String teamName(NPC npc) { return Util.getTeamName(npc.getUniqueId()); }
    private static String entry(NPC npc) { return npc.getEntity() instanceof ServerPlayer player ? player.getGameProfile().getName() : npc.getUniqueId().toString(); }
    private static Scoreboard privateBoard(ScoreboardTrait trait) throws Exception {
        var field = ScoreboardTrait.class.getDeclaredField("scoreboard"); field.setAccessible(true); return (Scoreboard) field.get(trait);
    }
    private static Map<?, ?> sessions(ScoreboardTrait trait) throws Exception {
        var field = ScoreboardTrait.class.getDeclaredField("sentTo"); field.setAccessible(true); return (Map<?, ?>) field.get(trait);
    }
    private static Map<?, ?> members(ScoreboardTrait trait) throws Exception {
        var field = Scoreboard.class.getDeclaredField("teamsByPlayer"); field.setAccessible(true); return (Map<?, ?>) field.get(privateBoard(trait));
    }
    private static void empty(ScoreboardTrait trait, NPC npc) throws Exception {
        check(privateBoard(trait).getPlayerTeams().isEmpty() && members(trait).isEmpty(), "private_scoreboard_disposed_" + npc.getName());
        check(sessions(trait).isEmpty() && !npc.data().has(NPC.Metadata.SCOREBOARD_FAKE_TEAM_NAME), "sessions_metadata_disposed_" + npc.getName());
    }
    private static void state(Actor actor, NPC npc, ChatFormatting color, Team.Visibility visibility, Team.CollisionRule collision) {
        actor.pump(); PlayerTeam team = actor.team(npc);
        check(team != null && team.getPlayers().equals(Set.of(entry(npc))) && team.getColor() == color
                && team.getNameTagVisibility() == visibility && team.getCollisionRule() == collision, "client_team_state_" + actor.name() + npc.getName() + color);
    }
    private static void clear() { for (Actor actor : actors) { actor.pump(); actor.packets.clear(); } }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); passed++; LoggerFactory.getLogger("citizens").info("[SCOREBOARDAUDIT] PASS {}", label); }
    private static Actor actor(MinecraftServer server, UUID id, String name, double x) {
        var defaults = ClientInformation.createDefault();
        var information = new ClientInformation(defaults.language(), 6, defaults.chatVisibility(), defaults.chatColors(),
                defaults.modelCustomisation(), defaults.mainHand(), defaults.textFilteringEnabled(), defaults.allowsListing());
        Actor actor = new Actor(); actor.player = new ServerPlayer(server, server.overworld(), new GameProfile(id, name), information);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        actor.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                connection.configurePacketHandler(channel.pipeline());
                channel.pipeline().addLast("scoreboard-capture", new ChannelOutboundHandlerAdapter() {
                    @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                        if (message instanceof Packet<?> packet) actor.capture(packet);
                        super.write(context, message, promise);
                    }
                });
            }
        });
        NetworkRegistry.configureMockConnection(connection);
        var cookie = new CommonListenerCookie(actor.player.getGameProfile(), 0, information, false, ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
        server.getPlayerList().placeNewPlayer(connection, actor.player, cookie);
        actor.player.setPos(x, -60, 4); actors.add(actor); return actor;
    }

    private static final class Actor {
        ServerPlayer player; EmbeddedChannel channel; int pendingBatches;
        final List<Packet<?>> packets = new ArrayList<>();
        final Scoreboard board = new Scoreboard();
        String name() { return player.getGameProfile().getName(); }
        PlayerTeam team(NPC npc) { return board.getPlayerTeam(teamName(npc)); }
        void capture(Packet<?> packet) {
            if (packet instanceof ClientboundBundlePacket bundle) { bundle.subPackets().forEach(this::capture); return; }
            packets.add(packet);
            if (packet instanceof ClientboundSetPlayerTeamPacket update) replay(update);
            if (packet instanceof ClientboundChunkBatchFinishedPacket) pendingBatches++;
        }
        // Same native objects and action ordering as ClientPacketListener.handleSetPlayerTeamPacket.
        // Unlike vanilla's warning-only behavior, duplicate ADDs and unknown updates fail this fixture.
        void replay(ClientboundSetPlayerTeamPacket packet) {
            var action = packet.getTeamAction();
            PlayerTeam team = board.getPlayerTeam(packet.getName());
            if (action == ClientboundSetPlayerTeamPacket.Action.ADD) {
                if (team != null) errors.add("duplicate_add_" + name() + packet.getName());
                team = board.addPlayerTeam(packet.getName());
            } else if (team == null) { errors.add("unknown_team_" + name() + packet.getName()); return; }
            PlayerTeam target = team;
            packet.getParameters().ifPresent(p -> {
                target.setDisplayName(p.getDisplayName()); target.setColor(p.getColor()); target.unpackOptions(p.getOptions());
                var visibility = Team.Visibility.byName(p.getNametagVisibility());
                if (visibility != null) target.setNameTagVisibility(visibility);
                var collision = Team.CollisionRule.byName(p.getCollisionRule());
                if (collision != null) target.setCollisionRule(collision);
                target.setPlayerPrefix(p.getPlayerPrefix()); target.setPlayerSuffix(p.getPlayerSuffix());
            });
            if (packet.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.ADD)
                for (String entry : packet.getPlayers()) board.addPlayerToTeam(entry, team);
            else if (packet.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE)
                for (String entry : packet.getPlayers()) board.removePlayerFromTeam(entry, team);
            if (action == ClientboundSetPlayerTeamPacket.Action.REMOVE) board.removePlayerTeam(team);
        }
        void pump() {
            channel.runPendingTasks();
            while (pendingBatches > 0) { pendingBatches--; player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(16)); }
            Object output; while ((output = channel.readOutbound()) != null) ReferenceCountUtil.release(output);
        }
        boolean watches(ServerLevel level, int x, int z) throws ReflectiveOperationException {
            var method = net.minecraft.server.level.ChunkMap.class.getDeclaredMethod("isChunkTracked", ServerPlayer.class, int.class, int.class);
            method.setAccessible(true);
            return (boolean) method.invoke(level.getChunkSource().chunkMap, player, x, z);
        }
        long spawns(NPC npc) { pump(); return packets.stream().filter(p -> p instanceof ClientboundAddEntityPacket add && add.getId() == npc.getEntity().getId()).count(); }
        long teamPackets(NPC npc) { pump(); return packets.stream().filter(p -> p instanceof ClientboundSetPlayerTeamPacket team && team.getName().equals(teamName(npc))).count(); }
        boolean teamBeforeSpawn(NPC npc) {
            pump(); boolean team = false;
            for (Packet<?> packet : packets) {
                if (packet instanceof ClientboundSetPlayerTeamPacket update && update.getName().equals(teamName(npc))
                        && update.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.ADD) team = true;
                if (packet instanceof ClientboundPlayerInfoUpdatePacket info && info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)
                        && info.entries().stream().anyMatch(e -> e.profileId().equals(npc.getUniqueId())) && !team) return false;
                if (packet instanceof ClientboundAddEntityPacket add && add.getId() == npc.getEntity().getId()) return team;
            }
            return false;
        }
    }
}
