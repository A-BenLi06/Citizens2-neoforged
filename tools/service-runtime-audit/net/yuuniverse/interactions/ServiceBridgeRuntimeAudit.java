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
import net.yuuniverse.economy.api.ShopApiException;
import net.yuuniverse.economy.api.ShopApiException.Reason;
import net.yuuniverse.economy.api.ShopMenuRequest;
import net.yuuniverse.economy.api.SystemShopApi;
import net.yuuniverse.economy.core.CommerceAction;
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
            int optionalPaper = actor.getInventory().countItem(Items.PAPER);
            check(!actions.runAll(List.of(payment(), "rs_item: shared;absent-provider;1"), actor, Component.empty())
                    && actor.getInventory().countItem(Items.PAPER) == optionalPaper,
                    "missing_item_provider_fails_before_dialogue_payment");
            var migrationDirectory=java.nio.file.Files.createTempDirectory(server.getServerDirectory(),"missing-rsitems-");
            var migrationInput=migrationDirectory.resolve("items.yml");
            String migrationSource="'retained':\n  item: {type: PAPER, amount: 4}\n";
            java.nio.file.Files.writeString(migrationInput,migrationSource);
            var migrationReport=LegacySavedItemMigration.run(server.createCommandSourceStack(),migrationInput,
                    migrationDirectory.resolve("reports"),true);
            var failedMigration=new com.google.gson.Gson().fromJson(java.nio.file.Files.readString(migrationReport),LegacySavedItemMigration.Report.class);
            check(failedMigration.entries().get("retained").status().equals("FAILED")
                    && java.nio.file.Files.readString(migrationInput).equals(migrationSource)
                    && java.nio.file.Files.readString(migrationReport.getParent().resolve("source.yml")).equals(migrationSource),
                    "missing_item_provider_retains_migration_source_and_explicit_failure_report");
            check(!Conditions.all(List.of("%ftb_task_completed_10103% != true"), actor),
                    "missing_ftb_provider_does_not_satisfy_negative_condition");
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
            auditSystemShopApi(runtime, actor, other);
            auditCoordinateShopApi(runtime, actor, other);
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

    private static void auditSystemShopApi(EconomyRuntime runtime, AuditPlayer actor, AuditPlayer other) throws Exception {
        var shops = runtime.systemShops().orElseThrow();
        check(runtime.apiVersion() == 4 && runtime.commerceApi().isPresent() && shops.version() == 1 && shops.isAvailable(), "system_shop_capability_and_version");
        var menu = actor.containerMenu;
        long balance = runtime.balance(actor.getUUID(), "audit");
        long transactions = runtime.ledger().audit().transactionCount();
        int accounts = runtime.ledger().playerAccounts().size();
        var catalog = shops.catalog(actor.getUUID());
        check(catalog.stream().noneMatch(shop -> shop.id().equals("hidden_api") || shop.id().equals("disabled_api")),
                "catalog_filters_hidden_and_disabled_sections");
        check(shops.shop(actor.getUUID(), "hidden_api").orElseThrow().hidden()
                && shops.shop(actor.getUUID(), "disabled_api").isEmpty()
                && shops.shop(actor.getUUID(), "missing_api").isEmpty(), "direct_lookup_preserves_hidden_shop_policy");
        check(shops.pages(actor.getUUID(), "blocks").get(1).pageKey().equals("资源.items.special"),
                "pages_preserve_opaque_keys_and_sparse_order");
        var offers = shops.offers(actor.getUUID(), "blocks", "资源.items.special");
        check(offers.size() == 1 && offers.getFirst().itemKey().equals("opaque-api-offer"),
                "offer_lookup_uses_exact_page_membership");
        boolean immutable = false;
        try { offers.clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "catalog_offer_snapshot_is_immutable");
        check(shops.search(actor.getUUID(), "  资源  ").stream().anyMatch(offer -> offer.itemKey().equals("opaque-api-offer")),
                "search_matches_unicode_snapshot_text");
        var targets = List.<ShopMenuRequest>of(new ShopMenuRequest.Browser(0), new ShopMenuRequest.Shop("商店"),
                new ShopMenuRequest.Page("blocks", "资源.items.special"), new ShopMenuRequest.Search("资源", 0),
                new ShopMenuRequest.Quantity("blocks", "opaque-api-offer", CommerceAction.BUY_FROM_SHOP));
        for (var target : targets) shops.validate(actor.getUUID(), target);
        check(actor.containerMenu == menu && accounts == runtime.ledger().playerAccounts().size()
                && balance == runtime.balance(actor.getUUID(), "audit")
                && transactions == runtime.ledger().audit().transactionCount(), "system_shop_queries_and_all_preflights_have_no_effects");
        reject(shops, actor, new ShopMenuRequest.Browser(-1), Reason.INVALID_REQUEST);
        reject(shops, actor, new ShopMenuRequest.Browser(Integer.MAX_VALUE), Reason.INVALID_REQUEST);
        reject(shops, actor, new ShopMenuRequest.Search("资源", Integer.MAX_VALUE), Reason.INVALID_REQUEST);
        reject(shops, actor, new ShopMenuRequest.Search("  ", 0), Reason.INVALID_REQUEST);
        reject(shops, actor, new ShopMenuRequest.Page("blocks", "missing"), Reason.NOT_FOUND);
        reject(shops, actor, new ShopMenuRequest.Shop("disabled_api"), Reason.NOT_FOUND);
        reject(shops, actor, new ShopMenuRequest.Shop("no_pages"), Reason.NOT_FOUND);
        reject(shops, actor, new ShopMenuRequest.Quantity("blocks", "disabled-api-offer", CommerceAction.BUY_FROM_SHOP), Reason.NOT_FOUND);
        reject(shops, actor, new ShopMenuRequest.Quantity("blocks", "opaque-api-offer", CommerceAction.SELL_TO_SHOP), Reason.UNSUPPORTED_ACTION);
        reject(shops, actor, null, Reason.INVALID_REQUEST);
        check(rejected(() -> shops.open(UUID.randomUUID(), new ShopMenuRequest.Browser(0)), Reason.OFFLINE_PLAYER),
                "new_shop_api_rejects_offline_actor");
        check(CompletableFuture.supplyAsync(() -> rejected(
                () -> shops.catalog(actor.getUUID()), Reason.WRONG_THREAD)).join(), "catalog_rejects_worker_thread_access");
        check(actor.containerMenu == menu && accounts == runtime.ledger().playerAccounts().size(),
                "invalid_requests_leave_menu_and_accounts_unchanged");
        int otherMenus = other.openedMenus.size();
        for (var target : targets) {
            shops.open(actor.getUUID(), target);
            check(actor.containerMenu != menu, "public_menu_opens_" + target.getClass().getSimpleName());
            menu = actor.containerMenu;
        }
        actor.containerMenu.clicked(22, 0, net.minecraft.world.inventory.ClickType.PICKUP, actor);
        check(actor.openedMenus.getLast().equals("Audit exact page"), "quantity_back_uses_page_key_not_sparse_page_index");
        shops.open(actor.getUUID(), new ShopMenuRequest.Search("资源", 0));
        actor.containerMenu.clicked(0, 0, net.minecraft.world.inventory.ClickType.PICKUP, actor);
        actor.containerMenu.clicked(22, 0, net.minecraft.world.inventory.ClickType.PICKUP, actor);
        check(actor.openedMenus.getLast().equals("Audit exact page"), "search_quantity_back_uses_authoritative_page_membership");
        shops.open(actor.getUUID(), new ShopMenuRequest.Search("no-such-api-item", 0));
        check(actor.containerMenu.slots.getFirst().getItem().isEmpty(), "empty_search_first_page_is_a_valid_menu");
        check(other.openedMenus.size() == otherMenus && balance == runtime.balance(actor.getUUID(), "audit")
                && transactions == runtime.ledger().audit().transactionCount(), "shop_opening_neither_charges_nor_targets_other_players");
        var target = new ShopMenuRequest.Page("blocks", "资源.items.special");
        shops.validate(actor.getUUID(), target);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + runtime.ledger().databasePath());
             var sql = connection.createStatement()) {
            sql.executeUpdate("UPDATE system_shop SET enabled=0 WHERE shop_id='blocks'");
            try { reject(shops, actor, target, Reason.NOT_FOUND); }
            finally { sql.executeUpdate("UPDATE system_shop SET enabled=1 WHERE shop_id='blocks'"); }
        }
        auditConsumerMenus(runtime, actor, other);
        YuuniverseEconomyApi.clear(runtime);
        try {
            check(YuuniverseEconomyApi.current().isEmpty() && !shops.isAvailable()
                    && rejected(() -> shops.catalog(actor.getUUID()), Reason.UNAVAILABLE)
                    && rejected(() -> shops.open(actor.getUUID(), target), Reason.UNAVAILABLE),
                    "retired_shop_facade_cannot_query_or_open_menus");
        } finally { YuuniverseEconomyApi.publish(runtime); }
    }

    private static void auditConsumerMenus(EconomyRuntime runtime, AuditPlayer actor, AuditPlayer other) throws Exception {
        var actions = new Actions(new ItemLibrary(), new Economy());
        var definitions = List.of("browser;0", "shop;A shop", "page;blocks;资源.items.special", "search;资源;0",
                "quantity;blocks;opaque-api-offer;buy");
        long transactions = runtime.ledger().audit().transactionCount();
        int otherMenus = other.openedMenus.size();
        for (String definition : definitions) {
            var before = actor.containerMenu;
            String action = "economy_shop: " + definition;
            check(actions.validateAll(List.of(action), actor, Component.empty()) && actor.containerMenu == before,
                    "consumer_menu_readonly_preflight_" + definition);
            check(actions.runAll(List.of(action), actor, Component.empty()) && actor.containerMenu != before,
                    "consumer_dialogue_menu_opens_" + definition);
        }
        for (String definition : List.of("page;blocks;absent", "quantity;blocks;opaque-api-offer;sell",
                "browser;-1", "search;资源;2147483648", "shop;")) {
            var before = actor.containerMenu;
            int paper = actor.getInventory().countItem(Items.PAPER);
            check(!actions.runAll(List.of(payment(), "economy_shop: " + definition), actor, Component.empty())
                    && actor.containerMenu == before && actor.getInventory().countItem(Items.PAPER) == paper,
                    "invalid_consumer_target_preserves_menu_and_payment_" + definition);
        }
        var before = actor.containerMenu;
        serverCommand(actor, "citizens economyshop page blocks \"资源.items.special\"");
        check(actor.containerMenu != before && actor.openedMenus.getLast().equals("Audit exact page"),
                "citizens_menu_command_opens_exact_provider_page");
        before = actor.containerMenu;
        serverCommand(actor, "citizens economyshop shop \"A shop\"");
        check(actor.containerMenu != before && actor.openedMenus.getLast().equals("Audit A shop"),
                "citizens_menu_command_preserves_quoted_shop_id");
        check(other.openedMenus.size() == otherMenus && transactions == runtime.ledger().audit().transactionCount(),
                "consumer_opening_is_actor_scoped_and_posts_no_money");
        for (boolean packet : new boolean[] { false, true }) {
            var npc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry()
                    .createNPC(net.minecraft.world.entity.EntityType.COW, "EconomyMenu" + packet);
            try {
                if (packet) npc.getOrAddTrait(net.citizensnpcs.trait.PacketNPC.class);
                npc.spawn(new net.citizensnpcs.api.util.Location(actor.serverLevel(), 2, 80, 2));
                var command = npc.getOrAddTrait(net.citizensnpcs.trait.CommandTrait.class);
                command.addCommand(new net.citizensnpcs.trait.CommandTrait.NPCCommandBuilder(
                        "citizens economyshop page blocks \"资源.items.special\"",
                        net.citizensnpcs.trait.CommandTrait.Hand.RIGHT).player(true).cooldown(-1));
                before = actor.containerMenu;
                command.dispatch(actor, net.citizensnpcs.trait.CommandTrait.Hand.RIGHT);
                net.citizensnpcs.Citizens.getInstance().getScheduler().tick();
                check(actor.containerMenu != before && actor.openedMenus.getLast().equals("Audit exact page"),
                        "native_npc_command_opens_economy_page_" + packet);
            } finally { npc.destroy(); }
        }
        var legacyCalls = new java.util.concurrent.atomic.AtomicInteger();
        var legacy = (net.yuuniverse.economy.api.EconomyApi) java.lang.reflect.Proxy.newProxyInstance(
                runtime.getClass().getClassLoader(), new Class<?>[] { net.yuuniverse.economy.api.EconomyApi.class },
                (proxy, method, arguments) -> {
                    if (method.getName().equals("systemShops")) return java.util.Optional.empty();
                    if (method.getName().equals("validateSystemShop") || method.getName().equals("openSystemShop")) {
                        check(arguments[0].equals(actor.getUUID()) && arguments[1].equals("A shop"),
                                "legacy_fallback_retains_actor_and_exact_id_" + method.getName());
                        legacyCalls.incrementAndGet();
                        return null;
                    }
                    try { return method.invoke(runtime, arguments); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
        YuuniverseEconomyApi.clear(runtime);
        YuuniverseEconomyApi.publish(legacy);
        try {
            before = actor.containerMenu;
            check(actions.runAll(List.of("economy_shop: shop;A shop"), actor, Component.empty())
                    && legacyCalls.get() == 2, "consumer_legacy_named_shop_fallback_dispatch");
            before = actor.containerMenu;
            int paper = actor.getInventory().countItem(Items.PAPER);
            check(!actions.runAll(List.of(payment(), "economy_shop: browser;0"), actor, Component.empty())
                    && actor.containerMenu == before && actor.getInventory().countItem(Items.PAPER) == paper,
                    "consumer_legacy_missing_capability_rejects_before_payment");
        } finally { YuuniverseEconomyApi.clear(legacy); YuuniverseEconomyApi.publish(runtime); }
        var target = net.citizensnpcs.util.EconomyShopMenus.parse(List.of("page", "blocks", "资源.items.special"));
        YuuniverseEconomyApi.clear(runtime);
        try {
            before = actor.containerMenu;
            int paper = actor.getInventory().countItem(Items.PAPER);
            check(!actions.runAll(List.of(payment(), "economy_shop: page;blocks;资源.items.special"), actor, Component.empty())
                    && actor.containerMenu == before && actor.getInventory().countItem(Items.PAPER) == paper,
                    "consumer_unpublished_provider_rejects_before_payment");
        } finally { YuuniverseEconomyApi.publish(runtime); }
        before = actor.containerMenu;
        net.citizensnpcs.util.EconomyShopMenus.present(actor, target, false);
        check(actor.containerMenu != before, "consumer_reacquires_republished_provider");
    }

    private static void auditCoordinateShopApi(EconomyRuntime runtime, AuditPlayer actor, AuditPlayer other) throws Exception {
        var api = runtime.coordinateShops().orElseThrow();
        var level = actor.serverLevel();
        var position = new BlockPos(4, 80, 4);
        var original = level.getBlockState(position);
        var actorPosition = actor.position();
        var otherPosition = other.position();
        var otherLevel = other.serverLevel();
        var ledger = runtime.ledger();
        net.yuuniverse.economy.core.CoordinateShop shop = null;
        try {
            level.setBlock(position, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
            actor.setPos(3, 80, 3);
            if (other.serverLevel() != level) other.teleportTo(level, 3, 80, 3, 0, 0);
            other.setPos(3, 80, 3);
            var owner = runtime.playerAccount(actor.getUUID()).orElseThrow();
            var buyer = runtime.playerAccount(other.getUUID()).orElseThrow();
            shop = ledger.createCoordinateShop(new net.yuuniverse.economy.core.CoordinateShopDraft(
                    level.dimension().location().toString(), "fixture", position.getX(), position.getY(), position.getZ(),
                    owner.id(), "SELL_TO_PLAYER", "{\"material\":\"STONE\"}", "STONE", "audit", 10, 1,
                    false, 0L, false, 0, null), "coordinate-api-fixture");
            var id = new net.yuuniverse.economy.api.CoordinateShopReference.Id(shop.id());
            var at = new net.yuuniverse.economy.api.CoordinateShopReference.Position(level.dimension().location().toString(), 4, 80, 4);
            final String shopId = shop.id();
            var before = actor.containerMenu;
            int accounts = ledger.playerAccounts().size();
            long transactions = ledger.audit().transactionCount();
            check(api.version() == 1 && api.isAvailable() && api.shop(actor.getUUID(), id).orElseThrow().id().equals(shopId)
                    && api.shop(actor.getUUID(), at).orElseThrow().id().equals(shopId), "coordinate_exact_id_and_position_lookup");
            api.validate(actor.getUUID(), id);
            check(actor.containerMenu == before && accounts == ledger.playerAccounts().size()
                    && transactions == ledger.audit().transactionCount(), "coordinate_lookup_preflight_has_no_effects");
            check(api.shop(actor.getUUID(), new net.yuuniverse.economy.api.CoordinateShopReference.Id("absent")).isEmpty(),
                    "coordinate_missing_id_lookup_is_empty");
            api.open(actor.getUUID(), id);
            check(actor.containerMenu != before, "coordinate_api_opens_economy_menu");
            var snapshot = api.shop(actor.getUUID(), id).orElseThrow();
            boolean immutable = false;
            try { snapshot.staffAccountIds().add(buyer.id()); } catch (UnsupportedOperationException expected) { immutable = true; }
            check(immutable, "coordinate_snapshot_staff_is_immutable");
            ledger.configureCoordinateShop(shopId, buyer.id(), "SELL_TO_PLAYER", "audit", 17, 2, false, true, 0, null, 0, "fixture");
            actor.containerMenu.clicked(45, 0, net.minecraft.world.inventory.ClickType.PICKUP, actor);
            check(ledger.coordinateShopDetails(shopId).orElseThrow().ownerAccountId().equals(buyer.id())
                    && ledger.coordinateShopDetails(shopId).orElseThrow().mode().equals("SELL_TO_PLAYER"),
                    "old_owner_menu_cannot_toggle_or_restore_old_owner");
            ledger.setCoordinateShopStaff(shopId, owner.id(), true, "fixture");
            api.open(actor.getUUID(), id);
            ledger.setCoordinateShopStaff(shopId, owner.id(), false, "fixture");
            actor.containerMenu.clicked(47, 0, net.minecraft.world.inventory.ClickType.PICKUP, actor);
            check(ledger.coordinateShopDetails(shopId).orElseThrow().displayEnabled(), "revoked_staff_menu_cannot_toggle_display");
            api.open(other.getUUID(), id);
            var oldMenu = other.containerMenu;
            ledger.configureCoordinateShop(shopId, buyer.id(), "SELL_TO_PLAYER", "audit", 31, 3, false, true, 0, null, 0, "fixture");
            other.containerMenu.clicked(45, 0, net.minecraft.world.inventory.ClickType.PICKUP, other);
            var current = ledger.coordinateShopDetails(shopId).orElseThrow();
            check(current.mode().equals("BUY_FROM_PLAYER") && current.priceMinorUnits() == 31 && current.itemAmount() == 3,
                    "management_toggle_preserves_latest_price_and_amount");
            oldMenu.clicked(49, 0, net.minecraft.world.inventory.ClickType.PICKUP, other);
            check(ledger.coordinateShopDetails(shopId).orElseThrow().enabled(), "retired_menu_cannot_change_shop");
            other.containerMenu.clicked(49, 0, net.minecraft.world.inventory.ClickType.PICKUP, actor);
            check(ledger.coordinateShopDetails(shopId).orElseThrow().enabled(), "other_actor_cannot_click_owner_menu");
            ledger.setCoordinateShopEnabled(shopId, false, "fixture");
            rejectCoordinate(api, actor, id, Reason.ACCESS_DENIED);
            api.open(other.getUUID(), id);
            other.containerMenu.clicked(49, 0, net.minecraft.world.inventory.ClickType.PICKUP, other);
            check(ledger.coordinateShopDetails(shopId).orElseThrow().enabled(), "disabled_shop_owner_can_reenable_through_menu");
            actor.setPos(40, 80, 40);
            rejectCoordinate(api, actor, id, Reason.OUT_OF_RANGE);
            actor.setPos(3, 80, 3);
            level.setBlock(position, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            rejectCoordinate(api, actor, id, Reason.CONTAINER_UNAVAILABLE);
            level.setBlock(position, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + ledger.databasePath());
                    var update = connection.prepareStatement("UPDATE coordinate_shop SET legacy_material=?,item_config_json=? WHERE shop_id=?")) {
                update.setString(1, "missing:coordinate_item");
                update.setString(2, "{\"material\":\"missing:coordinate_item\",\"display-item\":{\"material\":\"STONE\"}}");
                update.setString(3, shopId); update.executeUpdate();
                try { rejectCoordinate(api, actor, id, Reason.ITEM_UNAVAILABLE); }
                finally {
                    update.setString(1, "STONE"); update.setString(2, "{\"material\":\"STONE\"}"); update.executeUpdate();
                }
            }
            check(rejected(() -> api.validate(UUID.randomUUID(), id), Reason.OFFLINE_PLAYER), "coordinate_offline_actor_rejected");
            check(CompletableFuture.supplyAsync(() -> rejected(() -> api.validate(actor.getUUID(), id), Reason.WRONG_THREAD)).join(),
                    "coordinate_wrong_thread_rejected");
            rejectCoordinate(api, actor, new net.yuuniverse.economy.api.CoordinateShopReference.Id(""), Reason.INVALID_REQUEST);
            rejectCoordinate(api, actor, new net.yuuniverse.economy.api.CoordinateShopReference.Position("missing:dimension", 4, 80, 4),
                    Reason.DIMENSION_UNAVAILABLE);
            actor.teleportTo(otherLevel, 3, 80, 3, 0, 0);
            if (otherLevel != level) rejectCoordinate(api, actor, id, Reason.OUT_OF_RANGE);
            actor.teleportTo(level, 3, 80, 3, 0, 0);
            var actions = new Actions(new ItemLibrary(), new Economy());
            before = actor.containerMenu;
            check(actions.validateAll(List.of("economy_shop: coordinate;" + shopId), actor, Component.empty())
                    && actor.containerMenu == before, "coordinate_dialogue_preflight_is_readonly");
            check(actions.runAll(List.of("economy_shop: coordinate;" + shopId), actor, Component.empty())
                    && actor.containerMenu != before, "coordinate_dialogue_opens_provider_menu");
            before = actor.containerMenu;
            serverCommand(actor, "citizens economyshop coordinate " + shopId);
            check(actor.containerMenu != before, "coordinate_citizens_command_opens_provider_menu");
            for (boolean packet : new boolean[] { false, true }) {
                var npc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry()
                        .createNPC(net.minecraft.world.entity.EntityType.COW, "CoordinateMenu" + packet);
                try {
                    if (packet) npc.getOrAddTrait(net.citizensnpcs.trait.PacketNPC.class);
                    npc.spawn(new net.citizensnpcs.api.util.Location(level, 2, 80, 2));
                    var command = npc.getOrAddTrait(net.citizensnpcs.trait.CommandTrait.class);
                    command.addCommand(new net.citizensnpcs.trait.CommandTrait.NPCCommandBuilder(
                            "citizens economyshop coordinate " + shopId,
                            net.citizensnpcs.trait.CommandTrait.Hand.RIGHT).player(true).cooldown(-1));
                    before = actor.containerMenu;
                    command.dispatch(actor, net.citizensnpcs.trait.CommandTrait.Hand.RIGHT);
                    net.citizensnpcs.Citizens.getInstance().getScheduler().tick();
                    check(actor.containerMenu != before, "coordinate_npc_command_opens_provider_menu_" + packet);
                } finally { npc.destroy(); }
            }
            actor.setPos(40, 80, 40);
            int paper = actor.getInventory().countItem(Items.PAPER);
            check(!actions.runAll(List.of(payment(), "economy_shop: coordinate;" + shopId), actor, Component.empty())
                    && actor.getInventory().countItem(Items.PAPER) == paper, "coordinate_range_failure_preserves_dialogue_payment");
            actor.setPos(3, 80, 3);
            YuuniverseEconomyApi.clear(runtime);
            try { check(rejected(() -> api.validate(actor.getUUID(), id), Reason.UNAVAILABLE), "coordinate_retired_provider_rejected"); }
            finally { YuuniverseEconomyApi.publish(runtime); }
        } finally {
            if (shop != null) ledger.removeCoordinateShop(shop.id(), "fixture-cleanup");
            actor.setPos(actorPosition);
            if (other.serverLevel() != otherLevel) other.teleportTo(otherLevel,
                    otherPosition.x, otherPosition.y, otherPosition.z, 0, 0);
            other.setPos(otherPosition);
            level.setBlock(position, original, 3);
        }
    }

    private static void rejectCoordinate(net.yuuniverse.economy.api.CoordinateShopApi api, AuditPlayer actor,
            net.yuuniverse.economy.api.CoordinateShopReference reference, Reason reason) {
        var before = actor.containerMenu;
        check(rejected(() -> api.validate(actor.getUUID(), reference), reason)
                && rejected(() -> api.open(actor.getUUID(), reference), reason) && actor.containerMenu == before,
                "coordinate_reject_" + reason);
    }

    private static void serverCommand(AuditPlayer actor, String line) {
        actor.getServer().getCommands().performPrefixedCommand(actor.createCommandSourceStack(), line);
    }

    private static void reject(SystemShopApi api, AuditPlayer actor, ShopMenuRequest request, Reason reason) {
        var before = actor.containerMenu;
        check(rejected(() -> api.validate(actor.getUUID(), request), reason)
                && rejected(() -> api.open(actor.getUUID(), request), reason) && actor.containerMenu == before,
                "reject_" + (request == null ? "null" : request) + "_" + reason);
    }

    private static boolean rejected(Runnable action, Reason reason) {
        try { action.run(); return false; }
        catch (ShopApiException expected) { return expected.reason() == reason; }
    }

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
            try (var sql = connection.createStatement()) {
                sql.executeUpdate("""
                        INSERT OR REPLACE INTO system_shop VALUES
                        ('hidden_api','Hidden','fixture','fixture','{}',1),
                        ('disabled_api','Disabled','fixture','fixture','{}',0)
                        """);
                sql.executeUpdate("""
                        INSERT OR REPLACE INTO system_shop_section
                        (shop_id,section_slot,hidden,title,icon_config_json)
                        VALUES ('hidden_api',0,1,'Hidden','{}')
                        """);
                sql.executeUpdate("""
                        INSERT OR REPLACE INTO system_shop_page VALUES
                        ('blocks','资源.items.special',10,'Audit exact page',3,'{}'),
                        ('blocks','last',20,'Audit last page',3,'{}')
                        """);
                sql.executeUpdate("""
                        INSERT OR REPLACE INTO system_shop_item
                        (shop_id,item_key,page_key,slot_key,legacy_material,item_config_json,currency_id,
                         buy_price_minor_units,sell_price_minor_units,dynamic_pricing,enabled) VALUES
                        ('blocks','opaque-api-offer','资源.items.special','1','STONE','{"material":"STONE","name":"资源"}','audit',10,NULL,0,1),
                        ('blocks','disabled-api-offer','资源.items.special','2','STONE','{"material":"STONE"}','audit',10,NULL,0,0)
                        """);
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
