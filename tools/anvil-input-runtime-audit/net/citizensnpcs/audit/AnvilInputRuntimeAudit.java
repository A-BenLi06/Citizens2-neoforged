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
public final class AnvilInputRuntimeAudit {
    private static boolean done, started;
    private static int passed, advancedCostPackets;
    private static ServerPlayer player, other;
    private static final List<EmbeddedChannel> channels = new ArrayList<>();
    private static final List<ClientboundContainerSetContentPacket> content = new ArrayList<>();
    private static final List<ClientboundContainerSetDataPacket> data = new ArrayList<>();
    private static final List<ClientboundOpenScreenPacket> screens = new ArrayList<>();
    private static final Deque<Runnable> steps = new ArrayDeque<>();
    private static InventoryMenu menu;
    private static AbstractContainerMenu saved;
    private static Page parent;
    private static final List<String> answers = new ArrayList<>();

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done || event.getServer().getTickCount() < 10) return;
        var server = event.getServer();
        try {
            if (!started) {
                started = true;
                check(Files.isRegularFile(Path.of("anvil-input-audit-fixture.txt")), "isolated_fixture");
                player = admit(server, "AnvilInputAudit");
                other = admit(server, "AnvilOther");
                factory(); values(); inventory(); lifecycle(); multiViewer(); consumers(); deferred();
            } else if (!steps.isEmpty()) {
                steps.removeFirst().run();
            } else {
                done = true;
                LoggerFactory.getLogger("citizens").info("[ANVILINPUTAUDIT] COMPLETE {} checks", passed);
            }
        } catch (Throwable failure) {
            done = true;
            LoggerFactory.getLogger("citizens").error("[ANVILINPUTAUDIT] FAILED", failure);
        } finally {
            if (done) {
                try {
                    if (menu != null) menu.close();
                    if (player != null) server.getPlayerList().remove(player);
                    if (other != null) server.getPlayerList().remove(other);
                    for (var channel : channels) channel.finishAndReleaseAll();
                } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[ANVILINPUTAUDIT] FAILED cleanup", failure); }
                server.halt(false);
            }
        }
    }

    private static ServerPlayer admit(net.minecraft.server.MinecraftServer server, String name) {
        ServerPlayer who = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault());
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            EmbeddedChannel admittedChannel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
                @Override protected void initChannel(Channel channel) {
                    connection.configurePacketHandler(channel.pipeline());
                    channel.pipeline().addLast("menu-capture", new ChannelOutboundHandlerAdapter() {
                        @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) throws Exception {
                            if (who == player && message instanceof ClientboundContainerSetContentPacket packet) {
                                RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
                                try {
                                    ClientboundContainerSetContentPacket.STREAM_CODEC.encode(buffer, packet);
                                    content.add(ClientboundContainerSetContentPacket.STREAM_CODEC.decode(buffer));
                                } finally { buffer.release(); }
                            }
                            if (who == player && message instanceof ClientboundOpenScreenPacket packet) screens.add(packet);
                            if (who == player && message instanceof ClientboundContainerSetDataPacket packet) data.add(packet);
                            if (who == player && message instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket packet
                                    && packet.payload() instanceof net.neoforged.neoforge.network.payload.AdvancedContainerSetDataPayload payload) {
                                RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
                                try {
                                    var codec = net.neoforged.neoforge.network.payload.AdvancedContainerSetDataPayload.STREAM_CODEC;
                                    codec.encode(buffer, payload); data.add(codec.decode(buffer).toVanillaPacket()); advancedCostPackets++;
                                } finally { buffer.release(); }
                            }
                            super.write(ctx, message, promise);
                        }
                    });
                }
            });
            NetworkRegistry.configureMockConnection(connection);
            var cookie = new CommonListenerCookie(who.getGameProfile(), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
            connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
            server.getPlayerList().placeNewPlayer(connection, who, cookie);

        channels.add(admittedChannel);
        return who;
    }

    private static void factory() {
        open("Initial", value -> { answers.add(value); return true; });
        check(current().getType() == net.minecraft.world.inventory.MenuType.ANVIL && current().slots.size() == 39, "native_type_and_slot_count");
        check(screens.getLast().getTitle().getString().equals("Input title"), "caller_title_sent");
        check(name(0).equals("Initial") && name(2).equals("Initial") && current().getSlot(1).getItem().isEmpty(), "initial_value_and_confirm_preview");
        check(current().getSlot(0).x == 27 && current().getSlot(1).x == 76 && current().getSlot(2).x == 134 && current().getSlot(3).y == 84,
                "native_anvil_and_inventory_positions");
        check(current().getCost() == 0, "no_experience_cost");
        rename("Edited"); check(name(0).equals("Edited") && name(2).equals("Edited") && answers.isEmpty(), "rename_packet_changes_only_draft"); sync();
        check(advancedCostPackets > 0, "neoforge_native_data_channel_is_captured");
        click(2, 0, ClickType.PICKUP);
        check(answers.equals(List.of("Edited")) && player.containerMenu instanceof CitizensMenuContainer && parent.initialisations == 2,
                "accepted_input_returns_and_refreshes_parent");
        menu.close();
        menu = InventoryMenu.create(InputMenus.stringSetter(() -> "Root", answers::add)); menu.present(player);
        check(player.containerMenu instanceof net.minecraft.world.inventory.AnvilMenu, "unfiltered_factory_is_native");
        click(2, 1, ClickType.PICKUP);
        check(answers.getLast().equals("Root") && player.containerMenu == player.inventoryMenu, "root_confirmation_closes_once");
    }

    private static void values() {
        open(null, value -> { answers.add(value); return false; });
        check(name(0).isEmpty() && name(2).isEmpty(), "null_initial_has_empty_custom_name");
        click(2, 0, ClickType.PICKUP);
        check(answers.size() == 1 && answers.getFirst() == null && player.containerMenu instanceof net.minecraft.world.inventory.AnvilMenu,
                "empty_input_is_null_and_rejection_stays_open");
        for (String value : List.of("null", "Not set", "NULL", "中文 😀", "  ", "same", "same", "x".repeat(50))) {
            rename(value); click(2, 0, ClickType.QUICK_MOVE);
            check(answers.getLast().equals(value) && name(2).equals(value), "literal_unicode_spaces_unchanged_or_max_length_" + answers.size()); sync();
        }
        rename("x".repeat(51));
        check(name(2).equals("x".repeat(50)), "overlength_rename_does_not_replace_draft");
        rename("a\u0000b\n§c");
        check(name(2).equals("abc"), "native_control_character_filter");
        rename(""); click(2, 0, ClickType.PICKUP);
        check(answers.getLast() == null, "clearing_text_submits_null_without_sentinel");
        var snapshot = content.getLast(); rename("later");
        check(snapshot.getItems().get(0).getHoverName().getString().isEmpty(), "captured_content_is_codec_detached");
    }

    private static void inventory() {
        open("Protected", value -> { answers.add(value); return false; });
        player.setExperienceLevels(0); player.setExperiencePoints(0);
        player.getInventory().setItem(9, new ItemStack(Items.DIAMOND, 7));
        player.getInventory().setItem(0, new ItemStack(Items.STONE, 5));
        current().setCarried(new ItemStack(Items.GOLD_INGOT, 3));
        for (int index = 0; index < 3; index++) {
            check(!current().getSlot(index).mayPlace(new ItemStack(Items.STONE)) && !current().getSlot(index).mayPickup(player), "control_slot_locked_" + index);
            for (ClickType type : List.of(ClickType.SWAP, ClickType.THROW, ClickType.CLONE, ClickType.PICKUP_ALL)) {
                click(index, type == ClickType.SWAP ? 40 : 0, type);
                check(current().getCarried().getCount() == 3 && player.getInventory().countItem(Items.PAPER) == 0 && name(0).equals("Protected"),
                        "control_cannot_escape_" + index + "_" + type);
            }
        }
        check(answers.isEmpty(), "nonconfirmation_controls_do_not_submit");
        click(2, 0, ClickType.PICKUP); sync();
        check(answers.equals(List.of("Protected")) && current().getCarried().getCount() == 3 && player.experienceLevel == 0 && player.totalExperience == 0,
                "confirmation_with_cursor_is_free_and_preserves_items");
        current().setCarried(ItemStack.EMPTY); click(3, 0, ClickType.QUICK_MOVE);
        check(player.getInventory().getItem(9).isEmpty() && player.getInventory().countItem(Items.DIAMOND) == 7, "player_shift_moves_to_hotbar_without_entering_inputs");
        current().setCarried(current().getSlot(0).getItem().copyWithCount(1));
        click(4, 0, ClickType.PICKUP_ALL);
        check(current().getCarried().getCount() == 1 && name(0).equals("Protected") && name(2).equals("Protected"), "double_click_does_not_collect_virtual_controls");
        current().setCarried(new ItemStack(Items.STONE, 8));
        click(-999, 0, ClickType.QUICK_CRAFT); click(0, 1, ClickType.QUICK_CRAFT); click(1, 1, ClickType.QUICK_CRAFT);
        click(2, 1, ClickType.QUICK_CRAFT); click(4, 1, ClickType.QUICK_CRAFT); click(5, 1, ClickType.QUICK_CRAFT); click(-999, 2, ClickType.QUICK_CRAFT);
        check(current().getSlot(4).getItem().getCount() == 4 && current().getSlot(5).getItem().getCount() == 4 && current().getCarried().isEmpty()
                && name(0).equals("Protected"), "drag_only_allocates_to_real_inventory_slots");
        int submitted = answers.size();
        current().setCarried(new ItemStack(Items.STONE, 4)); click(-999, 0, ClickType.QUICK_CRAFT); click(2, 0, ClickType.PICKUP);
        check(answers.size() == submitted && current().getCarried().getCount() == 4, "pending_drag_consumes_control_click");
        player.gameMode.changeGameModeForPlayer(GameType.CREATIVE); current().setCarried(ItemStack.EMPTY);
        click(0, 2, ClickType.CLONE); click(2, 2, ClickType.CLONE);
        check(current().getCarried().isEmpty(), "creative_clone_cannot_create_controls");
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        current().setCarried(new ItemStack(Items.EMERALD, 3)); click(0, 0, ClickType.PICKUP);
        check(player.containerMenu instanceof CitizensMenuContainer && player.getInventory().countItem(Items.EMERALD) == 3
                && player.getInventory().countItem(Items.PAPER) == 0, "cancel_returns_cursor_once_and_never_virtual_paper");
    }

    private static void lifecycle() {
        open("Before", value -> { answers.add(value); current().clicked(2, 0, ClickType.PICKUP, player); return false; });
        click(2, 0, ClickType.PICKUP);
        check(answers.equals(List.of("Before")), "recursive_confirmation_is_guarded");
        saved = current(); click(0, 0, ClickType.PICKUP);
        saved.clicked(2, 0, ClickType.PICKUP, player);
        ((net.minecraft.world.inventory.AnvilMenu) saved).setItemName("Stale");
        check(answers.size() == 1 && saved.getSlot(2).getItem().getHoverName().getString().equals("Before"), "stale_menu_cannot_submit_or_rename");
        open("Transition", value -> { answers.add(value); menu.transition(new Page()); return true; });
        click(2, 0, ClickType.PICKUP);
        check(parent.initialisations == 1 && player.containerMenu instanceof CitizensMenuContainer, "callback_transition_is_not_popped_again");
        open("Close", value -> { menu.close(); return true; }); click(2, 0, ClickType.PICKUP);
        check(player.containerMenu == player.inventoryMenu && parent.initialisations == 1, "callback_close_is_not_reopened");
        open("Other", value -> { answers.add(value); return true; });
        current().clicked(2, 0, ClickType.PICKUP, other);
        check(answers.isEmpty(), "wrong_player_cannot_submit");
        int oldId = current().containerId; menu.transition(new Page());
        player.connection.handleContainerClick(new ServerboundContainerClickPacket(oldId, 0, 2, 0, ClickType.PICKUP, ItemStack.EMPTY,
                new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>()));
        player.connection.handleRenameItem(new ServerboundRenameItemPacket("Stale packet"));
        check(answers.isEmpty(), "old_click_id_and_rename_on_nonanvil_are_ignored");
    }

    private static void multiViewer() {
        open("Shared", value -> { answers.add(value); return false; });
        menu.present(other);
        var first = current(); var second = (net.minecraft.world.inventory.AnvilMenu) other.containerMenu;
        rename("First"); other.connection.handleRenameItem(new ServerboundRenameItemPacket("Second"));
        check(first.getSlot(2).getItem().getHoverName().getString().equals("First")
                && second.getSlot(2).getItem().getHoverName().getString().equals("Second"), "viewer_drafts_are_independent");
        click(2, 0, ClickType.PICKUP); second.clicked(2, 0, ClickType.PICKUP, other);
        check(answers.equals(List.of("First", "Second")), "each_viewer_submits_own_draft");
        click(0, 0, ClickType.PICKUP);
        check(player.containerMenu instanceof CitizensMenuContainer && other.containerMenu instanceof CitizensMenuContainer,
                "shared_page_cancel_transitions_all_viewers");
    }

    private static void consumers() {
        menu.close(); player.getInventory().clearContent();
        var npc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry().createNPC(net.minecraft.world.entity.EntityType.COW, "AnvilNPC");
        try {
            menu = InventoryMenu.create(new net.citizensnpcs.commands.gui.NPCConfigurator(npc)); menu.present(player);
            click(0, 0, ClickType.PICKUP);
            check(player.containerMenu instanceof net.minecraft.world.inventory.AnvilMenu && name(0).equals("AnvilNPC"), "actual_npc_configurator_opens_rename_field");
            rename("Renamed NPC"); click(2, 0, ClickType.PICKUP);
            check(npc.getName().equals("Renamed NPC") && player.containerMenu instanceof CitizensMenuContainer
                    && player.containerMenu.getSlot(0).getItem().get(DataComponents.LORE).lines().getLast().getString().equals("Renamed NPC"),
                    "actual_npc_configurator_saves_name_and_refreshes_display");
        } finally { menu.close(); npc.destroy(); }
        open("stale chat".repeat(8), value -> { answers.add(value); return true; });
        InventoryMenu replacement = InventoryMenu.create(new Page()); replacement.present(player);
        var event = new net.neoforged.neoforge.event.ServerChatEvent(player, "ordinary chat", Component.literal("ordinary chat"));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event);
        check(!event.isCanceled() && answers.isEmpty(), "replaced_chat_page_does_not_swallow_ordinary_chat");
        replacement.close();
        event = new net.neoforged.neoforge.event.ServerChatEvent(player, "after replacement closes", Component.literal("chat"));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event);
        check(!event.isCanceled() && answers.isEmpty(), "replaced_chat_cannot_reactivate_when_inventory_screen_returns");
        open("line\nbreak", value -> true);
        check(player.containerMenu == player.inventoryMenu, "unrepresentable_initial_uses_chat_without_character_loss");
    }

    private static void deferred() {
        steps.add(() -> {
            open("Escape", value -> { answers.add(value); return true; }); saved = current();
            current().setCarried(new ItemStack(Items.EMERALD, 2));
            player.connection.handleContainerClose(new ServerboundContainerClosePacket(current().containerId));
            check(player.containerMenu == player.inventoryMenu && answers.isEmpty(), "escape_waits_until_native_close_finishes");
        });
        steps.add(() -> {});
        steps.add(() -> {
            check(player.containerMenu instanceof CitizensMenuContainer && parent.initialisations == 2 && player.getInventory().countItem(Items.EMERALD) == 2,
                    "escape_returns_parent_next_tick_and_cursor_once");
            open("Escape close", value -> true);
            player.connection.handleContainerClose(new ServerboundContainerClosePacket(current().containerId)); menu.close();
        });
        steps.add(() -> {});
        steps.add(() -> {
            check(player.containerMenu == player.inventoryMenu, "explicit_close_cancels_pending_return");
            open("Escape replaced", value -> true);
            player.connection.handleContainerClose(new ServerboundContainerClosePacket(current().containerId));
            InventoryMenu replacement = InventoryMenu.create(new Page()); replacement.present(player); saved = player.containerMenu;
        });
        steps.add(() -> {});
        steps.add(() -> {
            check(player.containerMenu == saved, "pending_return_does_not_replace_new_screen");
            open("L".repeat(51), value -> { answers.add(value); return false; });
            check(player.containerMenu == player.inventoryMenu && menu.getViewers().contains(player), "overlength_initial_uses_owned_chat_without_truncation");
            chat("A".repeat(80));
        });
        steps.add(() -> {});
        steps.add(() -> {
            check(answers.equals(List.of("A".repeat(80))), "long_chat_input_is_complete_and_rejection_reprompts");
            chat("null");
        });
        steps.add(() -> {});
        steps.add(() -> {
            check(answers.getLast().equals("null"), "page_chat_preserves_literal_sentinel");
            menu.close(); chat("after close");
            check(answers.size() == 2, "closed_chat_prompt_unregistered");
            open("L".repeat(51), value -> { answers.add(value); return true; }); chat("accepted");
        });
        steps.add(() -> {});
        steps.add(() -> {
            check(answers.equals(List.of("accepted")) && player.containerMenu instanceof CitizensMenuContainer,
                    "accepted_chat_fallback_returns_parent");
            open("L".repeat(51), value -> { answers.add(value); return true; }); chat("queued"); menu.transition(new Page());
        });
        steps.add(() -> {});
        steps.add(() -> {
            check(answers.isEmpty() && player.containerMenu instanceof CitizensMenuContainer, "queued_chat_cannot_mutate_replacement_page");
            open("explicit", value -> true);
            InputMenus.runChatStringSetter(menu, player, "Explicit chat", answers::add); chat("null");
        });
        steps.add(() -> {});
        steps.add(() -> check(answers.equals(List.of("")) && player.containerMenu instanceof net.minecraft.world.inventory.AnvilMenu,
                "explicit_chat_api_keeps_existing_empty_string_semantics"));
        steps.add(() -> {
            open("Two escape", value -> true); menu.present(other);
            player.connection.handleContainerClose(new ServerboundContainerClosePacket(current().containerId));
            check(other.containerMenu instanceof CitizensMenuContainer, "escape_immediately_returns_other_viewer");
        });
        steps.add(() -> {});
        steps.add(() -> {
            check(player.containerMenu instanceof CitizensMenuContainer && menu.getViewers().size() == 2,
                    "escape_rejoins_closing_viewer_to_shared_parent");
            open("Escape transition", value -> true);
            player.connection.handleContainerClose(new ServerboundContainerClosePacket(current().containerId));
            menu.transition(new Page());
        });
        steps.add(() -> {});
        steps.add(() -> {
            check(player.containerMenu == player.inventoryMenu, "later_transition_cancels_pending_return");
            open("queued long".repeat(6), value -> { answers.add(value); return true; });
            chat("queued before temporary screen");
            InventoryMenu temporary = InventoryMenu.create(new Page()); temporary.present(player); temporary.close();
        });
        steps.add(() -> {});
        steps.add(() -> {
            check(answers.isEmpty(), "queued_chat_is_cancelled_by_temporary_screen");
            open("Session", value -> { answers.add(value); return true; });
            saved = current(); player.getServer().getPlayerList().remove(player);
            saved.clicked(2, 0, ClickType.PICKUP, player);
            check(!saved.stillValid(player) && answers.isEmpty(), "removed_player_session_cannot_submit");
        });
    }

    private static void open(String initial, java.util.function.Function<String, Boolean> callback) {
        if (menu != null) menu.close();
        player.closeContainer(); player.getInventory().clearContent(); answers.clear(); content.clear(); screens.clear(); data.clear();
        parent = new Page(); menu = InventoryMenu.create(parent); menu.present(player);
        menu.transition(InputMenus.filteredStringSetter("Input title", () -> initial, callback)); drain();
        if (initial == null || initial.length() <= 50 && initial.equals(net.minecraft.util.StringUtil.filterText(initial))) check(player.containerMenu instanceof net.minecraft.world.inventory.AnvilMenu, "factory_opens_native_anvil");
    }
    private static net.minecraft.world.inventory.AnvilMenu current() { return (net.minecraft.world.inventory.AnvilMenu) player.containerMenu; }
    private static String name(int slot) { return current().getSlot(slot).getItem().getHoverName().getString(); }
    private static void rename(String name) { player.connection.handleRenameItem(new ServerboundRenameItemPacket(name)); drain(); }
    private static void click(int slot, int button, ClickType type) {
        player.connection.handleContainerClick(new ServerboundContainerClickPacket(player.containerMenu.containerId, player.containerMenu.getStateId(),
                slot, button, type, ItemStack.EMPTY, new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>())); drain();
    }
    private static void chat(String value) {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.ServerChatEvent(player, value, Component.literal(value)));
    }
    private static void sync() {
        var packet = content.stream().filter(p -> p.getContainerId() == current().containerId).toList().getLast();
        boolean same = ItemStack.matches(packet.getCarriedItem(), current().getCarried());
        for (int i = 0; i < current().slots.size(); i++) same &= ItemStack.matches(packet.getItems().get(i), current().getSlot(i).getItem());
        check(same, "encoded_full_content_matches_current_draft_inventory_and_cursor");
        check(data.stream().filter(p -> p.getContainerId() == current().containerId).toList().getLast().getValue() == 0, "zero_cost_prediction_corrected");
    }
    private static void drain() {
        for (var channel : channels) {
            channel.runPendingTasks(); Object output; while ((output = channel.readOutbound()) != null) ReferenceCountUtil.release(output);
        }
    }
    @Menu(dimensions = { 1, 9 }) public static final class Page extends InventoryMenuPage {
        int initialisations;
        @Override public void initialise(MenuContext ctx) { initialisations++; }
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++; LoggerFactory.getLogger("citizens").info("[ANVILINPUTAUDIT] PASS {}", label);
    }
}
