package net.citizensnpcs.trait.shop;

import java.nio.file.*;
import java.util.*;
import com.mojang.authlib.GameProfile;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemLore;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class ItemReceiptRuntimeAudit {
    private static ServerPlayer player;
    private static EmbeddedChannel channel;
    private static boolean done;
    private static int passed;

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done || event.getServer().getTickCount() < 10) return;
        done = true;
        try {
            check(Files.isRegularFile(Path.of("item-receipt-audit-fixture.txt")), "isolated_fixture");
            player = admit(event.getServer());
            failedPayment(); merchantTransfer(); failedReward();
            LoggerFactory.getLogger("citizens").info("[ITEMRECEIPTAUDIT] COMPLETE {} checks", passed);
        } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[ITEMRECEIPTAUDIT] FAILED", failure); }
        finally {
            if (player != null) event.getServer().getPlayerList().remove(player);
            if (channel != null) channel.finishAndReleaseAll();
            event.getServer().halt(false);
        }
    }
    private static ItemStack named(Item item, String name, String lore) {
        ItemStack result = new ItemStack(item); result.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        if (lore != null) result.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(lore))));
        return result;
    }
    private static ItemAction loose(ItemStack template) {
        ItemAction action = new ItemAction(template); action.metaFilter = List.of("custom_name"); action.requireUndamaged = false; return action;
    }
    private static boolean holds(ItemStack expected) {
        return player.getInventory().items.stream().anyMatch(stack -> ItemStack.matches(stack, expected));
    }
    private static void failedPayment() {
        player.getInventory().clearContent();
        ItemStack paid = named(Items.DIAMOND_SWORD, "Token", "Player data"); paid.setDamageValue(7); paid.set(DataComponents.REPAIR_COST, 9);
        player.getInventory().setItem(0, paid.copy());
        NPCShop shop = new NPCShop("receipt-cost"); shop.getStorage().setUnlimited(false);
        NPCShopItem item = new NPCShopItem(); item.setDisplayItem(new ItemStack(Items.BOOK)); item.timesPurchasable = 1;
        item.getCost().add(loose(named(Items.DIAMOND_SWORD, "Token", null))); item.getResult().add(new FailedAction());
        shop.getOrCreatePage(0).setItem(0, item); shop.display(player);
        player.containerMenu.clicked(0, 0, ClickType.PICKUP, player);
        check(holds(paid), "failed_trade_returns_actual_cost_components");
        check(shop.getStorage().getInventory().isEmpty() && item.purchases.isEmpty(), "failed_trade_keeps_stock_and_counter_empty");
        player.containerMenu.clicked(0, 0, ClickType.PICKUP, player);
        check(player.getInventory().items.stream().filter(s -> !s.isEmpty()).count() == 1 && holds(paid), "retry_does_not_duplicate_or_normalize_cost");
        shop.retire();
    }
    private static void merchantTransfer() {
        player.getInventory().clearContent();
        ItemStack reward = named(Items.DIAMOND_SWORD, "Reward", "Stock data"); reward.setDamageValue(4); reward.set(DataComponents.REPAIR_COST, 3);
        ItemStack coin = named(Items.EMERALD, "Coin", "Mint data"); coin.setCount(4);
        NPCShop shop = new NPCShop("receipt-merchant"); shop.setShopType(ShopType.TRADER); shop.getStorage().setUnlimited(false);
        shop.getStorage().setInventory(new ArrayList<>(List.of(reward.copy())));
        NPCShopItem item = new NPCShopItem(); item.setDisplayItem(named(Items.DIAMOND_SWORD, "Reward", null));
        item.getCost().add(loose(named(Items.EMERALD, "Coin", null)));
        item.getResult().add(loose(named(Items.DIAMOND_SWORD, "Reward", null)));
        shop.getOrCreatePage(0).setItem(0, item); shop.display(player);
        MerchantMenu menu = (MerchantMenu) player.containerMenu; menu.getSlot(0).set(coin.copy()); menu.setSelectionHint(0);
        menu.clicked(2, 0, ClickType.PICKUP, player);
        check(holds(reward), "merchant_delivers_actual_stock_components");
        check(shop.getStorage().getInventory().size() == 1 && ItemStack.matches(shop.getStorage().getInventory().getFirst(), coin.copyWithCount(1)),
                "merchant_stock_receives_actual_payment");
        check(ItemStack.matches(menu.getSlot(0).getItem(), coin.copyWithCount(3)), "merchant_remainder_keeps_components");
        shop.retire(); check(holds(coin.copyWithCount(3)), "merchant_close_returns_exact_remainder");
    }
    private static void failedReward() {
        player.getInventory().clearContent();
        ItemStack unrelated = named(Items.DIAMOND_SWORD, "Reward", "Existing player item"); player.getInventory().setItem(0, unrelated.copy());
        player.getInventory().setItem(1, new ItemStack(Items.EMERALD));
        NPCShop shop = new NPCShop("receipt-reward"); NPCShopItem item = new NPCShopItem(); item.setDisplayItem(new ItemStack(Items.BOOK));
        item.getCost().add(new ItemAction(new ItemStack(Items.EMERALD)));
        item.getResult().add(loose(named(Items.DIAMOND_SWORD, "Reward", null))); item.getResult().add(new FailedAction());
        shop.getOrCreatePage(0).setItem(0, item); shop.display(player); player.containerMenu.clicked(0, 0, ClickType.PICKUP, player);
        check(holds(unrelated), "reward_rollback_keeps_unrelated_matching_item");
        check(holds(new ItemStack(Items.EMERALD)), "failed_reward_refunds_currency");
        check(player.getInventory().items.stream().filter(s -> !s.isEmpty()).count() == 2, "reward_rollback_has_no_extra_template");
        shop.retire();
    }
    private static final class FailedAction extends NPCShopAction {
        @Override public String describe() { return "unavailable fixture result"; }
        @Override public int getMaxRepeats(Entity entity, InventoryMultiplexer inventory) { return -1; }
        @Override public Transaction take(NPCShopStorage stock, Entity entity, InventoryMultiplexer inventory, int repeats) { return Transaction.fail(); }
        @Override public Transaction grant(NPCShopStorage stock, Entity entity, InventoryMultiplexer inventory, int repeats) { return Transaction.fail(); }
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); passed++; LoggerFactory.getLogger("citizens").info("[ITEMRECEIPTAUDIT] PASS {}", label); }
    private static ServerPlayer admit(MinecraftServer server) {
        ServerPlayer who = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), "ReceiptTrader"), ClientInformation.createDefault());
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
