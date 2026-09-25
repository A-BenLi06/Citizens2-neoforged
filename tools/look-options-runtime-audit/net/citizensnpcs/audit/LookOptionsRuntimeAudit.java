package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.authlib.GameProfile;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.event.NPCLookCloseChangeTargetEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.RotationTrait;
import net.citizensnpcs.trait.PacketNPC;
import net.citizensnpcs.util.NPCVisibility;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class LookOptionsRuntimeAudit {
    private static boolean done;
    private static int passed;
    private static NPC npc;
    private static LookClose look;
    private static ServerPlayer visible, hidden;
    private static final List<NPC> helpers = new ArrayList<>();
    private static final List<ServerPlayer> players = new ArrayList<>();
    private static final List<EmbeddedChannel> channels = new ArrayList<>();
    private static final Map<ServerPlayer, List<WireRotation>> packets = new IdentityHashMap<>();
    private static final Map<ServerPlayer, Integer> pendingBatches = new IdentityHashMap<>();
    private record WireRotation(int id, boolean head, byte yaw, byte pitch) { }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done || event.getServer().getTickCount() < 10) return;
        var server = event.getServer();
        try {
            check(Files.isRegularFile(Path.of("look-options-audit-fixture.txt")), "isolated_fixture");
            visible = admit(server, "OptionVisible", 0, 4);
            hidden = admit(server, "OptionHidden", 2, 0);
            npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.COW, "LookOptions");
            look = npc.getOrAddTrait(LookClose.class); look.setRealisticLooking(false); look.setRandomLook(false);
            check(npc.spawn(new Location(server.overworld(), 0, -60, 0)), "npc_spawned");
            var source = server.createCommandSourceStack(); CitizensAPI.getDefaultNPCSelector().select(source, npc);
            check(server.getCommands().getDispatcher().execute("npc lookclose -r", source) == 1, "realistic_command_executes");
            check(look.isRealisticLooking() && !look.isRandomLook(), "r_flag_selects_realistic_looking");
            commands(server);
            filters();
            persistence();
            randomBehavior();
            npcTargets(server);
            LoggerFactory.getLogger("citizens").info("[LOOKOPTIONSAUDIT] COMPLETE {} checks", passed);
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[LOOKOPTIONSAUDIT] FAILED", failure);
        } finally {
            done = true;
            try {
                for (NPC helper : helpers) helper.destroy();
                if (npc != null) npc.destroy();
                for (var player : players) server.getPlayerList().remove(player);
                for (var channel : channels) channel.finishAndReleaseAll();
            } finally { server.halt(false); }
        }
    }


    private static void fresh() {
        look = new LookClose(); look.setEnabled(false); look.setRandomLook(false); look.setRealisticLooking(false);
        npc.addTrait(look); look.setRange(16); look.setDisableWhileNavigating(false);
    }
    private static int command(MinecraftServer server, String value) throws Exception {
        var source = server.createCommandSourceStack(); CitizensAPI.getDefaultNPCSelector().select(source, npc);
        try { return server.getCommands().getDispatcher().execute("npc " + value, source); }
        catch (com.mojang.brigadier.exceptions.CommandSyntaxException rejected) { return 0; }
    }
    private static Map<String, Object> saved() {
        var data = new MemoryDataKey(); PersistenceLoader.save(look, data); look.save(data); return data.getValuesDeep();
    }
    private static void load(MemoryDataKey data) throws Exception {
        fresh(); PersistenceLoader.load(look, data); look.load(data);
    }
    private static void commands(MinecraftServer server) throws Exception {
        fresh();
        check(command(server, "look --range 17 --randomlook true --perplayer true --headonly true --linkedbody true"
                + " --randomswitchtargets true --targetnpcs true --disablewhennavigating true --randomlookdelay 2s"
                + " --randompitchrange -10,10 --randomyawrange 45,90 --filter type=PLAYER") == 1,
                "combined_native_options_execute");
        check(!look.isEnabled() && look.getRange() == 17 && look.isRandomLook() && look.isPerPlayer()
                && look.isHeadOnly() && look.isLinkedBody() && look.isRandomlySwitchingTargets() && look.targetNPCs()
                && look.disableWhileNavigating(), "all_options_apply_without_toggling_enabled");
        check(look.getRandomLookDelay() == 40 && java.util.Arrays.equals(look.getRandomLookPitchRange(), new float[]{-10,10})
                && java.util.Arrays.equals(look.getRandomLookYawRange(), new float[]{45,90}), "duration_and_random_ranges_apply");
        check(command(server, "look --rlook false -hpd") == 1 && !look.isRandomLook() && !look.isHeadOnly()
                && !look.isPerPlayer() && !look.disableWhileNavigating(), "alias_and_existing_shortcuts_can_combine");
        check(command(server, "lookclose --headonly true -h") == 1 && look.isHeadOnly(), "explicit_option_precedes_shortcut");
        var before = saved();
        for (String input : List.of("--range -1", "--range NaN", "--range Infinity", "--randomlook perhaps",
                "--randompitchrange 10,-10", "--randompitchrange 1", "--randompitchrange 1,2,3", "--randompitchrange NaN,2",
                "--randomyawrange 0,Infinity", "--randomlookdelay 999999999999999d", "--randomlookdelay invalid",
                "--filter type=missing:entity", "--filter unknown=x", "--filter permission=", "--filter type=player,", "--bogus 1")) {
            check(command(server, "lookclose --linkedbody false " + input) == 0, "invalid_option_rejected_" + input);
            check(saved().equals(before), "invalid_option_does_not_partially_mutate_" + input);
        }
        npc.removeTrait(LookClose.class);
        check(command(server, "lookclose --range 8 --randompitchrange 2,1") == 0 && !npc.hasTrait(LookClose.class),
                "invalid_configuration_does_not_attach_trait");
        check(command(server, "lookclose") == 1 && npc.getTrait(LookClose.class).isEnabled(), "bare_command_enables_new_trait");
        check(command(server, "lookclose") == 1 && !npc.getTrait(LookClose.class).isEnabled(), "bare_command_toggles_existing_trait");
        look = npc.getTrait(LookClose.class);
        check(command(server, "look --randomlookdelay 0t") == 1 && look.getRandomLookDelay() == 1,
                "reference_delay_minimum_is_one_tick");
    }

    private static void filters() throws Exception {
        var permissions = PermissionUtil.getPermissionResolver(); var groups = PermissionUtil.getGroupResolver();
        try {
            fresh(); look.setEnabled(true);
            List<String> queries = new ArrayList<>();
            PermissionUtil.setPermissionResolver((player, permission) -> {
                queries.add(permission);
                return permission.equals("audit.first") || permission.equals("audit.second") && player == visible;
            });
            PermissionUtil.setGroupResolver((player, group) -> player == visible && group.equals("Builders"));
            look.setFilter("type=minecraft:player perm=audit.first,audit.second group=Staff,Builders"); look.run();
            check(look.getTarget() == visible, "filter_combines_native_type_all_permissions_and_any_group");
            check(queries.contains("audit.first") && queries.contains("audit.second")
                    && queries.stream().noneMatch(query -> query.contains("=") || query.contains(",")),
                    "permission_provider_receives_exact_individual_operands");
            look.setFilter("type=COW,PLAYER permission=audit.second"); look.run();
            check(look.getTarget() == visible, "type_alternatives_and_permission_alias_are_supported");
            look.setEntityFilter(entity -> entity != visible); look.run();
            check(look.getTarget() == null, "programmatic_and_persisted_filters_both_apply");
            look.setEntityFilter(null); look.setFilter("group=Builders"); look.run();
            check(look.getTarget() == visible, "case_preserved_group_reaches_service");
            PermissionUtil.setGroupResolver((player, group) -> player == hidden && group.equals("Builders")); look.run();
            check(look.getTarget() == hidden, "live_group_change_invalidates_old_target");
            PermissionUtil.setGroupResolver(null); look.run();
            check(look.getTarget() == null, "missing_group_provider_does_not_grant_filter");
            String previous = look.getFilter();
            try { look.setFilter("unknown=value"); throw new AssertionError("Invalid filter accepted"); }
            catch (IllegalArgumentException expected) { check(previous.equals(look.getFilter()), "invalid_filter_keeps_previous_policy"); }
            look.setFilter("none"); look.run(); check(look.getTarget() == hidden, "explicit_filter_clear_restores_selection");
            look.setFilter("type=player"); look.setPerPlayer(true); look.run();
            var rotation = npc.getOrAddTrait(RotationTrait.class);
            check(rotation.getPacketSession(visible) != null && rotation.getPacketSession(hidden) != null,
                    "private_mode_consumes_configured_filter");
            look.setFilter("type=cow"); look.run();
            check(rotation.getPacketSession(visible) == null && rotation.getPacketSession(hidden) == null,
                    "filter_change_releases_old_private_sessions");
        } finally { PermissionUtil.setPermissionResolver(permissions); PermissionUtil.setGroupResolver(groups); }
    }

    private static void persistence() throws Exception {
        fresh();
        check(java.util.Arrays.equals(look.getRandomLookPitchRange(), new float[]{0,0})
                && java.util.Arrays.equals(look.getRandomLookYawRange(), new float[]{0,360}), "reference_random_defaults_restored");
        MemoryDataKey original = new MemoryDataKey();
        original.setDouble("randomPitchRange.0", -12); original.setDouble("randomPitchRange.1", 12);
        original.setDouble("randomYawRange.0", 45); original.setDouble("randomYawRange.1", 135);
        original.setString("filter", "type=cow"); original.setBoolean("targetnpcs", true);
        original.setBoolean("enabled", true); original.setBoolean("randomSwitchTargets", true);
        load(original); look.run();
        check(look.getTarget() == null && look.targetNPCs() && look.isRandomlySwitchingTargets()
                && java.util.Arrays.equals(look.getRandomLookPitchRange(), new float[]{-12,12})
                && java.util.Arrays.equals(look.getRandomLookYawRange(), new float[]{45,135}),
                "original_saved_fields_restore_actual_behavior");
        var roundtrip = new MemoryDataKey(); PersistenceLoader.save(look, roundtrip); look.save(roundtrip);
        load(roundtrip); look.run();
        check(look.getTarget() == null && look.getFilter().equals("type=cow") && look.targetNPCs(), "filter_and_target_policy_survive_roundtrip");
        MemoryDataKey legacy = new MemoryDataKey();
        legacy.setDouble("randomlookpitchrange.0", -30); legacy.setDouble("randomlookpitchrange.1", 30);
        legacy.setDouble("randomlookyawrange.0", -90); legacy.setDouble("randomlookyawrange.1", 90);
        load(legacy);
        check(java.util.Arrays.equals(look.getRandomLookPitchRange(), new float[]{-30,30})
                && java.util.Arrays.equals(look.getRandomLookYawRange(), new float[]{-90,90}), "earlier_native_range_keys_migrate");
        PersistenceLoader.save(look, legacy); look.save(legacy);
        check(!legacy.keyExists("randomlookpitchrange") && !legacy.keyExists("randomlookyawrange")
                && legacy.getDouble("randomPitchRange.0") == -30 && legacy.getDouble("randomYawRange.1") == 90,
                "saving_migrated_ranges_uses_original_keys");
        legacy.setDouble("randomlookpitchrange.0", 0); legacy.setDouble("randomlookpitchrange.1", 0);
        load(legacy); check(look.getRandomLookPitchRange()[0] == -30, "canonical_ranges_precede_legacy_aliases");
        float[] returned = look.getRandomLookPitchRange(); returned[0] = 123;
        check(look.getRandomLookPitchRange()[0] == -30, "range_getters_do_not_expose_mutable_configuration");
        MemoryDataKey invalid = new MemoryDataKey(); invalid.setString("filter", "type=missing:provider"); invalid.setBoolean("enabled", true);
        try { load(invalid); throw new AssertionError("Invalid persisted filter accepted"); }
        catch (NPCLoadException expected) { check(true, "invalid_saved_filter_reports_load_failure"); }
        look.run(); check(look.getTarget() == null, "invalid_saved_filter_remains_restrictive");
        PersistenceLoader.save(look, invalid); look.save(invalid);
        check(invalid.getString("filter").equals("type=missing:provider"), "invalid_saved_filter_is_retained_for_recovery");
        MemoryDataKey badAngles = new MemoryDataKey(); badAngles.setDouble("randomPitchRange.0", 7); badAngles.setBoolean("enableRandomLook", true);
        try { load(badAngles); throw new AssertionError("Invalid persisted angles accepted"); }
        catch (NPCLoadException expected) { check(true, "invalid_saved_angles_report_load_failure"); }
        look.run(); check(look.getRandomLookPitchRange().length == 1, "invalid_angles_are_retained_without_tick_exception");
    }

    private static void randomBehavior() {
        fresh(); var rotation = npc.getOrAddTrait(RotationTrait.class);
        rotation.getGlobalParameters().immediate(true).headOnly(false).linkedBody(true);
        look.setRandomLookPitchRange(0,0); look.setRandomLookYawRange(90,90); look.setRandomLookDelay(2);
        look.setRandomLook(true); look.onSpawn(); look.run(); rotation.run();
        check(!look.isEnabled() && npc.getEntity().getYRot() == 90, "random_mode_operates_independently_of_close_targeting");
        look.setRandomLookYawRange(45,45); look.run(); rotation.run();
        check(npc.getEntity().getYRot() == 90, "random_delay_keeps_previous_angle_between_requests");
        look.run(); rotation.run(); check(npc.getEntity().getYRot() == 45, "random_delay_issues_next_angle_on_schedule");
        look.setRandomLookDelay(1); look.setRandomLookYawRange(-45,-45); look.run(); look.run();
        check(rotation.getPhysicalSession().isActive(), "random_request_active_before_disable");
        look.setRandomLook(false); check(!rotation.getPhysicalSession().isActive(), "disable_random_releases_its_own_rotation");
        look.setRandomLook(true); look.run(); rotation.getPhysicalSession().rotateToHave(135,0);
        look.setRandomLook(false); check(rotation.getPhysicalSession().isActive(), "random_cleanup_preserves_later_external_rotation");
        float[] previous = look.getRandomLookPitchRange();
        for (float[] invalid : new float[][]{{Float.NaN,0},{0,Float.POSITIVE_INFINITY},{2,1}}) {
            try { look.setRandomLookPitchRange(invalid[0],invalid[1]); throw new AssertionError("Invalid angle range accepted"); }
            catch (IllegalArgumentException expected) { check(java.util.Arrays.equals(previous,look.getRandomLookPitchRange()), "invalid_programmatic_range_is_atomic"); }
        }
        fresh(); look.setEnabled(true); look.setRandomlySwitchTargets(true); look.setRandomLookDelay(1);
        look.run(); check(look.getTarget() == hidden, "random_switch_starts_with_nearest_target");
        look.run(); check(look.getTarget() == visible, "random_switch_moves_to_other_eligible_target");
        look.run(); check(look.getTarget() == hidden, "random_switch_continues_at_configured_delay");
    }

    private static void npcTargets(MinecraftServer server) {
        fresh(); look.setEnabled(true);
        NPC target = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, "LookNpcTarget"); helpers.add(target);
        target.getOrAddTrait(LookClose.class).setEnabled(false);
        check(target.spawn(new Location(server.overworld(), 0, -60, 1)), "target_player_npc_spawned");
        look.run(); check(look.getTarget() == hidden, "player_npcs_excluded_by_default");
        look.setTargetNPCs(true); look.run(); check(look.getTarget() == target.getEntity(), "enabled_npc_targeting_selects_live_player_npc");
        look.setTargetNPCs(false); look.run(); check(look.getTarget() == hidden, "disabling_npc_targeting_releases_npc_target");
        look.setTargetNPCs(true); look.setPerPlayer(true); look.run();
        check(npc.getOrAddTrait(RotationTrait.class).getPacketSession((ServerPlayer)target.getEntity()) == null,
                "per_viewer_mode_never_opens_session_for_npc_connection");
        look.setPerPlayer(false); look.run(); target.despawn(); look.run();
        check(look.getTarget() == hidden, "despawned_npc_target_is_dropped");
        target.getOrAddTrait(PacketNPC.class);
        check(target.spawn(new Location(server.overworld(), 0, -60, 1)), "virtual_player_target_spawned");
        look.setTargetNPCs(false); look.setTargetNPCs(true); look.run();
        check(look.getTarget() == target.getEntity(), "virtual_player_npc_target_uses_owned_entity_state");
        target.destroy();
    }

    private static void capture(ServerPlayer player, Packet<?> packet) {
        if (packet instanceof ClientboundBundlePacket bundle) { bundle.subPackets().forEach(part -> capture(player, part)); return; }
        if (packet instanceof ClientboundChunkBatchFinishedPacket) pendingBatches.merge(player, 1, Integer::sum);
        if (!(packet instanceof ClientboundMoveEntityPacket.Rot) && !(packet instanceof ClientboundRotateHeadPacket)) return;
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            if (packet instanceof ClientboundMoveEntityPacket.Rot rotation) {
                ClientboundMoveEntityPacket.Rot.STREAM_CODEC.encode(buffer, rotation);
                packets.get(player).add(new WireRotation(buffer.readVarInt(), false, buffer.readByte(), buffer.readByte()));
            } else if (packet instanceof ClientboundRotateHeadPacket head) {
                ClientboundRotateHeadPacket.STREAM_CODEC.encode(buffer, head);
                packets.get(player).add(new WireRotation(buffer.readVarInt(), true, buffer.readByte(), (byte)0));
            }
        } finally { buffer.release(); }
    }
    private static void pump() {
        for (var channel : channels) channel.runPendingTasks();
        for (var player : players) {
            int count = pendingBatches.getOrDefault(player, 0); pendingBatches.put(player, 0);
            while (count-- > 0) player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(16));
        }
        for (var channel : channels) { Object value; while ((value = channel.readOutbound()) != null) ReferenceCountUtil.release(value); }
    }
    private static void clearPackets() { pump(); packets.values().forEach(List::clear); }

    private static ServerPlayer admit(MinecraftServer server, String name, double x, double z) {
        var player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault());
        packets.put(player, new ArrayList<>());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                connection.configurePacketHandler(channel.pipeline());
                channel.pipeline().addLast("look-options-capture", new ChannelOutboundHandlerAdapter() {
                    @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) throws Exception {
                        if (message instanceof Packet<?> packet) capture(player, packet);
                        super.write(ctx, message, promise);
                    }
                });
            }
        });
        NetworkRegistry.configureMockConnection(connection);
        var cookie = new CommonListenerCookie(player.getGameProfile(), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setPos(x, -60, z);
        channels.add(channel); players.add(player);
        return player;
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++; LoggerFactory.getLogger("citizens").info("[LOOKOPTIONSAUDIT] PASS {}", label);
    }
}
