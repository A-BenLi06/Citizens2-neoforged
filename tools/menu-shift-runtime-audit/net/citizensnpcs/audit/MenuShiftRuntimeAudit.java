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
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class MenuShiftRuntimeAudit {
    private static boolean done;
    private static int passed;
    private static ServerPlayer player;
    private static EmbeddedChannel channel;
    private static InventoryMenu menu;
    private static Page page;
    private static final List<ClientboundContainerSetContentPacket> content = new ArrayList<>();

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done || event.getServer().getTickCount() < 10) return;
        done = true;
        var server = event.getServer();
        try {
            check(Files.isRegularFile(Path.of("menu-shift-audit-fixture.txt")), "isolated_fixture");
            player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), "MenuShiftAudit"), ClientInformation.createDefault());
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
            inbound();
            outbound();
            constraints();
            mutations();
            lifecycle();
            recursion();
            stockEditor();
            LoggerFactory.getLogger("citizens").info("[MENUSHIFTAUDIT] COMPLETE {} checks", passed);
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[MENUSHIFTAUDIT] FAILED", failure);
        } finally {
            try {
                if (menu != null) menu.close();
                if (player != null) server.getPlayerList().remove(player);
                if (channel != null) channel.finishAndReleaseAll();
            } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[MENUSHIFTAUDIT] FAILED cleanup", failure); }
            server.halt(false);
        }
    }

    private static void inbound() {
        open();
        item(0, 60); item(1, 61); allow(0); allow(1); allow(2);
        List<InventoryAction> actions = new ArrayList<>(); List<Integer> cursors = new ArrayList<>();
        List<Integer> results = new ArrayList<>(); List<Integer> order = new ArrayList<>();
        player.containerMenu.setCarried(new ItemStack(Items.DIRT, 2));
        for (int slot : List.of(0, 1, 2)) page.ctx.getSlot(slot).setClickHandler(e -> {
            context(e, false); actions.add(e.getAction()); cursors.add(e.getCursorNonNull().getCount());
            results.add(e.getResultItemNonNull().getCount()); order.add(e.getSlot());
            check(player.containerMenu.getCarried().is(Items.DIRT) && player.containerMenu.getCarried().getCount() == 2,
                    "inbound_event_does_not_replace_real_cursor");
        });
        player.getInventory().setItem(9, new ItemStack(Items.STONE, 12));
        click(9, 0, ClickType.QUICK_MOVE);
        check(count(0) == 64 && count(1) == 64 && count(2) == 5 && player.getInventory().getItem(9).isEmpty(),
                "shift_merges_all_partial_stacks_before_empty_slot");
        check(actions.equals(List.of(InventoryAction.PLACE_SOME, InventoryAction.PLACE_SOME, InventoryAction.PLACE_ALL))
                && cursors.equals(List.of(4, 3, 5)) && results.equals(List.of(64, 64, 5)) && order.equals(List.of(0, 1, 2)),
                "inbound_actions_amounts_results_and_order_are_exact");
        sync();

        open(); allow(0); item(1, 60); allow(1);
        player.getInventory().setItem(9, new ItemStack(Items.STONE, 3));
        click(9, 0, ClickType.QUICK_MOVE);
        check(count(0) == 0 && count(1) == 63, "merge_pass_precedes_earlier_empty_slot");

        open(); item(0, 60); item(1, 60); item(2, 60); allow(3);
        page.ctx.getSlot(0).setFilter(List.of(InventoryAction.PICKUP_ALL));
        page.ctx.getSlot(1).setClickHandler(e -> e.setCancelled(true));
        allow(2); player.getInventory().setItem(9, new ItemStack(Items.STONE, 10));
        click(9, 0, ClickType.QUICK_MOVE);
        check(count(0) == 60 && count(1) == 60 && count(2) == 64 && count(3) == 6,
                "denied_merge_targets_do_not_block_later_allowed_targets");
        open(); allow(2); player.getInventory().setItem(9, new ItemStack(Items.STONE, 10));
        click(9, 0, ClickType.QUICK_MOVE);
        check(count(0) == 0 && count(1) == 0 && count(2) == 10, "locked_empty_targets_are_skipped");

        open(); for (int i = 0; i < 9; i++) item(i, 64);
        item(0, 60); allow(0); player.getInventory().setItem(9, new ItemStack(Items.STONE, 10));
        click(9, 0, ClickType.QUICK_MOVE);
        check(count(0) == 64 && player.getInventory().getItem(9).getCount() == 6, "full_menu_retains_untransferred_remainder");
        sync();
    }

    private static void outbound() {
        open(); item(0, 12); allow(0);
        player.getInventory().setItem(8, new ItemStack(Items.STONE, 60));
        player.getInventory().setItem(7, new ItemStack(Items.STONE, 61));
        current().setCarried(new ItemStack(Items.DIRT, 2));
        List<InventoryAction> actions = new ArrayList<>(); List<Integer> results = new ArrayList<>();
        page.ctx.getSlot(0).setClickHandler(e -> {
            context(e, true); actions.add(e.getAction()); results.add(e.getResultItemNonNull().getCount());
            check(e.getCursorNonNull().is(Items.DIRT) && e.getCursorNonNull().getCount() == 2, "outbound_event_has_real_cursor_snapshot");
        });
        click(0, 1, ClickType.QUICK_MOVE);
        check(count(0) == 0 && player.getInventory().getItem(8).getCount() == 64
                && player.getInventory().getItem(7).getCount() == 64 && player.getInventory().getItem(6).getCount() == 5,
                "outbound_uses_native_reverse_hotbar_order_and_all_destinations");
        check(actions.equals(List.of(InventoryAction.PICKUP_SOME, InventoryAction.PICKUP_SOME, InventoryAction.PICKUP_ALL))
                && results.equals(List.of(8, 5, 0)), "outbound_actions_and_predicted_source_remainders_are_exact");
        sync();

        open(); item(0, 10); player.getInventory().setItem(8, new ItemStack(Items.STONE, 60));
        page.ctx.getSlot(0).setFilter(List.of(InventoryAction.PICKUP_SOME));
        click(0, 0, ClickType.QUICK_MOVE);
        check(count(0) == 6 && player.getInventory().getItem(8).getCount() == 64 && player.getInventory().getItem(7).isEmpty(),
                "outbound_cancellation_stops_remaining_source_after_allowed_partial_move");
        open(); item(0, 10); click(0, 0, ClickType.QUICK_MOVE);
        check(count(0) == 10 && player.getInventory().countItem(Items.STONE) == 0, "locked_outbound_source_preserved");

        open(); item(0, 10); allow(0);
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        player.getInventory().setItem(8, new ItemStack(Items.STONE, 62));
        click(0, 0, ClickType.QUICK_MOVE);
        check(count(0) == 8 && player.getInventory().getItem(8).getCount() == 64, "full_player_inventory_retains_menu_remainder");
    }

    private static void constraints() {
        open(); for (int i = 0; i < 3; i++) { allow(i); restrict(i, 4, true, true); }
        player.getInventory().setItem(9, new ItemStack(Items.STONE, 10));
        List<Integer> remainders = new ArrayList<>();
        for (int i = 0; i < 3; i++) page.ctx.getSlot(i).setClickHandler(e -> remainders.add(e.getResultItemNonNull().getCount()));
        click(9, 0, ClickType.QUICK_MOVE);
        check(count(0) == 4 && count(1) == 4 && count(2) == 2 && remainders.equals(List.of(4, 4, 2)),
                "native_slot_limits_bound_each_empty_destination_and_event_result");
        open(); item(0, 10); allow(0); restrict(44, 4, true, true); restrict(43, 4, true, true);
        click(0, 0, ClickType.QUICK_MOVE);
        check(count(0) == 0 && player.getInventory().getItem(8).getCount() == 4 && player.getInventory().getItem(7).getCount() == 4
                && player.getInventory().getItem(6).getCount() == 2, "outbound_slot_limits_continue_through_empty_destinations");

        open(); allow(0); allow(1); item(0, 2); restrict(0, 64, false, true);
        player.getInventory().setItem(9, new ItemStack(Items.STONE, 10));
        click(9, 0, ClickType.QUICK_MOVE);
        check(count(0) == 2 && count(1) == 10, "native_destination_may_place_restriction_respected");
        open(); item(0, 10); allow(0); restrict(0, 64, true, false);
        click(0, 0, ClickType.QUICK_MOVE);
        check(count(0) == 10 && player.getInventory().countItem(Items.STONE) == 0, "native_source_may_pickup_restriction_respected");
        open(); allow(0); var target = restrict(0, 64, true, true);
        page.ctx.getSlot(0).setClickHandler(e -> target.canPlace = false);
        player.getInventory().setItem(9, new ItemStack(Items.STONE, 10)); click(9, 0, ClickType.QUICK_MOVE);
        check(count(0) == 0 && player.getInventory().getItem(9).getCount() == 10, "callback_changed_slot_permission_rechecked");

        open(); allow(0); allow(1);
        ItemStack named = new ItemStack(Items.STONE, 15); named.set(DataComponents.CUSTOM_NAME, Component.literal("Named"));
        named.set(DataComponents.MAX_STACK_SIZE, 16); page.ctx.getSlot(0).setItemStack(named.copy());
        player.getInventory().setItem(9, named.copyWithCount(4));
        click(9, 0, ClickType.QUICK_MOVE);
        check(count(0) == 16 && count(1) == 3 && ItemStack.isSameItemSameComponents(named, current().getSlot(1).getItem()),
                "component_stack_limit_and_payload_preserved");
        open(); item(0, 5); allow(0); allow(1); player.getInventory().setItem(9, named.copyWithCount(4));
        click(9, 0, ClickType.QUICK_MOVE);
        check(count(0) == 5 && count(1) == 4, "different_components_do_not_merge");

        open(); allow(0); player.getInventory().setItem(9, new ItemStack(Items.STONE, 10));
        click(9, 2, ClickType.QUICK_MOVE);
        check(count(0) == 0 && player.getInventory().getItem(9).getCount() == 10, "invalid_shift_button_rejected");
        current().setCarried(new ItemStack(Items.STONE, 8));
        click(-999, 0, ClickType.QUICK_CRAFT); click(10, 1, ClickType.QUICK_CRAFT); click(9, 0, ClickType.QUICK_MOVE);
        check(count(0) == 0 && player.getInventory().getItem(9).getCount() == 10 && current().getCarried().getCount() == 8,
                "shift_consumes_unfinished_native_drag");
        click(-999, 2, ClickType.QUICK_CRAFT); click(9, 0, ClickType.QUICK_MOVE);
        check(count(0) == 10 && current().getCarried().getCount() == 8 && player.getInventory().getItem(10).isEmpty(),
                "cancelled_drag_stays_cancelled_and_next_shift_works");
    }

    private static void mutations() {
        for (boolean outbound : List.of(false, true)) {
            for (String change : List.of("source_replace", "source_mutate", "source_equal_replace", "target_replace", "target_mutate", "target_equal_replace", "cursor_replace", "cursor_mutate")) {
                open();
                int sourceIndex = outbound ? 0 : 9, targetIndex = outbound ? 44 : 0, nextIndex = outbound ? 43 : 1;
                var active = current(); var source = active.getSlot(sourceIndex); var target = active.getSlot(targetIndex);
                source.set(new ItemStack(Items.STONE, 10)); target.set(new ItemStack(Items.STONE, 4));
                active.getSlot(nextIndex).set(new ItemStack(Items.STONE, 4)); allow(0); allow(1);
                active.setCarried(new ItemStack(Items.DIRT, 2));
                page.ctx.getSlot(0).setClickHandler(e -> {
                    switch (change) {
                        case "source_replace" -> source.set(new ItemStack(Items.DIRT, 3));
                        case "source_mutate" -> source.getItem().shrink(1);
                        case "source_equal_replace" -> source.set(source.getItem().copy());
                        case "target_replace" -> target.set(new ItemStack(Items.DIRT, 3));
                        case "target_mutate" -> target.getItem().grow(1);
                        case "target_equal_replace" -> target.set(target.getItem().copy());
                        case "cursor_replace" -> e.setCursor(new ItemStack(Items.DIAMOND, 3));
                        case "cursor_mutate" -> active.getCarried().grow(1);
                    }
                });
                click(sourceIndex, 0, ClickType.QUICK_MOVE);
                String label = (outbound ? "out_" : "in_") + change;
                check(source.getItem().getCount() == (change.equals("source_mutate") ? 9 : change.equals("source_replace") ? 3 : 10), label + "_preserves_source");
                check(target.getItem().getCount() == (change.equals("target_mutate") ? 5 : change.equals("target_replace") ? 3 : 4), label + "_preserves_destination");
                check(active.getSlot(nextIndex).getItem().getCount() == 4 && active.getCarried().getCount() == (change.startsWith("cursor_") ? 3 : 2),
                        label + "_stops_remaining_transfers_and_preserves_cursor");
                sync();
                page.ctx.getSlot(0).setClickHandler(e -> {}); source.set(new ItemStack(Items.STONE, 10));
                target.set(new ItemStack(Items.STONE, 4));
                click(sourceIndex, 0, ClickType.QUICK_MOVE);
                check(source.getItem().isEmpty() && target.getItem().getCount() == 14, label + "_guard_released");
            }
        }
    }

    private static void lifecycle() {
        for (boolean outbound : List.of(false, true)) for (String action : List.of("close", "transition", "replace")) {
            open(); var old = current();
            int sourceIndex = outbound ? 0 : 9, first = outbound ? 44 : 0, second = outbound ? 43 : 1;
            old.getSlot(sourceIndex).set(new ItemStack(Items.STONE, 10));
            old.getSlot(first).set(new ItemStack(Items.STONE, 60)); old.getSlot(second).set(new ItemStack(Items.STONE, 60));
            old.setCarried(new ItemStack(Items.DIRT, 2)); allow(0); allow(1);
            int[] calls = { 0 };
            var handler = (java.util.function.Consumer<CitizensInventoryClickEvent>) e -> {
                calls[0]++;
                if (outbound ? calls[0] != 2 : e.getSlot() != 1) return;
                if (action.equals("close")) menu.close();
                else if (action.equals("transition")) { page = new Page(); menu.transition(page); }
                else { menu.close(); page = new Page(); menu = InventoryMenu.create(page); menu.present(player); }
            };
            page.ctx.getSlot(0).setClickHandler(handler); if (!outbound) page.ctx.getSlot(1).setClickHandler(handler);
            click(sourceIndex, 0, ClickType.QUICK_MOVE);
            String label = (outbound ? "out_" : "in_") + action;
            check(player.containerMenu != old && old.getSlot(sourceIndex).getItem().getCount() == 6,
                    label + "_stops_old_transfer_after_first_completed_move");
            check(old.getSlot(first).getItem().getCount() == 64 && old.getSlot(second).getItem().getCount() == 60,
                    label + "_preserves_completed_transfer_and_unmodified_next_target");
            check(player.getInventory().countItem(Items.DIRT) == 2 && player.containerMenu.getCarried().isEmpty(), label + "_native_close_returns_actual_cursor_once");
            check(content.stream().noneMatch(p -> p.getContainerId() == old.containerId), label + "_does_not_resync_retired_menu");
            old.clicked(sourceIndex, 0, ClickType.QUICK_MOVE, player);
            check(old.getSlot(sourceIndex).getItem().getCount() == 6 && old.getSlot(second).getItem().getCount() == 60, label + "_rejects_retired_container");
        }
    }

    private static void recursion() {
        for (boolean outbound : List.of(false, true)) {
            open(); var active = current(); int sourceIndex = outbound ? 0 : 9;
            active.getSlot(sourceIndex).set(new ItemStack(Items.STONE, 10));
            int[] calls = { 0 };
            page.ctx.getSlot(0).setClickHandler(e -> {
                check(++calls[0] == 1, "shift_callback_not_reentered");
                active.clicked(sourceIndex, 0, ClickType.QUICK_MOVE, player);
                active.clicked(10, 0, ClickType.PICKUP_ALL, player);
                check(active.getSlot(sourceIndex).getItem().getCount() == 10, "recursive_bulk_operations_have_no_side_effects");
            });
            click(sourceIndex, 0, ClickType.QUICK_MOVE);
            check(calls[0] == 1 && active.getSlot(sourceIndex).getItem().isEmpty(), "outer_shift_completes_once_after_recursion");
        }
    }

    private static void stockEditor() {
        menu.close(); player.getInventory().clearContent();
        var storage = new net.citizensnpcs.trait.shop.NPCShopStorage();
        storage.setInventory(new ArrayList<>(List.of(new ItemStack(Items.STONE, 10), new ItemStack(Items.DIRT, 3))));
        menu = InventoryMenu.create(storage.createInventoryViewer(player)); menu.present(player);
        click(0, 0, ClickType.QUICK_MOVE); menu.close();
        check(storage.getInventory().size() == 1 && storage.getInventory().getFirst().is(Items.DIRT)
                && player.getInventory().getItem(8).getCount() == 10, "actual_stock_editor_shift_out_saves_remaining_stock");
        menu = InventoryMenu.create(storage.createInventoryViewer(player)); menu.present(player);
        click(71, 0, ClickType.QUICK_MOVE); menu.close();
        check(storage.getInventory().size() == 2 && storage.getInventory().get(1).is(Items.STONE)
                && storage.getInventory().get(1).getCount() == 10 && player.getInventory().getItem(8).isEmpty(),
                "actual_stock_editor_shift_in_saves_received_stock");
    }

    private static void context(CitizensInventoryClickEvent e, boolean right) {
        check(e.getWhoClicked() == player && e.getViewers().contains(player) && e.isShiftClick(), "event_has_actual_clicker_and_shift_context");
        check(e.getClick() == (right ? MenuClickType.SHIFT_RIGHT : MenuClickType.SHIFT_LEFT) && e.getHotbarButton() == -1,
                "event_preserves_native_shift_button");
    }
    private static void sync() {
        var packets = content.stream().filter(p -> p.getContainerId() == player.containerMenu.containerId).toList();
        check(!packets.isEmpty(), "encoded_current_menu_full_state_sent");
        var packet = packets.getLast();
        check(ItemStack.matches(packet.getCarriedItem(), current().getCarried()), "encoded_cursor_matches_authoritative_cursor");
        boolean same = true;
        for (int i = 0; i < current().slots.size(); i++) same &= ItemStack.matches(packet.getItems().get(i), current().getSlot(i).getItem());
        check(same, "encoded_all_slots_match_authoritative_items");
    }
    private static RestrictedSlot restrict(int index, int limit, boolean place, boolean pickup) {
        Slot original = current().getSlot(index);
        RestrictedSlot slot = new RestrictedSlot(original, limit, place, pickup); slot.index = index;
        current().slots.set(index, slot); return slot;
    }
    private static final class RestrictedSlot extends Slot {
        private int limit;
        private boolean canPlace, canPickup;
        RestrictedSlot(Slot original, int limit, boolean place, boolean pickup) {
            super(original.container, original.getContainerSlot(), original.x, original.y);
            this.limit = limit; canPlace = place; canPickup = pickup;
        }
        @Override public int getMaxStackSize() { return limit; }
        @Override public boolean mayPlace(ItemStack stack) { return canPlace; }
        @Override public boolean mayPickup(Player player) { return canPickup; }
    }

    private static CitizensMenuContainer current() { return (CitizensMenuContainer) player.containerMenu; }
    private static void item(int slot, int count) { page.ctx.getSlot(slot).setItemStack(new ItemStack(Items.STONE, count)); }
    private static void allow(int slot) { page.ctx.getSlot(slot).setFilter(List.of()); }
    private static int count(int slot) { return page.ctx.getSlot(slot).getCurrentItemNonNull().getCount(); }
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
        passed++; LoggerFactory.getLogger("citizens").info("[MENUSHIFTAUDIT] PASS {}", label);
    }
}
