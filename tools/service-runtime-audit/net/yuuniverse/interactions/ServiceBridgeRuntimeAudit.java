package net.yuuniverse.interactions;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.yuuniverse.economy.EconomyRuntime;
import net.yuuniverse.economy.api.YuuniverseEconomyApi;
import org.slf4j.LoggerFactory;
import team.creative.cmdcam.common.packet.StartPathPacket;
import team.creative.cmdcam.common.scene.CamScene;
import team.creative.cmdcam.server.CMDCamServer;

/** Actual provider APIs, menus, legacy scene loading and encoded packets. No real client is attached. */
@EventBusSubscriber(modid = "interactions")
public final class ServiceBridgeRuntimeAudit {
    private static boolean forced, ran;
    private static int passed;
    private static final List<String> SHOPS = List.of("blocks", "商店", "A shop");
    private static final List<String> SCENES = List.of("dead_end1", "uDays_intro", "uDays_intro2", "uDays_intro3");
    private static final UUID OFFLINE = UUID.fromString("0142af86-5c6e-4c0b-92f6-8394cf001264");
    private static final UUID AMBIGUOUS = UUID.fromString("043bf111-89af-40e1-bf74-e83dd72acd96");

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (ran) return;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        ServerLevel end = server.getLevel(Level.END);
        if (!forced) {
            level.setChunkForced(0, 0, true);
            end.setChunkForced(0, 0, true);
            forced = true;
        }
        if (!ready(level) || !ready(end)) {
            if (server.getTickCount() < 1200) return;
            ran = true;
            LoggerFactory.getLogger("interactions").error("[SERVICEAUDIT] FAILED fixture chunks did not become ready");
            server.halt(false);
            return;
        }
        ran = true;
        var players = new ArrayList<AuditPlayer>();
        try {
            var api = YuuniverseEconomyApi.current().orElseThrow();
            var runtime = (EconomyRuntime) api;
            seedShops(runtime.ledger().databasePath());
            AuditPlayer actor = player(level, players, "ServiceActor");
            AuditPlayer other = player(level, players, "ServiceOther");
            var economy = new Economy();
            economy.install();
            var actions = new Actions(new ItemLibrary(), economy);
            CommandAliases.load(Path.of("config/interactions/command-aliases.yml").toFile());
            check(CommandAliases.rewrite("shop 商店", actor.getGameProfile().getName()).equals("shop 商店")
                    && CommandAliases.rewrite("cam-server start test ServiceActor", "ServiceActor")
                            .equals("cam-server start test ServiceActor"), "legacy_native_placeholders_no_longer_block_services");
            var previous = actor.containerMenu;
            long balance = api.balance(actor.getUUID(), "audit");
            api.validateSystemShop(actor.getUUID(), "商店");
            check(actor.containerMenu == previous && api.balance(actor.getUUID(), "audit") == balance,
                    "shop_preflight_has_no_menu_or_balance_side_effect");
            for (String shop : SHOPS) {
                String argument = shop.indexOf(' ') >= 0 ? '"' + shop + '"' : shop;
                check(actions.runAll(List.of("player_command_as_op: shop " + argument), actor, Component.empty())
                        && actor.containerMenu != previous && actor.openedMenus.getLast().equals("Audit " + shop),
                        "economy_menu_opens_for_" + shop.replace(' ', '_'));
                previous = actor.containerMenu;
            }
            check(other.openedMenus.isEmpty(), "shop_targets_the_dialogue_player_only");
            actor.getInventory().clearContent();
            actor.getInventory().add(new ItemStack(Items.PAPER, 2));
            check(!actions.runAll(List.of(payment(), "player_command_as_op: shop missing_shop"), actor, Component.empty())
                    && actor.getInventory().countItem(Items.PAPER) == 2 && actor.containerMenu == previous,
                    "missing_shop_fails_before_item_payment");
            check(!actions.runAll(List.of(payment(), "player_command_as_op: shop no_pages"), actor, Component.empty())
                    && actor.getInventory().countItem(Items.PAPER) == 2 && actor.containerMenu == previous,
                    "unrenderable_shop_fails_before_item_payment");
            check(!actions.runAll(List.of(payment(), "console_command: shop blocks"), actor, Component.empty())
                    && actor.getInventory().countItem(Items.PAPER) == 2,
                    "console_shop_does_not_silently_substitute_the_actor");
            UUID absent = UUID.randomUUID();
            boolean offlineRejected = false;
            try { api.openSystemShop(absent, "blocks"); } catch (RuntimeException expected) { offlineRejected = true; }
            check(offlineRejected && api.playerAccount(absent).isEmpty(), "offline_shop_api_does_not_create_an_account");
            check(CompletableFuture.supplyAsync(() -> {
                try { api.validateSystemShop(actor.getUUID(), "blocks"); return false; }
                catch (IllegalStateException expected) { return true; }
            }).join(), "shop_api_rejects_off_thread_presentation");
            check(actions.runAll(List.of("console_command: eco give ServiceActor 1.25"), actor, Component.empty())
                    && api.balance(actor.getUUID(), "audit") == balance + 125,
                    "dialogue_money_still_uses_the_real_economy_ledger");
            balance = api.balance(actor.getUUID(), "audit");
            check(!actions.runAll(List.of("console_command: eco take ServiceActor 1",
                    "player_command_as_op: shop missing_shop"), actor, Component.empty())
                    && api.balance(actor.getUUID(), "audit") == balance, "missing_shop_fails_before_ledger_payment");
            check(actions.runAll(List.of("player_command_as_op: shop list"), actor, Component.empty())
                    && actor.containerMenu == previous, "native_shop_subcommands_keep_their_dispatcher_grammar");
            actor.doCloseContainer();

            api.ensurePlayerAccount(OFFLINE, "OfflineService");
            // Prior runs may have left the intentionally ambiguous fixture name on this second account.
            api.ensurePlayerAccount(AMBIGUOUS, "OtherService");
            api.setBalance(OFFLINE, "reserve", 0, "service:" + UUID.randomUUID(), "service-audit");
            check(api.resolvePlayerAccount("offlineservice").orElseThrow().ownerUuid().equals(OFFLINE)
                    && server.getPlayerList().getPlayer(OFFLINE) == null, "stored_offline_name_resolves_to_its_existing_uuid");
            long offlineDefault = api.balance(OFFLINE, "audit");
            check(actions.runAll(List.of("console_command: eco give OfflineService 1.005 储备币"), actor, Component.empty())
                    && api.balance(OFFLINE, "reserve") == 1005 && api.balance(OFFLINE, "audit") == offlineDefault,
                    "named_currency_and_its_scale_apply_to_offline_credit");
            check(actions.runAll(List.of("console_command: eco take OfflineService 0.001 reserve"), actor, Component.empty())
                    && api.balance(OFFLINE, "reserve") == 1004, "currency_id_applies_to_offline_debit");
            check(actions.runAll(List.of("console_command: balance OfflineService 储备币"), actor, Component.empty())
                    && actor.messages.getLast().contains("1.004"), "offline_balance_uses_the_selected_currency");
            check(!actions.runAll(List.of(payment(), "console_command: eco give OfflineService 1 unknown_currency"), actor,
                    Component.empty()) && api.balance(OFFLINE, "reserve") == 1004
                    && actor.getInventory().countItem(Items.PAPER) == 2, "unknown_currency_fails_before_payment");
            api.ensurePlayerAccount(AMBIGUOUS, "OFFLINESERVICE");
            try {
                check(!actions.runAll(List.of("console_command: eco give OfflineService 1 reserve"), actor, Component.empty())
                        && api.balance(OFFLINE, "reserve") == 1004, "ambiguous_offline_names_are_not_credited");
                check(actions.runAll(List.of("console_command: eco give " + OFFLINE + " 0.001 reserve"), actor, Component.empty())
                        && api.balance(OFFLINE, "reserve") == 1005, "explicit_uuid_remains_usable_when_names_are_ambiguous");
            } finally { api.ensurePlayerAccount(AMBIGUOUS, "OtherService"); }

            check(server.getCommands().getDispatcher().getRoot().getChild("cam-server") != null,
                    "actual_cmdcam_command_is_registered");
            check(CMDCamServer.getSavedPaths(level).contains("dead_end1")
                    && CMDCamServer.getSavedPaths(end).containsAll(SCENES.subList(1, SCENES.size())),
                    "all_four_old_camera_scenes_load_in_their_original_dimensions");
            for (String scene : SCENES) {
                ServerLevel sceneLevel = scene.equals("dead_end1") ? level : end;
                if (actor.serverLevel() != sceneLevel) actor.teleportTo(sceneLevel, 1, 80, 1, 0, 0);
                String command = "player_command_as_op: cam-server start " + scene + " ServiceActor";
                int before = actor.paths.size();
                check(actions.validateAll(List.of(command), actor, Component.empty()) && actor.paths.size() == before,
                        "camera_preflight_is_read_only_" + scene);
                CompoundTag expected = CMDCamServer.get(sceneLevel, scene).save(new CompoundTag());
                check(actions.runAll(List.of(command), actor, Component.empty()) && actor.paths.size() == before + 1
                        && actor.paths.getLast().equals(expected) && other.paths.isEmpty(), "camera_packet_matches_legacy_scene_" + scene);
            }
            int before = actor.paths.size();
            check(!actions.runAll(List.of(payment(), "player_command_as_op: cam-server start absent ServiceActor"),
                    actor, Component.empty()) && actor.getInventory().countItem(Items.PAPER) == 2 && actor.paths.size() == before,
                    "missing_camera_fails_before_payment");
            var empty = new CamScene(CMDCamServer.get(actor.serverLevel(), SCENES.getLast()).save(new CompoundTag()));
            empty.points.clear();
            CMDCamServer.set(actor.serverLevel(), "empty_audit", empty);
            try {
                check(!actions.runAll(List.of(payment(), "player_command_as_op: cam-server start empty_audit ServiceActor"),
                        actor, Component.empty()) && actor.getInventory().countItem(Items.PAPER) == 2,
                        "empty_camera_fails_before_payment_despite_provider_zero_return");
            } finally { CMDCamServer.removePath(actor.serverLevel(), "empty_audit"); }
            check(!actions.runAll(List.of(payment(), "player_command_as_op: cam-server start uDays_intro3 MissingSvc"),
                    actor, Component.empty()) && actor.getInventory().countItem(Items.PAPER) == 2 && actor.paths.size() == before,
                    "missing_camera_recipient_fails_before_payment");
            actor.channel.checkException();
            other.channel.checkException();
            check(actor.channel.isActive() && other.channel.isActive(), "provider_packets_encode_without_disconnect");
            check(runtime.ledger().audit().valid(), "economy_ledger_remains_balanced");
            LoggerFactory.getLogger("interactions").info("[SERVICEAUDIT] COMPLETE {} checks", passed);
        } catch (Throwable failure) {
            LoggerFactory.getLogger("interactions").error("[SERVICEAUDIT] FAILED", failure);
        } finally {
            for (AuditPlayer player : players) {
                try {
                    if (server.getPlayerList().getPlayer(player.getUUID()) == player) server.getPlayerList().remove(player);
                    else level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
                    if (player.channel != null) player.channel.finishAndReleaseAll();
                } catch (Throwable failure) {
                    LoggerFactory.getLogger("interactions").error("[SERVICEAUDIT] FAILED player cleanup", failure);
                }
            }
            server.halt(false);
        }
    }

    private static String payment() { return "remove_item: %checkitem_remove_mat:minecraft:paper,amt:1%"; }

    private static boolean ready(ServerLevel level) {
        return level.areEntitiesLoaded(ChunkPos.asLong(0, 0)) && level.isPositionEntityTicking(new BlockPos(1, 0, 1));
    }

    private static void seedShops(Path database) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            try (var shop = connection.prepareStatement("INSERT OR IGNORE INTO system_shop "
                    + "(shop_id,display_name,source_file,source_sha256,config_json,enabled) VALUES (?,?, 'service-audit','fixture','{}',1)");
                 var page = connection.prepareStatement("INSERT OR IGNORE INTO system_shop_page "
                    + "(shop_id,page_key,page_index,title,gui_rows,page_config_json) VALUES (?,'page1',0,?,3,'{}')")) {
                for (String id : SHOPS) {
                    shop.setString(1, id); shop.setString(2, id); shop.executeUpdate();
                    page.setString(1, id); page.setString(2, "Audit " + id); page.executeUpdate();
                }
                shop.setString(1, "no_pages"); shop.setString(2, "No pages"); shop.executeUpdate();
            }
        }
    }

    private static AuditPlayer player(ServerLevel level, List<AuditPlayer> players, String name) {
        var player = new AuditPlayer(level, name);
        players.add(player);
        player.setPos(1, -60, 1);
        var connection = new Connection(PacketFlow.SERVERBOUND);
        player.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                Connection.configureInMemoryPipeline(channel.pipeline(), PacketFlow.SERVERBOUND);
                connection.configurePacketHandler(channel.pipeline());
                channel.pipeline().addBefore("packet_handler", "audit_camera", new ChannelOutboundHandlerAdapter() {
                    @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                        if (message instanceof ClientboundCustomPayloadPacket payload && payload.payload() instanceof StartPathPacket start)
                            player.paths.add(start.nbt.copy());
                        super.write(context, message, promise);
                    }
                });
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

    private static final class AuditPlayer extends ServerPlayer {
        EmbeddedChannel channel;
        final List<String> openedMenus = new ArrayList<>();
        final List<CompoundTag> paths = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        AuditPlayer(ServerLevel level, String name) {
            super(level.getServer(), level, new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault());
        }
        @Override public OptionalInt openMenu(MenuProvider provider) {
            openedMenus.add(provider.getDisplayName().getString());
            return super.openMenu(provider);
        }
        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        passed++;
        LoggerFactory.getLogger("interactions").info("[SERVICEAUDIT] PASS {}", name);
    }
}
