package net.yuuniverse.interactions;

import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "interactions")
public final class InventoryRuntimeAudit {
    private static boolean ran;

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (ran || event.getServer().getTickCount() < 70) return;
        ran = true;
        Session session = null;
        Session replacement = null;
        ServerPlayer player = null;
        DropRecorder recorder = null;
        try {
            var server = event.getServer();
            player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), "InventoryAudit"),
                    ClientInformation.createDefault());
            player.setPos(0, -55, 0);
            player.connection = new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), player,
                    CommonListenerCookie.createInitial(player.getGameProfile(), false));
            player.serverLevel().addNewPlayer(player);
            recorder = new DropRecorder(player);
            NeoForge.EVENT_BUS.register(recorder);
            player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
            player.getInventory().setItem(0, new ItemStack(Items.PAPER, 5));
            var settings = new AtomicReference<>(DialogueSettings.DEFAULT);
            var engine = new Session.Engine() {
                public Actions actions() { return new Actions(new ItemLibrary(), new Economy()); }
                public ProgressStore progress() { return new ProgressStore(new java.io.File("config/inventory-audit-progress")); }
                public DialogueSettings settings() { return settings.get(); }
            };
            var story = new Conversation();
            var node = new Conversation.Node("conversation1");
            session = new Session(engine, story, node, player, null);
            check(!DialogueInventory.isBlocked(player.getUUID()), "legacy_default_allows_inventory");
            settings.set(new DialogueSettings(false, false, false, List.of(), false, false));
            click(player, ClickType.PICKUP);
            check(player.getInventory().countItem(Items.PAPER) == 5 && player.containerMenu.getCarried().isEmpty(),
                    "pickup_rejected_without_item_loss");
            click(player, ClickType.QUICK_MOVE);
            check(player.getInventory().getItem(0).getCount() == 5, "quick_move_rejected");
            drop(player, ServerboundPlayerActionPacket.Action.DROP_ITEM);
            check(player.getInventory().countItem(Items.PAPER) == 5 && recorder.drops.isEmpty(), "single_drop_rejected_before_removal");
            drop(player, ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS);
            check(player.getInventory().countItem(Items.PAPER) == 5 && recorder.drops.isEmpty(), "stack_drop_rejected_before_removal");
            player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
            player.connection.handleSetCreativeModeSlot(new ServerboundSetCreativeModeSlotPacket(36, new ItemStack(Items.DIAMOND)));
            check(player.getInventory().countItem(Items.PAPER) == 5 && player.getInventory().countItem(Items.DIAMOND) == 0,
                    "creative_slot_replacement_rejected");
            player.connection.handleSetCreativeModeSlot(new ServerboundSetCreativeModeSlotPacket(-1, new ItemStack(Items.DIAMOND)));
            check(recorder.drops.isEmpty(), "creative_outside_drop_rejected");
            player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
            var item = new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);
            NeoForge.EVENT_BUS.post(item);
            check(item.isCanceled(), "main_hand_item_interaction_rejected");
            var block = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, BlockPos.ZERO,
                    new BlockHitResult(Vec3.ZERO, Direction.UP, BlockPos.ZERO, false));
            NeoForge.EVENT_BUS.post(block);
            check(block.isCanceled(), "main_hand_block_interaction_rejected");
            var left = new PlayerInteractEvent.LeftClickBlock(player, BlockPos.ZERO, Direction.UP,
                    PlayerInteractEvent.LeftClickBlock.Action.START);
            NeoForge.EVENT_BUS.post(left);
            check(left.isCanceled(), "left_click_block_rejected");
            item = new PlayerInteractEvent.RightClickItem(player, InteractionHand.OFF_HAND);
            NeoForge.EVENT_BUS.post(item);
            check(!item.isCanceled(), "legacy_offhand_exclusion_preserved");
            player.getInventory().add(new ItemStack(Items.PAPER));
            check(player.getInventory().countItem(Items.PAPER) == 6, "internal_inventory_mutation_is_allowed");
            replacement = new Session(engine, story, node, player, null);
            session.end(false);
            check(DialogueInventory.isBlocked(player.getUUID()), "stale_session_does_not_release_replacement");
            settings.set(DialogueSettings.DEFAULT);
            check(!DialogueInventory.isBlocked(player.getUUID()), "setting_change_applies_to_active_session");
            settings.set(new DialogueSettings(false, false, false, List.of(), false, false));
            replacement.end(false);
            click(player, ClickType.PICKUP);
            check(player.getInventory().countItem(Items.PAPER) == 0 && player.containerMenu.getCarried().getCount() == 6,
                    "normal_pickup_restored_after_end");
            click(player, ClickType.PICKUP);
            drop(player, ServerboundPlayerActionPacket.Action.DROP_ITEM);
            check(player.getInventory().countItem(Items.PAPER) == 5 && recorder.drops.size() == 1,
                    "normal_drop_restored_after_end");
            LoggerFactory.getLogger("interactions").info("[INVENTORYAUDIT] COMPLETE 16/16");
        } catch (Throwable failure) {
            LoggerFactory.getLogger("interactions").error("[INVENTORYAUDIT] FAILED", failure);
        } finally {
            if (session != null) session.end(false);
            if (replacement != null) replacement.end(false);
            if (recorder != null) {
                NeoForge.EVENT_BUS.unregister(recorder);
                recorder.drops.forEach(net.minecraft.world.entity.Entity::discard);
            }
            if (player != null) player.serverLevel().removePlayerImmediately(player,
                    net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        }
    }

    private static void click(ServerPlayer player, ClickType type) {
        player.connection.handleContainerClick(new ServerboundContainerClickPacket(player.containerMenu.containerId,
                player.containerMenu.getStateId(), 36, 0, type, ItemStack.EMPTY,
                new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>()));
    }

    private static void drop(ServerPlayer player, ServerboundPlayerActionPacket.Action action) {
        player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(action, BlockPos.ZERO, Direction.DOWN));
    }

    public static final class DropRecorder {
        final ServerPlayer player;
        final List<net.minecraft.world.entity.item.ItemEntity> drops = new ArrayList<>();
        DropRecorder(ServerPlayer player) { this.player = player; }
        @SubscribeEvent public void toss(ItemTossEvent event) {
            if (event.getPlayer() == player) drops.add(event.getEntity());
        }
    }

    private static void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        LoggerFactory.getLogger("interactions").info("[INVENTORYAUDIT] PASS {}", name);
    }
}
