package net.citizensnpcs.trait.shop;

import java.nio.file.*;
import java.util.*;
import com.mojang.authlib.GameProfile;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.Citizens;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.trait.ShopTrait;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.minecraft.network.*;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class PurchaseNotifyRuntimeAudit {
    private static final List<ServerPlayer> players = new ArrayList<>();
    private static final List<EmbeddedChannel> channels = new ArrayList<>();
    private static NPCShop receiptShop, closingShop;
    private static NPCShop deletingShop;
    private static NPC deletingNPC;
    private static int deletingEvents;
    private static int receiptEvents, closingEvents, passed, ready;
    private static boolean receiptCountSeen, paymentSeen, done;
    private static boolean fullPending;
    private static int droppedEmeralds, droppedDiamonds;
    private static final Set<UUID> originalDrops = new HashSet<>();
    private static NPCShop fullSuccessShop;
    private static boolean escrowDeliveredBeforeEvent;

    @SubscribeEvent public static void purchase(NPCShopPurchaseEvent event) {
        if (event.getShop() == fullSuccessShop) {
            escrowDeliveredBeforeEvent = dropped(Items.DIAMOND) - droppedDiamonds == 1;
        }
        if (event.getShop() == deletingShop) {
            deletingEvents++;
            deletingShop.requireIdle();
            deletingNPC.destroy();
        }
        if (event.getShop() == receiptShop) {
            receiptEvents++;
            receiptCountSeen = event.getItem().purchases.getOrDefault(event.getPlayer().getUUID(), 0) == 1;
            throw new IllegalStateException("expected receipt event failure");
        }
        if (event.getShop() == closingShop) {
            closingEvents++;
            paymentSeen = event.getPlayer().containerMenu.getSlot(0).getItem().getCount() == 4;
            event.getPlayer().closeContainer();
            throw new IllegalStateException("expected closing event failure");
        }
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done || event.getServer().getTickCount() < 10) return;
        try {
            if (ready == 0) {
                check(Files.isRegularFile(Path.of("purchase-notify-audit-fixture.txt")), "isolated_fixture");
                players.add(admit(event.getServer(), "ReceiptBuyer")); players.add(admit(event.getServer(), "ClosingBuyer"));
                receiptFailure(); closingEvent(); deletionAfterCommit(); navigationActions();
                ready = event.getServer().getTickCount() + 4;
            } else if (event.getServer().getTickCount() >= ready && !fullPending) {
                check(players.get(0).containerMenu == players.get(0).inventoryMenu, "rolled_back_navigation_does_not_open_target");
                check(players.get(1).containerMenu != players.get(1).inventoryMenu
                        && players.get(1).containerMenu.getSlot(0).getItem().is(Items.BOOK), "successful_navigation_opens_target");
                check(count(players.get(0), Items.EMERALD) == 5 && count(players.get(1), Items.EMERALD) == 4,
                        "navigation_preserves_refunds_after_dispatch");
                fullInventoryCases(); fullPending = true; ready = event.getServer().getTickCount() + 4;
            } else if (event.getServer().getTickCount() >= ready) {
                check(dropped(Items.EMERALD) - droppedEmeralds == 9 && dropped(Items.DIAMOND) - droppedDiamonds == 1,
                        "full_inventory_returns_refunds_and_only_committed_reward");
                check(players.get(0).containerMenu == players.get(0).inventoryMenu
                        && players.get(1).containerMenu.getSlot(0).getItem().is(Items.BOOK), "full_inventory_navigation_outcomes");
                check(players.stream().allMatch(p -> count(p, Items.EMERALD) == 0 && count(p, Items.DIAMOND) == 0),
                        "full_inventory_uses_vanilla_drop_return");
                check(escrowDeliveredBeforeEvent, "purchase_event_sees_final_escrow_delivery");
                done = true; LoggerFactory.getLogger("citizens").info("[PURCHASENOTIFYAUDIT] COMPLETE {} checks", passed);
            }
        } catch (Throwable failure) { done = true; LoggerFactory.getLogger("citizens").error("[PURCHASENOTIFYAUDIT] FAILED", failure); }
        finally {
            if (done) {
                if (fullPending) for (ItemEntity entity : drops()) if (!originalDrops.contains(entity.getUUID())) entity.discard();
                for (ServerPlayer player : players) event.getServer().getPlayerList().remove(player);
                for (EmbeddedChannel channel : channels) channel.finishAndReleaseAll();
                event.getServer().halt(false);
            }
        }
    }

    private static NPCShopItem item() {
        NPCShopItem item = new NPCShopItem(); item.setDisplayItem(new ItemStack(Items.DIAMOND));
        item.getCost().add(new ItemAction(new ItemStack(Items.EMERALD)));
        item.timesPurchasable = 1; item.globalTimesPurchasable = 1; return item;
    }

    private static void receiptFailure() {
        receiptShop = new NPCShop("receipt-failure"); NPCShopItem item = item();
        ThrowingDescription result = new ThrowingDescription(); item.getResult().add(result); item.resultMessage = "<result>";
        receiptShop.getOrCreatePage(0).setItem(0, item);
        ServerPlayer buyer = players.get(0); buyer.getInventory().setItem(0, new ItemStack(Items.EMERALD, 2));
        receiptShop.display(buyer); AbstractContainerMenu menu = buyer.containerMenu; result.fail = true;
        menu.clicked(0, 0, ClickType.PICKUP, buyer);
        check(item.purchases.getOrDefault(buyer.getUUID(), 0) == 1, "receipt_error_keeps_purchase_counts");
        check(count(buyer, Items.EMERALD) == 1 && count(buyer, Items.DIAMOND) == 1, "receipt_error_keeps_committed_items");
        check(receiptEvents == 1 && receiptCountSeen, "event_attempted_after_message_failure_and_sees_commit");
        check(buyer.containerMenu == menu, "notification_failure_does_not_close_chest");
        menu.clicked(0, 0, ClickType.PICKUP, buyer);
        check(count(buyer, Items.DIAMOND) == 1 && count(buyer, Items.EMERALD) == 1 && receiptEvents == 1,
                "notification_failure_cannot_bypass_purchase_limit");
        receiptShop.requireIdle(); receiptShop.getStorage().requireIdle(); check(true, "notification_failure_releases_trade_scopes");
    }

    private static void closingEvent() {
        closingShop = new NPCShop("closing-event"); closingShop.setShopType(ShopType.TRADER);
        NPCShopItem item = item(); item.getResult().add(new ItemAction(new ItemStack(Items.DIAMOND)));
        closingShop.getOrCreatePage(0).setItem(0, item);
        ServerPlayer buyer = players.get(1); closingShop.display(buyer); AbstractContainerMenu menu = buyer.containerMenu;
        menu.getSlot(0).set(new ItemStack(Items.EMERALD, 5)); ((MerchantMenu) menu).setSelectionHint(0);
        menu.clicked(2, 0, ClickType.PICKUP, buyer);
        check(paymentSeen && closingEvents == 1, "native_payment_committed_before_event_close");
        check(count(buyer, Items.EMERALD) == 4 && count(buyer, Items.DIAMOND) == 1, "event_close_returns_only_remaining_payment");
        check(buyer.containerMenu == buyer.inventoryMenu && !menu.stillValid(buyer), "event_closed_menu_stays_closed");
        check(item.purchases.getOrDefault(buyer.getUUID(), 0) == 1, "throwing_event_keeps_purchase_record");
        menu.clicked(2, 0, ClickType.PICKUP, buyer);
        check(count(buyer, Items.EMERALD) == 4 && count(buyer, Items.DIAMOND) == 1, "stale_payment_click_cannot_duplicate");
        ServerPlayer other = players.get(0); other.getInventory().clearContent(); other.getInventory().setItem(0, new ItemStack(Items.EMERALD));
        item.onClick(closingShop, closingShop.getStorage(), other, new InventoryMultiplexer(other.getInventory()), false, true);
        check(count(other, Items.EMERALD) == 1 && count(other, Items.DIAMOND) == 0 && closingEvents == 1,
                "global_purchase_limit_survives_notification_failure");
    }

    private static void navigationActions() {
        NPCShop target = Citizens.getInstance().getShops().addNamedShop("notify-target");
        NPCShopItem targetItem = new NPCShopItem(); targetItem.setDisplayItem(new ItemStack(Items.BOOK));
        target.getOrCreatePage(0).setItem(0, targetItem);
        OpenShopAction navigation = new OpenShopAction("notify-target");
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer player = players.get(i); player.getInventory().clearContent();
            NPCShop origin = new NPCShop("navigation-" + i); origin.setShopType(ShopType.TRADER);
            NPCShopItem item = item(); item.getResult().add(navigation);
            if (i == 0) item.getResult().add(new FailedAction());
            else item.getResult().add(new ItemAction(new ItemStack(Items.DIAMOND)));
            origin.getOrCreatePage(0).setItem(0, item); origin.display(player);
            MerchantMenu menu = (MerchantMenu) player.containerMenu;
            menu.getSlot(0).set(new ItemStack(Items.EMERALD, 5)); menu.setSelectionHint(0); menu.clicked(2, 0, ClickType.PICKUP, player);
            check(player.containerMenu == player.inventoryMenu, "navigation_closes_origin_" + i);
            check(count(player, Items.EMERALD) == (i == 0 ? 5 : 4) && count(player, Items.DIAMOND) == (i == 0 ? 0 : 1),
                    "navigation_inventory_and_rollback_coherent_" + i);
        }
    }

    private static void deletionAfterCommit() {
        deletingNPC = Citizens.getInstance().getNPCRegistry().createNPC(EntityType.PIG, "PostPurchaseRemoval");
        deletingShop = deletingNPC.getOrAddTrait(ShopTrait.class).getDefaultShop();
        NPCShopItem item = item(); item.getResult().add(new ItemAction(new ItemStack(Items.DIAMOND)));
        deletingShop.getOrCreatePage(0).setItem(0, item);
        ServerPlayer player = players.get(0); player.getInventory().clearContent();
        player.getInventory().setItem(0, new ItemStack(Items.EMERALD));
        deletingShop.display(player); player.containerMenu.clicked(0, 0, ClickType.PICKUP, player);
        check(deletingEvents == 1 && Citizens.getInstance().getNPCRegistry().getById(deletingNPC.getId()) == null,
                "post_commit_listener_can_remove_owner");
        check(count(player, Items.DIAMOND) == 1 && count(player, Items.EMERALD) == 0
                && item.purchases.getOrDefault(player.getUUID(), 0) == 1, "listener_removal_preserves_committed_purchase");
        check(player.containerMenu == player.inventoryMenu, "post_commit_removal_closes_owned_view");
    }

    private static void fullInventoryCases() {
        drops().forEach(entity -> originalDrops.add(entity.getUUID()));
        droppedEmeralds = dropped(Items.EMERALD); droppedDiamonds = dropped(Items.DIAMOND);
        fullPending = true;
        OpenShopAction navigation = new OpenShopAction("notify-target");
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer player = players.get(i); player.closeContainer(); player.getInventory().clearContent();
            for (int slot = 0; slot < player.getInventory().items.size(); slot++) player.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
            player.getInventory().armor.set(0, new ItemStack(Items.DIAMOND_BOOTS));
            player.getInventory().armor.set(1, new ItemStack(Items.DIAMOND_LEGGINGS));
            player.getInventory().armor.set(2, new ItemStack(Items.DIAMOND_CHESTPLATE));
            player.getInventory().armor.set(3, new ItemStack(Items.DIAMOND_HELMET));
            player.getInventory().offhand.set(0, new ItemStack(Items.SHIELD));
            NPCShop origin = new NPCShop("full-navigation-" + i); origin.setShopType(ShopType.TRADER);
            if (i == 1) fullSuccessShop = origin;
            NPCShopItem item = item(); item.getResult().add(navigation);
            item.getResult().add(new ItemAction(new ItemStack(Items.DIAMOND)));
            if (i == 0) item.getResult().add(new FailedAction());
            origin.getOrCreatePage(0).setItem(0, item); origin.display(player);
            MerchantMenu menu = (MerchantMenu) player.containerMenu;
            menu.getSlot(0).set(new ItemStack(Items.EMERALD, 5)); menu.setSelectionHint(0); menu.clicked(2, 0, ClickType.PICKUP, player);
            check(menu.getSlot(0).getItem().isEmpty() && menu.getSlot(1).getItem().isEmpty(), "closed_native_inputs_have_no_hidden_items_" + i);
            check(item.purchases.getOrDefault(player.getUUID(), 0) == i, "full_inventory_purchase_record_" + i);
        }
    }

    private static List<ItemEntity> drops() {
        return players.get(0).serverLevel().getEntitiesOfClass(ItemEntity.class, players.get(0).getBoundingBox().inflate(64));
    }
    private static int dropped(Item item) { return drops().stream().map(ItemEntity::getItem).filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum(); }

    private static final class ThrowingDescription extends ItemAction {
        boolean fail;
        ThrowingDescription() { super(new ItemStack(Items.DIAMOND)); }
        @Override public String describe() { if (fail) throw new IllegalStateException("expected receipt description failure"); return super.describe(); }
    }
    private static final class FailedAction extends NPCShopAction {
        @Override public String describe() { return "unavailable fixture result"; }
        @Override public int getMaxRepeats(Entity entity, InventoryMultiplexer inventory) { return -1; }
        @Override public Transaction take(NPCShopStorage stock, Entity entity, InventoryMultiplexer inventory, int repeats) { return Transaction.fail(); }
        @Override public Transaction grant(NPCShopStorage stock, Entity entity, InventoryMultiplexer inventory, int repeats) { return Transaction.fail(); }
    }
    private static int count(ServerPlayer player, Item item) { return player.getInventory().items.stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum(); }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); passed++; LoggerFactory.getLogger("citizens").info("[PURCHASENOTIFYAUDIT] PASS {}", label); }
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
