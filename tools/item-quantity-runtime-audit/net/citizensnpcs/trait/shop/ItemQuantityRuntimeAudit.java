package net.citizensnpcs.trait.shop;

import java.nio.file.*;
import java.util.*;
import com.mojang.authlib.GameProfile;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.minecraft.network.*;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class ItemQuantityRuntimeAudit {
    private static ServerPlayer player;
    private static EmbeddedChannel channel;
    private static boolean done;
    private static int passed;

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done || event.getServer().getTickCount() < 10) return;
        done = true;
        try {
            check(Files.isRegularFile(Path.of("item-quantity-audit-fixture.txt")), "isolated_fixture");
            player = admit(event.getServer());
            batch(false); batch(true); splitAndRefund();
            LoggerFactory.getLogger("citizens").info("[ITEMQUANTITYAUDIT] COMPLETE {} checks", passed);
        } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[ITEMQUANTITYAUDIT] FAILED", failure); }
        finally {
            if (player != null) event.getServer().getPlayerList().remove(player);
            if (channel != null) channel.finishAndReleaseAll();
            event.getServer().halt(false);
        }
    }

    private static void batch(boolean merchant) {
        player.closeContainer(); player.getInventory().clearContent();
        NPCShop shop = new NPCShop("quantity-" + merchant); shop.setShopType(merchant ? ShopType.TRADER : ShopType.DEFAULT);
        shop.getStorage().setUnlimited(false); shop.getStorage().setInventorySizeLimit(2);
        shop.getStorage().setInventory(new ArrayList<>(List.of(new ItemStack(Items.DIAMOND, 10))));
        NPCShopItem item = new NPCShopItem(); item.setDisplayItem(new ItemStack(Items.DIAMOND));
        item.maxRepeatsOnShiftClick = true;
        item.getCost().add(new ItemAction(new ItemStack(Items.EMERALD, 2), new ItemStack(Items.EMERALD, 3)));
        item.getResult().add(new ItemAction(new ItemStack(Items.DIAMOND)));
        shop.getOrCreatePage(0).setItem(0, item); shop.display(player);
        AbstractContainerMenu menu = player.containerMenu;
        if (merchant) {
            check(menu instanceof MerchantMenu, "merchant_menu");
            menu.getSlot(0).set(new ItemStack(Items.EMERALD, 8)); menu.getSlot(1).set(new ItemStack(Items.EMERALD, 12));
            ((MerchantMenu) menu).setSelectionHint(0); menu.clicked(2, 0, ClickType.QUICK_MOVE, player);
            check(menu.getSlot(0).getItem().isEmpty() && menu.getSlot(1).getItem().isEmpty(), "both_payment_slots_consumed_exactly");
        } else {
            player.getInventory().setItem(0, new ItemStack(Items.EMERALD, 20));
            menu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        }
        check(count(Items.DIAMOND) == 4 && count(Items.EMERALD) == 0, "four_repeats_delivered_" + merchant);
        check(stockCount(shop, Items.DIAMOND) == 6 && stockCount(shop, Items.EMERALD) == 20, "stock_quantities_conserved_" + merchant);
        check(shop.getStorage().getInventory().size() == 2, "exact_stock_slot_limit_" + merchant);
        shop.requireIdle(); check(true, "batch_releases_scope_" + merchant);
        shop.retire();
    }

    private static void splitAndRefund() {
        player.getInventory().clearContent();
        InventoryMultiplexer inventory = new InventoryMultiplexer(player.getInventory());
        ItemAction action = new ItemAction(new ItemStack(Items.EMERALD, 100));
        var transaction = action.grant(new NPCShopStorage(), player, inventory, 1);
        check(transaction.isPossible(), "oversized_quantity_has_capacity"); transaction.run();
        check(count(Items.EMERALD) == 100, "all_one_hundred_items_delivered");
        check(player.getInventory().items.stream().allMatch(s -> s.isEmpty() || s.getCount() <= s.getMaxStackSize()), "no_illegal_native_overstack");
        transaction.rollback(); check(count(Items.EMERALD) == 0, "split_quantity_refunds_exactly");
        check(!new ItemAction(new ItemStack(Items.EMERALD, 1 << 30)).take(new NPCShopStorage(), player, inventory, 4).isPossible(),
                "overflow_cannot_make_free_cost");
    }

    private static int count(Item item) { return player.getInventory().items.stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum(); }
    private static int stockCount(NPCShop shop, Item item) { return shop.getStorage().getInventory().stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum(); }
    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); passed++; LoggerFactory.getLogger("citizens").info("[ITEMQUANTITYAUDIT] PASS {}", name); }
    private static ServerPlayer admit(MinecraftServer server) {
        ServerPlayer who = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), "QuantityBuyer"), ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) { connection.configurePacketHandler(channel.pipeline()); }
        });
        NetworkRegistry.configureMockConnection(connection);
        var cookie = new CommonListenerCookie(who.getGameProfile(), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
        server.getPlayerList().placeNewPlayer(connection, who, cookie); return who;
    }
}
