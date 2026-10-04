package net.citizensnpcs.trait.shop;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import com.mojang.authlib.GameProfile;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.Citizens;
import net.citizensnpcs.api.gui.CitizensMenuContainer;
import net.citizensnpcs.api.gui.InventoryMenu;
import net.citizensnpcs.api.npc.*;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.util.*;
import net.citizensnpcs.trait.ShopTrait;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.minecraft.network.*;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class PrivateShopRuntimeAudit {
    private static final List<ServerPlayer> players = new ArrayList<>();
    private static final List<EmbeddedChannel> channels = new ArrayList<>();
    private static Citizens plugin;
    private static NPCDataStore original;
    private static Store first, target;
    private static NPCRegistry oldRegistry, named;
    private static NPC npc, namedNPC;
    private static NPCShop shared;
    private static NPCShopItem item;
    private static NPCShopStorage oldStock, namedStock, partialStock;
    private static AbstractContainerMenu chest, trader, globalView, namedView;
    private static int passed;
    private static boolean done;

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done || event.getServer().getTickCount() < 10) return;
        done = true; plugin = Citizens.getInstance();
        try {
            check(Files.isRegularFile(Path.of("private-shop-audit-fixture.txt")), "isolated_fixture");
            for (int i = 0; i < 6; i++) players.add(admit(event.getServer(), "PrivateShop" + i));
            setup(); failedLoad(); transactionGuard(); openPrivateViews();
            editPendingStock(); failedSave(); openPrivateViews(); successfulSwitch();
            LoggerFactory.getLogger("citizens").info("[PRIVATESHOPAUDIT] COMPLETE {} checks", passed);
        } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[PRIVATESHOPAUDIT] FAILED", failure); }
        finally {
            try {
                if (first != null) first.failSave = false;
                if (namedNPC != null) namedNPC.destroy();
                if (named != null) plugin.removeNamedNPCRegistry(named.getName());
                if (original != null) plugin.setDefaultNPCDataStore(original);
                for (ServerPlayer player : players) event.getServer().getPlayerList().remove(player);
                for (EmbeddedChannel channel : channels) channel.finishAndReleaseAll();
            } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[PRIVATESHOPAUDIT] FAILED cleanup", failure); }
            event.getServer().halt(false);
        }
    }

    private static void setup() {
        original = plugin.getDefaultNPCDataStore(); first = new Store(); plugin.setDefaultNPCDataStore(first);
        oldRegistry = plugin.getNPCRegistry(); npc = oldRegistry.createNPC(EntityType.PIG, "PrivateOwner");
        oldStock = npc.getOrAddTrait(ShopTrait.class).getStorage();
        oldStock.setUnlimited(false); oldStock.setBalance(100);
        oldStock.setInventory(new ArrayList<>(List.of(new ItemStack(Items.DIAMOND, 10))));
        named = plugin.createNamedNPCRegistry("private-shop-unrelated", new MemoryNPCDataStore());
        namedNPC = named.createNPC(EntityType.PIG, npc.getUniqueId(), npc.getId(), "Unrelated");
        namedStock = namedNPC.getOrAddTrait(ShopTrait.class).getStorage();
        shared = plugin.getShops().addNamedShop("shared-private-audit"); shared.setShopType(ShopType.DEFAULT);
        item = new NPCShopItem(); item.setDisplayItem(new ItemStack(Items.DIAMOND));
        item.getCost().add(new ItemAction(new ItemStack(Items.EMERALD)));
        item.getResult().add(new ItemAction(new ItemStack(Items.DIAMOND)));
        shared.getOrCreatePage(0).setItem(0, item);
        plugin.storeNPCs();
        target = copy(first); target.records.get(npc.getId()).setDouble("traits.shop.storage.balance", 25);
        openPrivateViews();
        shared.display(players.get(3)); globalView = players.get(3).containerMenu;
        shared.display(namedStock, players.get(4)); namedView = players.get(4).containerMenu;
        check(globalView instanceof MerchantMenu && namedView instanceof MerchantMenu, "unrelated_views_open");
    }

    private static void openPrivateViews() {
        shared.setShopType(ShopType.DEFAULT); shared.display(oldStock, players.get(0)); chest = players.get(0).containerMenu;
        shared.displayEditor(npc.getOrAddTrait(ShopTrait.class), players.get(1));
        shared.setShopType(ShopType.TRADER); shared.display(oldStock, players.get(2)); trader = players.get(2).containerMenu;
        players.get(2).getInventory().clearContent(); trader.getSlot(0).set(new ItemStack(Items.EMERALD, 5));
        check(chest instanceof CitizensMenuContainer && trader instanceof MerchantMenu, "private_views_open_" + passed);
    }

    private static void failedLoad() {
        Store broken = copy(target);
        broken.afterLoad = registry -> {
            partialStock = registry.getById(npc.getId()).getOrAddTrait(ShopTrait.class).getStorage();
            shared.display(partialStock, players.get(5));
            throw new IllegalStateException("expected private candidate failure");
        };
        reject(() -> plugin.setDefaultNPCDataStore(broken), "failed_provider_rejected");
        check(plugin.getNPCRegistry() == oldRegistry && oldStock.isActive(), "failed_load_keeps_previous_binding_and_stock");
        check(players.get(0).containerMenu == chest && players.get(2).containerMenu == trader, "failed_load_keeps_previous_views");
        check(!partialStock.isActive() && players.get(5).containerMenu == players.get(5).inventoryMenu,
                "failed_candidate_closes_only_its_private_view");
        check(shared.isActive() && players.get(3).containerMenu == globalView && players.get(4).containerMenu == namedView,
                "failed_candidate_preserves_shared_and_named_views");
    }

    private static void transactionGuard() {
        Store rejected = copy(target);
        NPCShopAction probe = new NPCShopAction() {
            @Override public String describe() { return "store replacement probe"; }
            @Override public int getMaxRepeats(net.minecraft.world.entity.Entity entity, InventoryMultiplexer inventory) { return -1; }
            @Override public Transaction take(NPCShopStorage stock, net.minecraft.world.entity.Entity entity, InventoryMultiplexer inventory, int count) { return grant(stock, entity, inventory, count); }
            @Override public Transaction grant(NPCShopStorage stock, net.minecraft.world.entity.Entity entity, InventoryMultiplexer inventory, int count) {
                return Transaction.create(() -> true, () -> reject(() -> plugin.setDefaultNPCDataStore(rejected), "replacement_in_private_trade_rejected"), () -> {});
            }
        };
        item.getResult().add(probe); players.get(0).getInventory().setItem(0, new ItemStack(Items.EMERALD));
        chest.clicked(0, 0, ClickType.PICKUP, players.get(0)); item.getResult().remove(probe);
        check(rejected.loads == 0 && plugin.getNPCRegistry() == oldRegistry, "trade_reentry_rejected_before_provider_load");
        check(count(oldStock, Items.DIAMOND) == 9, "trade_completes_against_original_stock");
    }

    private static void editPendingStock() throws Exception {
        var field = CitizensMenuContainer.class.getDeclaredField("menu"); field.setAccessible(true);
        InventoryMenu editor = (InventoryMenu) field.get(players.get(1).containerMenu);
        editor.transition(oldStock.createInventoryViewer(players.get(1)));
        players.get(1).containerMenu.getSlot(1).set(new ItemStack(Items.GOLD_INGOT, 3));
        check(count(oldStock, Items.GOLD_INGOT) == 0, "editor_has_pending_stock_changes");
    }

    private static void failedSave() {
        first.failSave = true;
        reject(() -> plugin.setDefaultNPCDataStore(copy(target)), "failed_save_rejected");
        check(plugin.getNPCRegistry() == oldRegistry && oldStock.isActive(), "save_failure_restores_stock_admission");
        check(count(oldStock, Items.GOLD_INGOT) == 3, "closed_editor_changes_remain_in_live_stock");
        check(players.get(0).containerMenu == players.get(0).inventoryMenu && players.get(1).containerMenu == players.get(1).inventoryMenu,
                "pre_save_private_views_closed");
        check(players.get(3).containerMenu == globalView && players.get(4).containerMenu == namedView, "save_failure_preserves_unrelated_views");
        first.failSave = false;
    }

    private static void successfulSwitch() {
        plugin.setDefaultNPCDataStore(target);
        NPCShopStorage current = plugin.getNPCRegistry().getById(npc.getId()).getOrAddTrait(ShopTrait.class).getStorage();
        check(current != oldStock && current.isActive() && current.getBalance() == 25, "replacement_has_its_own_stock");
        check(!oldStock.isActive() && shared.isActive(), "old_stock_retired_without_retiring_shared_shop");
        check(players.subList(0, 3).stream().allMatch(p -> p.containerMenu == p.inventoryMenu), "all_old_private_views_closed");
        check(players.get(3).containerMenu == globalView && globalView.stillValid(players.get(3))
                && players.get(4).containerMenu == namedView && namedStock.isActive(), "unrelated_stock_views_remain_valid");
        NPCShopStorage saved = PersistenceLoader.loadStrict(new NPCShopStorage(), first.records.get(npc.getId()).getRelative("traits.shop.storage"));
        check(count(saved, Items.GOLD_INGOT) == 3 && count(saved, Items.DIAMOND) == 9, "old_store_captures_editor_commit_before_retirement");
        check(count(current, Items.GOLD_INGOT) == 0 && count(current, Items.DIAMOND) == 10, "new_store_does_not_receive_old_editor_changes");
        int diamonds = count(oldStock, Items.DIAMOND);
        players.get(0).getInventory().setItem(0, new ItemStack(Items.EMERALD));
        item.onClick(shared, oldStock, players.get(0), new InventoryMultiplexer(players.get(0).getInventory()), false, true);
        check(count(oldStock, Items.DIAMOND) == diamonds, "retired_stock_rejects_direct_trade");
        shared.display(oldStock, players.get(0));
        check(players.get(0).containerMenu == players.get(0).inventoryMenu, "old_stock_cannot_reopen_view");
        shared.display(current, players.get(0));
        check(players.get(0).containerMenu instanceof MerchantMenu, "new_stock_can_open_view");
    }

    private static Store copy(Store source) { Store result = new Store(); source.records.forEach((id, key) -> result.records.put(id, key.copy())); return result; }
    private static int count(NPCShopStorage stock, Item item) { return stock.getInventory().stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum(); }
    private static void reject(Runnable action, String label) { try { action.run(); } catch (IllegalStateException expected) { check(true, label); return; } throw new AssertionError(label); }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); passed++; LoggerFactory.getLogger("citizens").info("[PRIVATESHOPAUDIT] PASS {}", label); }
    private static final class Store extends MemoryNPCDataStore {
        final Map<Integer, DataKey> records = new LinkedHashMap<>(); boolean failSave; int loads; Consumer<NPCRegistry> afterLoad;
        @Override public void loadInto(NPCRegistry registry) {
            loads++;
            records.forEach((id, key) -> { NPC npc = registry.createNPC(EntityType.PIG, UUID.fromString(key.getString("uuid")), id, key.getString("name")); npc.load(key.copy()); });
            if (afterLoad != null) afterLoad.accept(registry);
        }
        @Override public void storeAll(NPCRegistry registry) {
            if (failSave) throw new IllegalStateException("expected private stock save failure");
            for (NPC npc : registry) { DataKey key = new MemoryDataKey(); npc.saveSnapshot(key); records.put(npc.getId(), key); }
        }
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
