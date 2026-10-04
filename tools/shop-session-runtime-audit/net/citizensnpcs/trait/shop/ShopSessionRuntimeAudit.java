package net.citizensnpcs.trait.shop;

import java.nio.file.*;
import java.util.*;
import com.mojang.authlib.GameProfile;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.Citizens;
import net.citizensnpcs.api.gui.*;
import net.citizensnpcs.api.gui.InventoryMenu;
import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.minecraft.network.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class ShopSessionRuntimeAudit {
    private static final List<ServerPlayer> players = new ArrayList<>();
    private static final List<EmbeddedChannel> channels = new ArrayList<>();
    private static Citizens plugin;
    private static NPCShop old;
    private static NPCShopItem item;
    private static InventoryMenu chestMenu, editorMenu;
    private static AbstractContainerMenu chest, trader;
    private static int passed, phase, answers, reentry;
    private static boolean done;

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done || event.getServer().getTickCount() < 10) return;
        var server = event.getServer(); plugin = Citizens.getInstance();
        try {
            if (phase == 0) {
                check(Files.isRegularFile(Path.of("shop-session-audit-fixture.txt")), "isolated_fixture");
                for (int i = 0; i < 3; i++) players.add(admit(server, "ShopSession" + i));
                setup(); failedReload(); validTradeAndReentry();
                editorMenu.transition(InputMenus.filteredStringSetter(() -> "x".repeat(70), answer -> { answers++; return true; }));
                check(players.get(1).containerMenu == players.get(1).inventoryMenu, "editor_chat_fallback_open");
                plugin.reload();
                verifyRetired();
                phase = 1;
            } else if (phase == 1) {
                check(players.get(1).containerMenu == players.get(1).inventoryMenu && answers == 0, "no_delayed_editor_return");
                NPCShop current = plugin.getShops().getGlobalShop("session-shop");
                current.displayEditor(null, players.get(1));
                InventoryMenu pending = menu(players.get(1).containerMenu);
                pending.transition(InputMenus.filteredStringSetter(() -> "short", answer -> true));
                players.get(1).closeContainer();
                check(players.get(1).containerMenu == players.get(1).inventoryMenu, "parent_return_is_deferred");
                plugin.reload();
                phase = 2;
            } else {
                check(players.get(1).containerMenu == players.get(1).inventoryMenu, "publication_cancels_pending_parent_return");
                throwingDisposal();
                deletionAndShutdown();
                done = true;
                LoggerFactory.getLogger("citizens").info("[SHOPSESSIONAUDIT] COMPLETE {} checks", passed);
            }
        } catch (Throwable failure) {
            done = true; LoggerFactory.getLogger("citizens").error("[SHOPSESSIONAUDIT] FAILED", failure);
        } finally {
            if (done) {
                for (ServerPlayer player : players) server.getPlayerList().remove(player);
                for (EmbeddedChannel channel : channels) channel.finishAndReleaseAll();
                server.halt(false);
            }
        }
    }

    private static void setup() throws Exception {
        plugin.getShops().globalShops.clear();
        plugin.getShops().npcShops.clear();
        old = plugin.getShops().addNamedShop("session-shop");
        item = new NPCShopItem();
        item.setDisplayItem(new ItemStack(Items.DIAMOND));
        item.getCost().add(new ItemAction(new ItemStack(Items.EMERALD)));
        item.getResult().add(new ItemAction(new ItemStack(Items.DIAMOND)));
        old.getOrCreatePage(0).setItem(0, item);
        old.display(players.get(0)); chest = players.get(0).containerMenu;
        check(chest instanceof CitizensMenuContainer, "chest_open");
        chestMenu = menu(chest);
        old.displayEditor(null, players.get(1)); editorMenu = menu(players.get(1).containerMenu);
        check(players.get(1).containerMenu instanceof CitizensMenuContainer, "editor_open");
        old.setShopType(ShopType.TRADER);
        old.display(players.get(2)); trader = players.get(2).containerMenu;
        check(trader instanceof MerchantMenu && trader.stillValid(players.get(2)), "trader_open_and_valid");
        trader.getSlot(0).set(new ItemStack(Items.EMERALD, 5));
        plugin.storeNPCs();
    }

    private static void failedReload() throws Exception {
        Path file = plugin.getDataFolder().toPath().resolve("shops.yml");
        byte[] original = Files.readAllBytes(file);
        try {
            Files.writeString(file, "global: [broken");
            try { plugin.reload(); throw new AssertionError("bad_reload_succeeded"); }
            catch (NPCLoadException expected) { check(true, "failed_reload_reported"); }
            check(old.isActive() && players.get(0).containerMenu == chest && players.get(2).containerMenu == trader,
                    "failed_reload_preserves_sessions");
            check(trader.getSlot(0).getItem().getCount() == 5, "failed_reload_preserves_payment");
        } finally { Files.write(file, original); plugin.getShops().loadFromDisk(); }
    }

    private static void validTradeAndReentry() {
        ((MerchantMenu) trader).setSelectionHint(0);
        trader.clicked(2, 0, ClickType.PICKUP, players.get(2));
        check(count(players.get(2), Items.DIAMOND) == 1 && trader.getSlot(0).getItem().getCount() == 4,
                "live_merchant_trade_works");
        ServerPlayer buyer = players.get(0);
        buyer.getInventory().setItem(0, new ItemStack(Items.EMERALD, 2));
        chest.clicked(0, 0, ClickType.PICKUP, buyer);
        check(count(buyer, Items.DIAMOND) == 1 && count(buyer, Items.EMERALD) == 1, "live_chest_trade_works");
        NPCShopAction probe = new NPCShopAction() {
            @Override public String describe() { return "reload during trade"; }
            @Override public int getMaxRepeats(net.minecraft.world.entity.Entity entity, InventoryMultiplexer inventory) { return -1; }
            @Override public Transaction take(NPCShopStorage storage, net.minecraft.world.entity.Entity entity, InventoryMultiplexer inventory, int repeats) { return grant(storage, entity, inventory, repeats); }
            @Override public Transaction grant(NPCShopStorage storage, net.minecraft.world.entity.Entity entity, InventoryMultiplexer inventory, int repeats) {
                return Transaction.create(() -> true, () -> {
                    try { plugin.reload(); throw new AssertionError("transaction_reload_succeeded"); }
                    catch (NPCLoadException expected) { reentry++; }
                }, () -> {});
            }
        };
        item.getResult().add(probe);
        chest.clicked(0, 0, ClickType.PICKUP, buyer);
        item.getResult().remove(probe);
        check(reentry == 1 && old.isActive() && plugin.getShops().getGlobalShop("session-shop") == old,
                "reload_during_trade_rejected_before_replacement");
        check(count(buyer, Items.DIAMOND) == 2 && count(buyer, Items.EMERALD) == 0, "rejected_reload_keeps_transaction_coherent");
    }

    private static void verifyRetired() {
        check(!old.isActive() && plugin.getShops().getGlobalShop("session-shop") != old, "old_shop_retired_after_publication");
        NPCShop current = plugin.getShops().getGlobalShop("session-shop");
        plugin.getShops().deleteShop(old);
        check(plugin.getShops().getGlobalShop("session-shop") == current, "stale_deletion_preserves_replacement");
        check(players.stream().allMatch(p -> p.containerMenu == p.inventoryMenu), "all_shop_screens_closed");
        check(count(players.get(2), Items.EMERALD) == 4, "merchant_payment_returned_once");
        check(!trader.stillValid(players.get(2)), "old_merchant_invalid");
        int diamonds = count(players.get(0), Items.DIAMOND);
        players.get(0).getInventory().add(new ItemStack(Items.EMERALD));
        chest.clicked(0, 0, ClickType.PICKUP, players.get(0));
        item.onClick(old, old.getStorage(), players.get(0), new InventoryMultiplexer(players.get(0).getInventory()), false, true);
        trader.clicked(2, 0, ClickType.PICKUP, players.get(2));
        check(count(players.get(0), Items.DIAMOND) == diamonds && count(players.get(0), Items.EMERALD) == 1,
                "stale_chest_and_direct_trade_rejected");
        check(count(players.get(2), Items.EMERALD) == 4, "stale_merchant_does_not_duplicate_payment");
        chestMenu.present(players.get(0)); editorMenu.present(players.get(1)); old.display(players.get(2));
        check(players.stream().allMatch(p -> p.containerMenu == p.inventoryMenu), "retired_sessions_cannot_reopen");
        NeoForge.EVENT_BUS.post(new ServerChatEvent(players.get(1), "late answer", Component.literal("late answer")));
        check(answers == 0, "retired_chat_callback_not_called");
    }

    private static void throwingDisposal() {
        var page = new ThrowingPage();
        InventoryMenu menu = InventoryMenu.createSelfRegistered(page);
        int[] callbacks = { 0 };
        menu.addCloseCallback(() -> { callbacks[0]++; throw new IllegalStateException("expected menu close failure"); });
        menu.addCloseCallback(() -> callbacks[0]++);
        menu.present(players.get(0));
        AbstractContainerMenu container = players.get(0).containerMenu;
        try { menu.dispose(); throw new AssertionError("close_failure_missing"); }
        catch (IllegalStateException expected) { check(true, "close_error_reported"); }
        check(players.get(0).containerMenu == players.get(0).inventoryMenu && !container.stillValid(players.get(0)),
                "throwing_page_does_not_keep_screen_live");
        check(page.closed == 1 && callbacks[0] == 2, "all_close_callbacks_attempted");
        menu.dispose(); menu.present(players.get(0));
        check(page.closed == 1 && callbacks[0] == 2 && players.get(0).containerMenu == players.get(0).inventoryMenu,
                "disposal_idempotent_and_final");
    }

    private static void deletionAndShutdown() {
        NPCShop current = plugin.getShops().getGlobalShop("session-shop");
        current.setShopType(ShopType.DEFAULT);
        current.display(players.get(0));
        plugin.getShops().deleteShop(current);
        check(!current.isActive() && players.get(0).containerMenu == players.get(0).inventoryMenu,
                "shop_deletion_closes_view");
        NPCShop last = plugin.getShops().addNamedShop("shutdown-shop");
        last.getOrCreatePage(0).setItem(0, item);
        last.display(players.get(0));
        plugin.getShops().closeSessions();
        check(!last.isActive() && players.get(0).containerMenu == players.get(0).inventoryMenu,
                "shutdown_closes_shop_view");
        check(plugin.getShops().getGlobalShop("shutdown-shop") == last, "shutdown_keeps_definition_for_save");
        try { plugin.getShops().addNamedShop("late"); throw new AssertionError("shutdown_shop_reopened"); }
        catch (IllegalStateException expected) { check(true, "shutdown_rejects_new_shop"); }
        plugin.storeNPCs();
        check(true, "closed_shop_state_can_be_saved");
    }

    @Menu(title = "Disposal audit", type = InventoryType.CHEST, dimensions = {1, 9})
    public static final class ThrowingPage extends InventoryMenuPage {
        int closed;
        @Override public void initialise(MenuContext context) { }
        @Override public void onClose(ServerPlayer player) { closed++; throw new IllegalStateException("expected page close failure"); }
    }
    private static InventoryMenu menu(AbstractContainerMenu container) throws Exception {
        var field = CitizensMenuContainer.class.getDeclaredField("menu"); field.setAccessible(true); return (InventoryMenu) field.get(container);
    }
    private static int count(ServerPlayer player, Item item) {
        int total = 0; for (ItemStack stack : player.getInventory().items) if (stack.is(item)) total += stack.getCount(); return total;
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++; LoggerFactory.getLogger("citizens").info("[SHOPSESSIONAUDIT] PASS {}", label);
    }
    private static ServerPlayer admit(MinecraftServer server, String name) {
        ServerPlayer who = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) { connection.configurePacketHandler(channel.pipeline()); }
        });
        NetworkRegistry.configureMockConnection(connection);
        var cookie = new CommonListenerCookie(who.getGameProfile(), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
        server.getPlayerList().placeNewPlayer(connection, who, cookie); channels.add(channel); return who;
    }
}
