package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.citizensnpcs.api.gui.*;
import net.minecraft.network.*;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class MenuCollectionRuntimeAudit {
    private static boolean done;
    private static int passed;
    private static ServerPlayer player;
    private static EmbeddedChannel channel;
    private static InventoryMenu menu;
    private static Page page;
    private static boolean admitted;
    private static final List<ClientboundContainerSetContentPacket> content = new ArrayList<>();

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done || event.getServer().getTickCount() < 10) return;
        done = true;
        var server = event.getServer();
        try {
            check(Files.isRegularFile(Path.of("menu-collection-audit-fixture.txt")), "isolated_fixture");
            player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), "MenuAudit"), ClientInformation.createDefault());
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
                @Override protected void initChannel(Channel channel) {
                    connection.configurePacketHandler(channel.pipeline());
                    channel.pipeline().addLast("menu-capture", new ChannelOutboundHandlerAdapter() {
                        @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) throws Exception {
                            if (message instanceof ClientboundContainerSetContentPacket packet) {
                                RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
                                try {
                                    ClientboundContainerSetContentPacket.STREAM_CODEC.encode(buffer, packet);
                                    content.add(ClientboundContainerSetContentPacket.STREAM_CODEC.decode(buffer));
                                } finally { buffer.release(); }
                            }
                            super.write(ctx, message, promise);
                        }
                    });
                }
            });
            NetworkRegistry.configureMockConnection(connection);
            var cookie = new CommonListenerCookie(player.getGameProfile(), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
            connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
            server.getPlayerList().placeNewPlayer(connection, player, cookie);
            admitted = true;
            lockedAndEditable();
            orderingAndCapacity();
            preconditions();
            callbacks();
            stockEditor();
            retirement(server);
            LoggerFactory.getLogger("citizens").info("[MENUCOLLECTIONAUDIT] COMPLETE {} checks", passed);
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[MENUCOLLECTIONAUDIT] FAILED", failure);
        } finally {
            try {
                if (menu != null) menu.close();
                if (player != null && admitted) server.getPlayerList().remove(player);
                if (channel != null) channel.finishAndReleaseAll();
            } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[MENUCOLLECTIONAUDIT] FAILED cleanup", failure); }
            server.halt(false);
        }
    }

    private static void lockedAndEditable() {
        open();
        item(0, 10);
        player.getInventory().setItem(9, new ItemStack(Items.STONE, 4));
        cursor(1);
        // Supply the vanilla client's optimistic prediction, including the stolen locked control.
        drain(); content.clear();
        var predicted = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<ItemStack>();
        predicted.put(0, ItemStack.EMPTY); predicted.put(9, ItemStack.EMPTY);
        player.connection.handleContainerClick(new ServerboundContainerClickPacket(player.containerMenu.containerId,
                player.containerMenu.getStateId(), 10, 0, ClickType.PICKUP_ALL, new ItemStack(Items.STONE, 15), predicted));
        drain();
        check(count(0) == 10, "double_click_cannot_collect_locked_menu_item");
        check(carried() == 5 && player.getInventory().getItem(9).isEmpty(), "player_inventory_collection_still_works");
        var sync = latestContent();
        check(sync.getItems().get(0).getCount() == 10 && sync.getItems().get(9).isEmpty()
                && sync.getCarriedItem().getCount() == 5, "encoded_full_state_corrects_optimistic_client_prediction");
        item(0, 2); cursor(3);
        check(sync.getItems().get(0).getCount() == 10 && sync.getCarriedItem().getCount() == 5,
                "captured_client_state_detached_from_later_server_mutation");

        open();
        item(0, 10); item(1, 7); item(2, 6); item(3, 5); item(4, 8);
        page.ctx.getSlot(1).setFilter(List.of(InventoryAction.PICKUP_ALL));
        page.ctx.getSlot(2).setFilter(List.of(InventoryAction.COLLECT_TO_CURSOR));
        List<Integer> cancelled = new ArrayList<>();
        page.ctx.getSlot(3).setClickHandler(e -> { cancelled.add(e.getSlot()); e.setCancelled(true); });
        page.ctx.getSlot(4).setFilter(List.of(InventoryAction.PICKUP_ALL));
        page.ctx.getSlot(4).addClickHandler(e -> { check(e.isCancelled(), "filter_runs_before_override"); e.setCancelled(false); });
        cursor(1); click(10, 0, ClickType.PICKUP_ALL);
        check(count(0) == 10 && count(1) == 7 && count(3) == 5, "default_filter_and_callback_locks_preserved");
        check(count(2) == 0 && count(4) == 0 && carried() == 15, "editable_and_explicit_override_sources_collected");
        check(cancelled.equals(List.of(3)), "cancelled_partial_source_callback_runs_once_across_passes");

        open(); item(0, 10); allow(0); cursor(1);
        page.ctx.getSlot(8).setFilter(List.of(InventoryAction.COLLECT_TO_CURSOR));
        click(8, 0, ClickType.PICKUP_ALL);
        check(count(0) == 0 && carried() == 11, "allowed_empty_menu_origin_collects");
        open(); item(0, 10); allow(0); cursor(1);
        click(8, 0, ClickType.PICKUP_ALL);
        check(count(0) == 10 && carried() == 1, "locked_menu_origin_cancels_entire_click");
    }

    private static void orderingAndCapacity() {
        open(); item(0, 64); item(1, 7); item(2, 8); cursor(54);
        List<Integer> order = new ArrayList<>();
        List<Integer> results = new ArrayList<>();
        for (int slot : List.of(0, 1, 2)) {
            page.ctx.getSlot(slot).setClickHandler(e -> {
                context(e);
                order.add(e.getSlot()); results.add(e.getResultItemNonNull().getCount());
            });
        }
        click(10, 0, ClickType.PICKUP_ALL);
        check(order.equals(List.of(1, 2)) && results.equals(List.of(0, 5)), "partial_first_order_and_exact_predicted_remainders");
        check(count(0) == 64 && count(1) == 0 && count(2) == 5 && carried() == 64, "partial_first_capacity_conserved");

        open(); item(0, 64); item(1, 2); cursor(60); order.clear(); results.clear();
        for (int slot : List.of(0, 1)) page.ctx.getSlot(slot).setClickHandler(e -> {
            order.add(e.getSlot()); results.add(e.getResultItemNonNull().getCount());
        });
        click(10, 0, ClickType.PICKUP_ALL);
        check(order.equals(List.of(1, 0)) && results.equals(List.of(0, 62)), "full_stacks_wait_until_second_pass");
        check(count(0) == 62 && count(1) == 0 && carried() == 64, "full_stack_partial_take_conserved");

        open(); item(0, 7); item(1, 8); cursor(54); order.clear();
        for (int slot : List.of(0, 1)) page.ctx.getSlot(slot).setClickHandler(e -> order.add(e.getSlot()));
        click(10, 1, ClickType.PICKUP_ALL);
        check(order.equals(List.of(1, 0)) && count(0) == 5 && count(1) == 0 && carried() == 64, "reverse_direction_menu_order");

        open(); item(0, 10); allow(0); cursor(60);
        player.getInventory().setItem(0, new ItemStack(Items.STONE, 4));
        click(10, 1, ClickType.PICKUP_ALL);
        check(carried() == 64 && count(0) == 10 && player.getInventory().getItem(0).isEmpty(), "reverse_direction_player_hotbar_precedes_menu");

        open(); item(0, 64); cursor(1); order.clear();
        page.ctx.getSlot(0).setClickHandler(e -> { order.add(e.getSlot()); e.setCancelled(true); });
        click(10, 0, ClickType.PICKUP_ALL);
        check(order.equals(List.of(0)) && count(0) == 64 && carried() == 1, "cancelled_full_source_checked_once");

        open(); item(0, 4); allow(0); cursor(1);
        var named = new ItemStack(Items.STONE, 3); named.set(DataComponents.CUSTOM_NAME, Component.literal("Distinct"));
        page.ctx.getSlot(1).setItemStack(named); allow(1);
        page.ctx.getSlot(2).setItemStack(new ItemStack(Items.DIRT, 2)); allow(2);
        click(10, 0, ClickType.PICKUP_ALL);
        check(carried() == 5 && count(0) == 0 && count(1) == 3 && count(2) == 2, "item_and_component_compatibility_preserved");

        open();
        ItemStack smallStack = new ItemStack(Items.STONE, 9);
        smallStack.set(DataComponents.MAX_STACK_SIZE, 16);
        page.ctx.getSlot(0).setItemStack(smallStack);
        page.ctx.getSlot(0).setClickHandler(e -> check(e.getResultItemNonNull().getCount() == 5, "component_stack_limit_result_prediction"));
        current().setCarried(smallStack.copyWithCount(12));
        click(10, 0, ClickType.PICKUP_ALL);
        check(count(0) == 5 && carried() == 16, "native_component_stack_limit_respected");
    }

    private static void preconditions() {
        open(); item(0, 10); allow(0);
        click(10, 0, ClickType.PICKUP_ALL);
        check(count(0) == 10 && carried() == 0, "empty_cursor_does_not_collect");
        cursor(64); click(10, 0, ClickType.PICKUP_ALL);
        check(count(0) == 10 && carried() == 64, "full_cursor_does_not_collect");
        cursor(1); player.getInventory().setItem(10, new ItemStack(Items.DIRT));
        click(10, 0, ClickType.PICKUP_ALL);
        check(count(0) == 10 && carried() == 1, "occupied_pickupable_origin_does_not_collect");
        page.ctx.getSlot(0).setClickHandler(e -> check(e.getResultItemNonNull().getCount() == 10, "occupied_menu_origin_predicts_no_source_removal"));
        click(0, 0, ClickType.PICKUP_ALL);
        check(count(0) == 10 && carried() == 1, "occupied_menu_origin_does_not_collect");
        page.ctx.getSlot(0).setClickHandler(e -> {});
        player.getInventory().setItem(10, ItemStack.EMPTY);
        click(-999, 0, ClickType.PICKUP_ALL);
        check(count(0) == 10 && carried() == 1, "outside_origin_does_not_collect");
        click(-1, 0, ClickType.PICKUP_ALL);
        check(count(0) == 10 && carried() == 1, "negative_origin_does_not_collect");
        click(999, 0, ClickType.PICKUP_ALL);
        check(count(0) == 10 && carried() == 1, "invalid_packet_slot_rejected");

        cursor(8);
        click(-999, 0, ClickType.QUICK_CRAFT); // Start a native left drag.
        click(10, 1, ClickType.QUICK_CRAFT); // Add an empty player inventory slot.
        click(11, 0, ClickType.PICKUP_ALL);
        check(count(0) == 10 && carried() == 8 && player.getInventory().getItem(10).isEmpty(), "collection_consumes_unfinished_native_drag");
        click(-999, 2, ClickType.QUICK_CRAFT);
        check(carried() == 8 && player.getInventory().getItem(10).isEmpty(), "cancelled_drag_cannot_complete_later");
        click(11, 0, ClickType.PICKUP_ALL);
        check(count(0) == 0 && carried() == 18, "collection_resumes_after_drag_cancellation");
    }

    private static void callbacks() {
        for (String mutation : List.of("cursor_replace", "cursor_mutate", "source_replace", "source_mutate", "source_equal_replace")) {
            open(); item(0, 10); item(1, 4); allow(1); cursor(1);
            var active = current();
            page.ctx.getSlot(0).setClickHandler(e -> {
                context(e);
                switch (mutation) {
                    case "cursor_replace" -> e.setCursor(new ItemStack(Items.DIRT, 2));
                    case "cursor_mutate" -> active.getCarried().grow(1);
                    case "source_replace" -> page.ctx.getSlot(0).setItemStack(new ItemStack(Items.DIRT, 3));
                    case "source_mutate" -> page.ctx.getSlot(0).getCurrentItemNonNull().shrink(1);
                    case "source_equal_replace" -> item(0, 10);
                }
            });
            click(10, 0, ClickType.PICKUP_ALL);
            check(count(1) == 4, mutation + "_stops_remaining_sources");
            check(count(0) == (mutation.equals("source_replace") ? 3 : mutation.equals("source_mutate") ? 9 : 10), mutation + "_source_preserved");
            check(carried() == (mutation.startsWith("cursor_") ? 2 : 1), mutation + "_cursor_preserved");
            check(mutation.equals("cursor_replace") ? active.getCarried().is(Items.DIRT) : active.getCarried().is(Items.STONE), mutation + "_cursor_identity");
            var sync = latestContent();
            check(ItemStack.matches(sync.getCarriedItem(), active.getCarried())
                    && ItemStack.matches(sync.getItems().get(0), active.getSlot(0).getItem()), mutation + "_client_resync");
            page.ctx.getSlot(0).setClickHandler(e -> {});
            item(0, 10); cursor(1);
            click(10, 0, ClickType.PICKUP_ALL);
            check(count(0) == 0 && count(1) == 0 && carried() == 15, mutation + "_guard_released_for_next_click");
        }

        open(); item(0, 10); item(1, 4); allow(1); cursor(1);
        var active = current(); List<Integer> recursive = new ArrayList<>();
        page.ctx.getSlot(0).setClickHandler(e -> {
            recursive.add(e.getSlot());
            check(recursive.size() == 1, "recursive_callback_not_reentered");
            active.clicked(0, 0, ClickType.PICKUP_ALL, player);
            active.clicked(10, 0, ClickType.PICKUP_ALL, player);
            check(count(0) == 10 && carried() == 1, "recursive_collection_has_no_side_effects");
        });
        click(10, 0, ClickType.PICKUP_ALL);
        check(recursive.equals(List.of(0)) && count(0) == 0 && count(1) == 0 && carried() == 15, "outer_collection_completes_once_after_recursion");

        open(); item(0, 10); allow(0); cursor(1);
        var originContainer = current(); List<Integer> origins = new ArrayList<>();
        page.ctx.getSlot(8).setClickHandler(e -> {
            origins.add(e.getSlot());
            check(origins.size() == 1, "origin_callback_not_reentered");
            originContainer.clicked(8, 0, ClickType.PICKUP_ALL, player);
            originContainer.clicked(10, 0, ClickType.PICKUP_ALL, player);
            check(count(0) == 10 && carried() == 1, "origin_recursion_has_no_transfer_side_effects");
        });
        click(8, 0, ClickType.PICKUP_ALL);
        check(origins.equals(List.of(8)) && count(0) == 0 && carried() == 11, "outer_collection_completes_after_origin_recursion");
        item(0, 4); click(10, 0, ClickType.PICKUP_ALL);
        check(count(0) == 0 && carried() == 15, "origin_collection_guard_released_for_next_click");

        open(); item(0, 10); cursor(1);
        page.ctx.getSlot(0).setClickHandler(e -> { e.getCurrentItemNonNull().setCount(30); e.getCursorNonNull().setCount(40); });
        click(10, 0, ClickType.PICKUP_ALL);
        check(count(0) == 0 && carried() == 11, "event_snapshots_do_not_mutate_live_stacks");

        for (String lifecycle : List.of("transition", "close", "replace_menu")) {
            open(); item(0, 10); item(1, 4); allow(1); cursor(1);
            CitizensMenuContainer old = current(); Page oldPage = page;
            page.ctx.getSlot(0).setClickHandler(e -> {
                if (lifecycle.equals("transition")) { Page next = new Page(); menu.transition(next); page = next; }
                else if (lifecycle.equals("close")) menu.close();
                else { menu.close(); page = new Page(); menu = InventoryMenu.create(page); menu.present(player); }
            });
            click(10, 0, ClickType.PICKUP_ALL);
            check(player.containerMenu != old, lifecycle + "_retires_old_container");
            check(oldPage.ctx.getSlot(0).getCurrentItemNonNull().getCount() == 10
                    && oldPage.ctx.getSlot(1).getCurrentItemNonNull().getCount() == 4, lifecycle + "_stops_old_source_transfer");
            check(player.getInventory().countItem(Items.STONE) == 1 && player.containerMenu.getCarried().isEmpty(), lifecycle + "_native_close_returns_original_cursor_only");
            check(content.stream().noneMatch(p -> p.getContainerId() == old.containerId), lifecycle + "_does_not_resend_retired_menu");
            old.setCarried(new ItemStack(Items.STONE, 1));
            old.clicked(10, 0, ClickType.PICKUP_ALL, player);
            check(old.getCarried().getCount() == 1 && old.getSlot(1).getItem().getCount() == 4, lifecycle + "_rejects_direct_stale_click");
            old.setCarried(ItemStack.EMPTY);
        }
    }

    private static void retirement(MinecraftServer server) {
        open(); item(0, 10); allow(0); cursor(1);
        var old = current();
        ServerPlayer impostor = new ServerPlayer(server, server.overworld(), player.getGameProfile(), ClientInformation.createDefault());
        impostor.containerMenu = old;
        old.clicked(10, 0, ClickType.PICKUP_ALL, impostor);
        check(count(0) == 10 && carried() == 1, "same_uuid_different_player_cannot_use_current_menu");
        server.getPlayerList().remove(player); admitted = false;
        old.setCarried(new ItemStack(Items.STONE, 1));
        old.clicked(10, 0, ClickType.PICKUP_ALL, player);
        check(old.getSlot(0).getItem().getCount() == 10 && old.getCarried().getCount() == 1, "retired_player_session_cannot_collect");
        old.setCarried(ItemStack.EMPTY);
    }

    private static void stockEditor() {
        menu.close(); player.getInventory().clearContent();
        var storage = new net.citizensnpcs.trait.shop.NPCShopStorage();
        storage.setInventory(new ArrayList<>(List.of(new ItemStack(Items.STONE, 10), new ItemStack(Items.DIRT, 3))));
        menu = InventoryMenu.create(storage.createInventoryViewer(player)); menu.present(player);
        var stock = current();
        check(stock.getMenuSize() == 36, "actual_stock_editor_opened");
        cursor(1);
        click(36, 0, ClickType.PICKUP_ALL);
        check(stock.getSlot(0).getItem().isEmpty() && stock.getSlot(1).getItem().getCount() == 3 && carried() == 11,
                "actual_stock_editor_allows_collection");
        menu.close();
        check(storage.getInventory().size() == 1 && storage.getInventory().getFirst().is(Items.DIRT)
                && storage.getInventory().getFirst().getCount() == 3, "stock_editor_saves_remaining_inventory_on_close");
        check(player.getInventory().countItem(Items.STONE) == 11, "stock_editor_close_returns_collected_cursor_once");
        menu.present(player);
        check(current().getSlot(0).getItem().isEmpty() && current().getSlot(1).getItem().getCount() == 3,
                "same_stock_menu_reopens_without_resurrecting_collected_items");
    }

    private static void context(CitizensInventoryClickEvent event) {
        check(event.getWhoClicked() == player && event.getViewers().contains(player), "callback_has_real_clicker_and_viewers");
        check(event.getClick() == MenuClickType.DOUBLE_CLICK && event.getAction() == InventoryAction.COLLECT_TO_CURSOR
                && event.getHotbarButton() == -1, "callback_has_collection_action_and_click");
    }
    private static CitizensMenuContainer current() { return (CitizensMenuContainer) player.containerMenu; }
    private static void item(int slot, int count) { page.ctx.getSlot(slot).setItemStack(new ItemStack(Items.STONE, count)); }
    private static void allow(int slot) { page.ctx.getSlot(slot).setFilter(List.of(InventoryAction.COLLECT_TO_CURSOR)); }
    private static int count(int slot) { return page.ctx.getSlot(slot).getCurrentItemNonNull().getCount(); }
    private static int carried() { return player.containerMenu.getCarried().getCount(); }
    private static void cursor(int count) { player.containerMenu.setCarried(new ItemStack(Items.STONE, count)); }
    private static ClientboundContainerSetContentPacket latestContent() {
        var packets = content.stream().filter(p -> p.getContainerId() == player.containerMenu.containerId).toList();
        check(!packets.isEmpty(), "current_menu_full_state_sent");
        return packets.getLast();
    }
    private static void open() {
        if (menu != null) menu.close();
        player.getInventory().clearContent();
        page = new Page(); menu = InventoryMenu.create(page); menu.present(player);
        check(player.containerMenu instanceof CitizensMenuContainer, "native_menu_opened");
    }
    private static void click(int slot, int button, ClickType type) {
        drain(); content.clear();
        player.connection.handleContainerClick(new ServerboundContainerClickPacket(player.containerMenu.containerId,
                player.containerMenu.getStateId(), slot, button, type, ItemStack.EMPTY,
                new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>()));
        drain();
    }
    private static void drain() {
        channel.runPendingTasks();
        Object output; while ((output = channel.readOutbound()) != null) ReferenceCountUtil.release(output);
    }
    @Menu(dimensions = {1, 9}) public static class Page extends InventoryMenuPage {
        MenuContext ctx;
        @Override public void initialise(MenuContext ctx) { this.ctx = ctx; }
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++; LoggerFactory.getLogger("citizens").info("[MENUCOLLECTIONAUDIT] PASS {}", label);
    }
}
