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
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class MenuDragRuntimeAudit {
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
            check(Files.isRegularFile(Path.of("menu-drag-audit-fixture.txt")), "isolated_fixture");
            player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), "MenuDragAudit"), ClientInformation.createDefault());
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
            drag();
            distribution();
            singleAndCreative();
            protocol();
            callbackMutations();
            lifecycle();
            recursion();
            stockAndEquipment();
            LoggerFactory.getLogger("citizens").info("[MENUDRAGAUDIT] COMPLETE {} checks", passed);
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[MENUDRAGAUDIT] FAILED", failure);
        } finally {
            try {
                if (menu != null) menu.close();
                if (player != null) server.getPlayerList().remove(player);
                if (channel != null) channel.finishAndReleaseAll();
            } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[MENUDRAGAUDIT] FAILED cleanup", failure); }
            server.halt(false);
        }
    }

    private static void drag() {
        open(); allow(0);
        current().setCarried(new ItemStack(Items.STONE, 8));
        click(-999, 0, ClickType.QUICK_CRAFT);
        click(0, 1, ClickType.QUICK_CRAFT);
        click(9, 1, ClickType.QUICK_CRAFT);
        page.ctx.getSlot(0).setFilter(List.of(InventoryAction.PICKUP_ALL));
        click(-999, 2, ClickType.QUICK_CRAFT);
        check(count(0) == 0 && player.getInventory().getItem(9).getCount() == 4 && current().getCarried().getCount() == 4,
                "drag_rechecks_menu_lock_at_completion");
        sync();
        open(); cursor(8); begin(0, 0, 1); allow(0); allow(1); end(0);
        check(count(0) == 4 && count(1) == 4 && carried() == 0, "slots_unlocked_before_completion_receive_native_shares");
        open(); cursor(8); allow(1); begin(0, 0, 1); end(0);
        check(count(0) == 0 && count(1) == 4 && carried() == 4, "default_locked_share_stays_on_cursor_without_redistribution");
    }

    private static void distribution() {
        open(); item(0, 2); cursor(9);
        List<Integer> calls = new ArrayList<>();
        for (int index : List.of(0, 1)) page.ctx.getSlot(index).setClickHandler(e -> {
            context(e, MenuClickType.LEFT); calls.add(e.getSlot());
            check(e.getAction() == InventoryAction.PLACE_SOME && e.getCursorNonNull().getCount() == 4,
                    "left_drag_reports_exact_incoming_share");
            check(e.getResultItemNonNull().getCount() == (e.getSlot() == 0 ? 6 : 4), "left_drag_predicts_exact_destination_result");
        });
        begin(0, 0, 1);
        check(calls.isEmpty() && count(0) == 2 && count(1) == 0 && carried() == 9, "admission_has_no_menu_callbacks_or_mutations");
        check(content.stream().noneMatch(p -> p.getContainerId() == current().containerId), "admission_does_not_send_premature_full_correction");
        end(0);
        check(new HashSet<>(calls).equals(Set.of(0, 1)) && calls.size() == 2 && count(0) == 6 && count(1) == 4 && carried() == 1,
                "left_drag_native_even_shares_and_remainder");
        sync();
        var detached = content.getLast(); item(0, 1); cursor(2);
        check(detached.getItems().get(0).getCount() == 6 && detached.getCarriedItem().getCount() == 1, "captured_state_is_detached_from_later_mutations");

        open(); item(0, 2); cursor(5);
        for (int index : List.of(0, 1)) page.ctx.getSlot(index).setClickHandler(e -> {
            context(e, MenuClickType.RIGHT);
            check(e.getAction() == InventoryAction.PLACE_ONE && e.getCursorNonNull().getCount() == 1, "right_drag_reports_one_item");
        });
        begin(1, 0, 1, 9); end(1);
        check(count(0) == 3 && count(1) == 1 && player.getInventory().getItem(9).getCount() == 1 && carried() == 2,
                "right_drag_one_per_menu_and_player_slot");

        open(); allow(0); allow(1); allow(2); cursor(2); begin(0, 0, 0, 1, 2); end(0);
        check(count(0) == 1 && count(1) == 1 && count(2) == 0 && carried() == 0, "duplicate_candidates_and_native_item_count_admission");
        open(); item(0, 1); allow(0); allow(1); restrict(0, 3, true); cursor(10); begin(0, 0, 1); end(0);
        check(count(0) == 3 && count(1) == 5 && carried() == 3, "slot_limit_retains_capped_share_on_cursor");

        open(); allow(0); allow(1); allow(2);
        ItemStack named = new ItemStack(Items.STONE, 5); named.set(DataComponents.CUSTOM_NAME, Component.literal("Named"));
        named.set(DataComponents.MAX_STACK_SIZE, 16);
        page.ctx.getSlot(0).setItemStack(named.copyWithCount(15)); item(2, 3); current().setCarried(named.copy());
        begin(0, 0, 1, 2); end(0);
        check(count(0) == 16 && count(1) == 2 && count(2) == 3 && carried() == 2
                && ItemStack.isSameItemSameComponents(named, current().getSlot(1).getItem()), "components_native_stack_limit_and_incompatible_admission");
        open(); allow(0); allow(1); cursor(8); begin(0, 0, 1); item(0, 5); end(0);
        check(count(0) == 9 && count(1) == 4 && carried() == 0, "completion_uses_current_compatible_destination_count");
        open(); allow(0); allow(1); cursor(8); begin(0, 0, 1); cursor(12); end(0);
        check(count(0) == 6 && count(1) == 6 && carried() == 0, "completion_uses_current_cursor_like_native");
    }

    private static void singleAndCreative() {
        open(); item(0, 2); cursor(8); List<InventoryAction> actions = new ArrayList<>();
        page.ctx.getSlot(0).setClickHandler(e -> actions.add(e.getAction()));
        begin(0, 0); end(0);
        check(count(0) == 10 && carried() == 0 && actions.equals(List.of(InventoryAction.PLACE_ALL)), "single_left_drag_routes_through_normal_click");
        open(); cursor(8); allow(0); begin(1, 0); end(1);
        check(count(0) == 1 && carried() == 7, "single_right_drag_places_one");
        open(); cursor(8); begin(0, 0); end(0);
        check(count(0) == 0 && carried() == 8, "single_locked_drag_is_cancelled_by_normal_click");
        open(); cursor(3); allow(0); allow(1); begin(2, 0, 1); end(2);
        check(count(0) == 0 && count(1) == 0 && carried() == 3, "survival_cannot_start_creative_drag");
        open(); player.gameMode.changeGameModeForPlayer(GameType.CREATIVE); cursor(3); allow(0);
        actions.clear(); page.ctx.getSlot(0).setClickHandler(e -> actions.add(e.getAction()));
        begin(2, 0); end(2);
        check(count(0) == 0 && carried() == 3 && actions.isEmpty(), "single_creative_drag_preserves_native_noop");
        allow(1);
        for (int index : List.of(0, 1)) page.ctx.getSlot(index).setClickHandler(e -> {
            context(e, MenuClickType.MIDDLE);
            check(e.getResultItemNonNull().getCount() == 64 && e.getAction() == InventoryAction.PLACE_ALL, "creative_drag_exact_fill_result");
        });
        begin(2, 0, 1); end(2);
        check(count(0) == 64 && count(1) == 64 && current().getCarried().isEmpty(), "creative_multi_drag_native_fill_and_cursor_semantics");
        open(); player.gameMode.changeGameModeForPlayer(GameType.CREATIVE); cursor(3); allow(0); allow(1);
        begin(2, 0, 1); player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL); end(2);
        check(count(0) == 0 && count(1) == 0 && carried() == 3, "creative_permission_rechecked_at_completion");
        open(); player.gameMode.changeGameModeForPlayer(GameType.CREATIVE); cursor(3);
        for (int index : List.of(0, 1)) page.ctx.getSlot(index).setClickHandler(e -> player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL));
        begin(2, 0, 1); end(2);
        check(count(0) == 0 && count(1) == 0 && carried() == 3, "creative_permission_rechecked_after_callback");
    }

    private static void protocol() {
        open(); allow(0); allow(1); cursor(8);
        end(0); check(count(0) == 0 && carried() == 8, "end_without_start_is_noop");
        begin(0, 0, -999); end(0);
        check(count(0) == 0 && carried() == 8, "outside_candidate_resets_without_native_index_exception");
        begin(3, 0, 1); end(3);
        check(count(0) == 0 && count(1) == 0 && carried() == 8, "invalid_drag_type_resets");
        begin(0, 0, 1); end(0); end(0);
        check(count(0) == 4 && count(1) == 4 && carried() == 0, "completed_gesture_cannot_replay");
        open(); cursor(8); int[] clicks = { 0 };
        page.ctx.getSlot(0).setClickHandler(e -> clicks[0]++); allow(1);
        begin(0, 0, 1); click(0, 0, ClickType.PICKUP);
        check(clicks[0] == 0 && carried() == 8 && count(0) == 0, "ordinary_click_consumes_pending_drag_before_menu_callbacks");
        click(0, 0, ClickType.PICKUP);
        check(clicks[0] == 1 && count(0) == 8 && carried() == 0, "next_ordinary_click_works_after_drag_reset");
        open(); allow(0); allow(1); cursor(8); begin(0, 0, 1); end(1);
        check(count(0) == 4 && count(1) == 4 && carried() == 0, "completion_uses_native_stored_mode_not_final_button_mode");
        open(); allow(0); allow(1); cursor(8); begin(0, 0, 1); cursor(1); end(0);
        check(count(0) == 0 && count(1) == 0 && carried() == 1, "insufficient_final_cursor_cancels_native_multi_distribution");
    }

    private static void callbackMutations() {
        for (String mutation : List.of("target_replace", "target_mutate", "target_equal_replace", "cursor_replace", "cursor_mutate", "slot_permission", "slot_capacity")) {
            open(); item(0, 2); item(1, 2); cursor(8);
            var active = current(); int[] selected = { -1 }, calls = { 0 };
            RestrictedSlot[] restrictions = { restrict(0, 64, true), restrict(1, 64, true) };
            for (int index : List.of(0, 1)) page.ctx.getSlot(index).setClickHandler(e -> {
                calls[0]++; selected[0] = e.getSlot();
                Slot target = active.getSlot(e.getSlot());
                switch (mutation) {
                    case "target_replace" -> target.set(new ItemStack(Items.DIRT, 5));
                    case "target_mutate" -> target.getItem().grow(1);
                    case "target_equal_replace" -> target.set(target.getItem().copy());
                    case "cursor_replace" -> e.setCursor(new ItemStack(Items.DIAMOND, 3));
                    case "cursor_mutate" -> active.getCarried().shrink(1);
                    case "slot_permission" -> restrictions[e.getSlot()].place = false;
                    case "slot_capacity" -> restrictions[e.getSlot()].limit = 2;
                }
            });
            begin(0, 0, 1); end(0);
            check(calls[0] == 1 && count(1 - selected[0]) == 2, mutation + "_aborts_remaining_proposals");
            check(count(selected[0]) == (mutation.equals("target_replace") ? 5 : mutation.equals("target_mutate") ? 3 : 2), mutation + "_preserves_requested_target");
            check(carried() == (mutation.equals("cursor_replace") ? 3 : mutation.equals("cursor_mutate") ? 7 : 8), mutation + "_preserves_actual_cursor");
            sync();
            for (int index : List.of(0, 1)) { page.ctx.getSlot(index).setClickHandler(e -> {}); item(index, 2); restrictions[index].place = true; restrictions[index].limit = 64; }
            cursor(8); begin(0, 0, 1); end(0);
            check(count(0) == 6 && count(1) == 6 && carried() == 0, mutation + "_next_gesture_reuses_clean_state");
        }
    }

    private static void lifecycle() {
        for (String action : List.of("close", "transition", "replace")) {
            open(); item(0, 2); item(1, 2); cursor(8); var old = current();
            int[] calls = { 0 };
            for (int index : List.of(0, 1)) page.ctx.getSlot(index).setClickHandler(e -> {
                if (++calls[0] != 2) return;
                if (action.equals("close")) menu.close();
                else if (action.equals("transition")) { page = new Page(); menu.transition(page); }
                else { menu.close(); page = new Page(); menu = InventoryMenu.create(page); menu.present(player); }
            });
            begin(0, 0, 1); end(0);
            check(player.containerMenu != old && old.getSlot(0).getItem().getCount() + old.getSlot(1).getItem().getCount() == 8,
                    action + "_preserves_only_first_completed_share");
            check(player.getInventory().countItem(Items.STONE) == 4 && player.containerMenu.getCarried().isEmpty(), action + "_returns_only_unplaced_cursor");
            check(content.stream().noneMatch(p -> p.getContainerId() == old.containerId), action + "_does_not_resend_retired_menu");
            old.clicked(-999, 2, ClickType.QUICK_CRAFT, player);
            check(old.getSlot(0).getItem().getCount() + old.getSlot(1).getItem().getCount() == 8, action + "_rejects_retired_drag_replay");
        }
        open(); cursor(8); allow(0); allow(1); begin(0, 0, 1); var old = current(); menu.close(); menu.present(player); end(0);
        check(current() != old && count(0) == 0 && count(1) == 0 && player.getInventory().countItem(Items.STONE) == 8,
                "close_between_packets_discards_old_gesture");
        open(); allow(0); allow(1); cursor(8); var observed = current();
        ((net.minecraft.world.SimpleContainer) observed.getMenuContainer()).addListener(c -> menu.close());
        begin(0, 0, 1); end(0);
        check(player.containerMenu != observed && observed.getSlot(0).getItem().getCount() + observed.getSlot(1).getItem().getCount() == 4,
                "native_container_listener_close_preserves_first_written_share");
        check(player.getInventory().countItem(Items.STONE) == 4 && player.containerMenu.getCarried().isEmpty(),
                "native_container_listener_close_cannot_duplicate_consumed_cursor");
    }

    private static void recursion() {
        open(); cursor(8); var active = current(); int[] calls = { 0 };
        for (int index : List.of(0, 1)) page.ctx.getSlot(index).setClickHandler(e -> {
            check(++calls[0] <= 2, "drag_callback_not_reentered");
            int before = carried();
            active.clicked(-999, 0, ClickType.QUICK_CRAFT, player);
            active.clicked(0, 0, ClickType.QUICK_MOVE, player);
            active.clicked(9, 0, ClickType.PICKUP_ALL, player);
            check(carried() == before, "nested_drag_shift_collection_are_guarded");
        });
        begin(0, 0, 1); end(0);
        check(calls[0] == 2 && count(0) == 4 && count(1) == 4 && carried() == 0, "outer_drag_completes_after_guarded_recursion");
        cursor(4); begin(0, 9, 10); end(0);
        check(player.getInventory().getItem(9).getCount() == 2 && player.getInventory().getItem(10).getCount() == 2 && carried() == 0,
                "player_inventory_drag_works_after_guard_cleanup");
    }

    private static void stockAndEquipment() {
        menu.close(); player.getInventory().clearContent();
        var storage = new net.citizensnpcs.trait.shop.NPCShopStorage();
        storage.setInventory(new ArrayList<>(List.of(new ItemStack(Items.STONE, 2))));
        menu = InventoryMenu.create(storage.createInventoryViewer(player)); menu.present(player); cursor(8);
        begin(0, 0, 1); end(0); menu.close();
        check(storage.getInventory().size() == 2 && storage.getInventory().get(0).getCount() == 6 && storage.getInventory().get(1).getCount() == 4,
                "actual_stock_editor_saves_dragged_shares");
        check(player.getInventory().countItem(Items.STONE) == 0, "stock_drag_consumes_cursor_exactly_once");

        var npc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry().createNPC(net.minecraft.world.entity.EntityType.COW, "DragEquipmentAudit");
        try {
            var equipment = npc.getOrAddTrait(net.citizensnpcs.api.trait.trait.Equipment.class);
            equipment.set(net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot.HAND, new ItemStack(Items.STONE, 2));
            menu = InventoryMenu.createWithContext(net.citizensnpcs.editor.GenericEquipperGUI.class, Map.of("npc", npc)); menu.present(player); cursor(8);
            begin(0, 9, 10);
            check(equipment.get(net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot.HAND).getCount() == 2,
                    "actual_equipment_editor_not_mutated_during_admission");
            end(0);
            check(equipment.get(net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot.HAND).getCount() == 6
                    && equipment.get(net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot.OFF_HAND).getCount() == 4
                    && current().getSlot(9).getItem().getCount() == 6 && current().getSlot(10).getItem().getCount() == 4 && carried() == 0,
                    "actual_equipment_editor_matches_final_dragged_inventory");
        } finally { menu.close(); npc.destroy(); }
    }

    private static void context(CitizensInventoryClickEvent e, MenuClickType click) {
        check(e.getWhoClicked() == player && e.getViewers().contains(player) && e.getClick() == click && e.getHotbarButton() == -1,
                "drag_callback_has_real_player_and_input_context");
    }
    private static void begin(int mode, int... slots) {
        click(-999, AbstractContainerMenu.getQuickcraftMask(0, mode), ClickType.QUICK_CRAFT);
        for (int slot : slots) click(slot, AbstractContainerMenu.getQuickcraftMask(1, mode), ClickType.QUICK_CRAFT);
    }
    private static void end(int mode) { click(-999, AbstractContainerMenu.getQuickcraftMask(2, mode), ClickType.QUICK_CRAFT); }
    private static void cursor(int count) { current().setCarried(new ItemStack(Items.STONE, count)); }
    private static int carried() { return player.containerMenu.getCarried().getCount(); }
    private static void sync() {
        var packets = content.stream().filter(p -> p.getContainerId() == player.containerMenu.containerId).toList();
        check(!packets.isEmpty(), "encoded_current_menu_full_state_sent");
        var packet = packets.getLast();
        check(ItemStack.matches(packet.getCarriedItem(), current().getCarried()), "encoded_cursor_matches_authoritative_cursor");
        boolean same = true;
        for (int i = 0; i < current().slots.size(); i++) same &= ItemStack.matches(packet.getItems().get(i), current().getSlot(i).getItem());
        check(same, "encoded_all_slots_match_authoritative_items");
    }
    private static RestrictedSlot restrict(int index, int limit, boolean place) {
        Slot original = current().getSlot(index);
        RestrictedSlot slot = new RestrictedSlot(original, limit, place); slot.index = index;
        current().slots.set(index, slot); return slot;
    }
    private static final class RestrictedSlot extends Slot {
        private int limit;
        private boolean place;
        RestrictedSlot(Slot original, int limit, boolean place) {
            super(original.container, original.getContainerSlot(), original.x, original.y); this.limit = limit; this.place = place;
        }
        @Override public int getMaxStackSize() { return limit; }
        @Override public boolean mayPlace(ItemStack stack) { return place; }
    }
    private static CitizensMenuContainer current() { return (CitizensMenuContainer) player.containerMenu; }
    private static void item(int slot, int count) { page.ctx.getSlot(slot).setItemStack(new ItemStack(Items.STONE, count)); }
    private static void allow(int slot) { page.ctx.getSlot(slot).setFilter(List.of()); }
    private static int count(int slot) { return page.ctx.getSlot(slot).getCurrentItemNonNull().getCount(); }
    private static void open() {
        if (menu != null) menu.close();
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
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
        passed++; LoggerFactory.getLogger("citizens").info("[MENUDRAGAUDIT] PASS {}", label);
    }
}
