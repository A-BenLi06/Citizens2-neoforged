package net.citizensnpcs.trait.shop;

import java.nio.file.*;
import java.util.*;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.Citizens;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.trait.ShopTrait;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.*;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
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
public final class ShopCommandRuntimeAudit {
    private record Received(String marker, boolean player, boolean elevated) { }
    private static final List<Received> received = new ArrayList<>();
    private static final List<ServerPlayer> players = new ArrayList<>();
    private static final List<EmbeddedChannel> channels = new ArrayList<>();
    private static Citizens plugin;
    private static NPC chestNPC, merchantNPC, rejectedNPC;
    private static AbstractContainerMenu chest, merchant;
    private static NPCShopAction.Transaction committed;
    private static int ready, passed;
    private static boolean done;

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done || event.getServer().getTickCount() < 10) return;
        MinecraftServer server = event.getServer(); plugin = Citizens.getInstance();
        try {
            if (ready == 0) {
                check(Files.isRegularFile(Path.of("shop-command-audit-fixture.txt")), "isolated_fixture");
                register(server);
                players.add(admit(server, "CommandBuyer")); players.add(admit(server, "MerchantBuyer"));
                queueCases(); oneShotCases();
                check(received.isEmpty(), "commands_do_not_execute_inside_purchase");
                ready = server.getTickCount() + 3;
            } else if (server.getTickCount() >= ready) {
                verify(); done = true;
                LoggerFactory.getLogger("citizens").info("[SHOPCOMMANDAUDIT] COMPLETE {} checks", passed);
            }
        } catch (Throwable failure) {
            done = true; LoggerFactory.getLogger("citizens").error("[SHOPCOMMANDAUDIT] FAILED", failure);
        } finally {
            if (done) {
                if (rejectedNPC != null && plugin.getNPCRegistry().getById(rejectedNPC.getId()) == rejectedNPC) rejectedNPC.destroy();
                for (ServerPlayer player : players) server.getPlayerList().remove(player);
                for (EmbeddedChannel channel : channels) channel.finishAndReleaseAll();
                server.halt(false);
            }
        }
    }

    private static void register(MinecraftServer server) {
        server.getCommands().getDispatcher().register(LiteralArgumentBuilder.<CommandSourceStack>literal("shopqueueaudit")
                .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("marker", StringArgumentType.word())
                        .executes(ctx -> {
                            received.add(new Received(StringArgumentType.getString(ctx, "marker"),
                                    ctx.getSource().getEntity() instanceof ServerPlayer, ctx.getSource().hasPermission(4)));
                            return 1;
                        })));
    }

    private static CommandAction command(String marker) { return new CommandAction(new ArrayList<>(List.of("shopqueueaudit " + marker))); }
    private static void buy(NPCShop shop, NPCShopItem item) {
        item.onClick(shop, shop.getStorage(), players.get(0), new InventoryMultiplexer(players.get(0).getInventory()), false, true);
    }

    private static void queueCases() {
        NPCShop shop = new NPCShop("command-cases");
        net.citizensnpcs.util.Util.runCommand(null, players.get(0), "shopqueueaudit legacy", false, true);
        NPCShopItem failedCost = new NPCShopItem();
        failedCost.getCost().add(command("failed-cost")); failedCost.getCost().add(new FailedAction());
        buy(shop, failedCost);
        NPCShopItem failedResult = new NPCShopItem();
        players.get(0).getInventory().setItem(0, new ItemStack(Items.EMERALD));
        failedResult.getCost().add(new ItemAction(new ItemStack(Items.EMERALD)));
        failedResult.getCost().add(command("refunded-cost"));
        failedResult.getResult().add(command("failed-result")); failedResult.getResult().add(new FailedAction());
        failedResult.timesPurchasable = 1;
        buy(shop, failedResult);
        check(count(players.get(0), Items.EMERALD) == 1 && failedResult.purchases.isEmpty(), "failed_result_refunds_cost_without_purchase_record");
        NPCShopItem thrown = new NPCShopItem();
        thrown.getCost().add(new ItemAction(new ItemStack(Items.EMERALD)));
        thrown.getResult().add(command("failed-thrown-result")); thrown.getResult().add(new FailedAction(true));
        buy(shop, thrown);
        check(count(players.get(0), Items.EMERALD) == 1, "throwing_result_refunds_cost");

        CommandAction shared = command("shared-success");
        NPCShopItem success = new NPCShopItem(); success.getResult().add(shared); buy(shop, success);
        NPCShopItem failure = new NPCShopItem(); failure.getResult().add(shared); failure.getResult().add(new FailedAction()); buy(shop, failure);

        CommandAction ordered = new CommandAction(new ArrayList<>(List.of("shopqueueaudit first", "shopqueueaudit second")));
        committed = ordered.grant(shop.getStorage(), players.get(0), new InventoryMultiplexer(players.get(0).getInventory()), 2);
        committed.run(); ordered.commands.clear(); ordered.commands.add("shopqueueaudit mutated");
        CommandAction op = command("op-player"); op.op = true;
        op.grant(shop.getStorage(), players.get(0), new InventoryMultiplexer(players.get(0).getInventory()), 1).run();
        CommandAction console = command("console"); console.server = true;
        console.grant(shop.getStorage(), players.get(0), new InventoryMultiplexer(players.get(0).getInventory()), 1).run();
        CommandAction consolePlayer = command("console-<p>"); consolePlayer.server = true;
        consolePlayer.grant(shop.getStorage(), players.get(0), new InventoryMultiplexer(players.get(0).getInventory()), 1).run();

        BrokenRefresh broken = new BrokenRefresh(players.get(0)); broken.fail = true;
        CommandAction partial = new CommandAction(List.of("shopqueueaudit partial-a", "shopqueueaudit partial-b"));
        try { partial.grant(shop.getStorage(), players.get(0), broken, 1).run(); throw new AssertionError("partial_should_fail"); }
        catch (IllegalStateException expected) { check(true, "scheduling_batch_failure_reported"); }
    }

    private static void oneShotCases() {
        for (ServerPlayer player : players) player.getInventory().clearContent();
        rejectedNPC = plugin.getNPCRegistry().createNPC(EntityType.PIG, "RejectedOneShot");
        NPCShop rejectedShop = rejectedNPC.getOrAddTrait(ShopTrait.class).getDefaultShop();
        NPCShopItem rejected = oneShot(rejectedNPC); rejected.getResult().add(new FailedAction());
        rejectedShop.getOrCreatePage(0).setItem(0, rejected);
        players.get(0).getInventory().setItem(0, new ItemStack(Items.EMERALD));
        rejectedShop.display(players.get(0)); players.get(0).containerMenu.clicked(0, 0, ClickType.PICKUP, players.get(0));
        check(count(players.get(0), Items.EMERALD) == 1 && count(players.get(0), Items.DIAMOND) == 0,
                "failed_destructive_purchase_refunds_items");
        chestNPC = plugin.getNPCRegistry().createNPC(EntityType.PIG, "OneShotChest");
        NPCShop chestShop = chestNPC.getOrAddTrait(ShopTrait.class).getDefaultShop();
        NPCShopItem chestItem = oneShot(chestNPC); chestShop.getOrCreatePage(0).setItem(0, chestItem);
        players.get(0).getInventory().setItem(0, new ItemStack(Items.EMERALD));
        chestShop.display(players.get(0)); chest = players.get(0).containerMenu;
        chest.clicked(0, 0, ClickType.PICKUP, players.get(0));
        check(count(players.get(0), Items.DIAMOND) == 1 && count(players.get(0), Items.EMERALD) == 0, "chest_purchase_commits_before_command");
        check(plugin.getNPCRegistry().getById(chestNPC.getId()) == chestNPC, "chest_owner_survives_until_dispatch");

        merchantNPC = plugin.getNPCRegistry().createNPC(EntityType.PIG, "OneShotMerchant");
        NPCShop merchantShop = merchantNPC.getOrAddTrait(ShopTrait.class).getDefaultShop();
        merchantShop.setShopType(ShopType.TRADER); merchantShop.getOrCreatePage(0).setItem(0, oneShot(merchantNPC));
        merchantShop.display(players.get(1)); merchant = players.get(1).containerMenu;
        check(merchant instanceof MerchantMenu, "one_shot_merchant_opens");
        merchant.getSlot(0).set(new ItemStack(Items.EMERALD, 5)); ((MerchantMenu) merchant).setSelectionHint(0);
        merchant.clicked(2, 0, ClickType.PICKUP, players.get(1));
        check(count(players.get(1), Items.DIAMOND) == 1 && merchant.getSlot(0).getItem().getCount() == 4,
                "merchant_payment_updates_before_command");
        check(plugin.getNPCRegistry().getById(merchantNPC.getId()) == merchantNPC, "merchant_owner_survives_until_dispatch");
    }

    private static NPCShopItem oneShot(NPC npc) {
        NPCShopItem item = new NPCShopItem(); item.setDisplayItem(new ItemStack(Items.DIAMOND));
        item.getCost().add(new ItemAction(new ItemStack(Items.EMERALD)));
        item.getResult().add(new ItemAction(new ItemStack(Items.DIAMOND)));
        CommandAction remove = new CommandAction(List.of("npc remove --id " + npc.getId())); remove.server = true;
        item.getResult().add(remove); item.globalTimesPurchasable = 1; return item;
    }

    private static void verify() {
        List<String> labels = received.stream().map(Received::marker).toList();
        check(Collections.frequency(labels, "legacy") == 1, "legacy_void_command_helper_still_dispatches_once");
        check(labels.stream().noneMatch(s -> s.startsWith("failed") || s.startsWith("refunded") || s.startsWith("partial")), "rolled_back_commands_never_dispatch");
        check(Collections.frequency(labels, "shared-success") == 1, "rollback_is_scoped_to_one_purchase");
        check(labels.stream().filter(s -> s.equals("first") || s.equals("second")).toList().equals(List.of("first", "second", "first", "second")), "command_order_repeats_and_snapshot_preserved");
        check(!labels.contains("mutated"), "later_action_edits_do_not_change_queued_commands");
        check(received.stream().anyMatch(r -> r.marker().equals("op-player") && r.player() && r.elevated()), "player_op_source_preserved");
        check(received.stream().anyMatch(r -> r.marker().equals("console") && !r.player() && r.elevated()), "console_source_preserved");
        check(received.stream().anyMatch(r -> r.marker().equals("console-CommandBuyer") && !r.player()),
                "console_commands_expand_clicker_without_changing_source");
        check(!plugin.getServer().getPlayerList().isOp(players.get(0).getGameProfile()), "temporary_op_does_not_change_op_list");
        int before = received.size(); committed.rollback();
        check(received.size() == before, "rollback_does_not_claim_to_undo_executed_commands");
        check(plugin.getNPCRegistry().getById(chestNPC.getId()) == null && plugin.getNPCRegistry().getById(merchantNPC.getId()) == null,
                "successful_real_command_actions_remove_both_owners");
        check(plugin.getNPCRegistry().getById(rejectedNPC.getId()) == rejectedNPC, "failed_purchase_does_not_remove_owner");
        check(players.stream().allMatch(p -> p.containerMenu == p.inventoryMenu), "one_shot_views_close_after_commit");
        check(count(players.get(1), Items.EMERALD) == 4 && count(players.get(1), Items.DIAMOND) == 1,
                "merchant_remainder_returned_once_after_delete");
        merchant.clicked(2, 0, ClickType.PICKUP, players.get(1));
        check(count(players.get(1), Items.EMERALD) == 4 && count(players.get(1), Items.DIAMOND) == 1, "stale_one_shot_click_cannot_duplicate_items");
    }

    private static final class FailedAction extends NPCShopAction {
        private final boolean throwing;
        FailedAction() { this(false); }
        FailedAction(boolean throwing) { this.throwing = throwing; }
        private Transaction transaction() {
            return throwing ? Transaction.create(() -> true, () -> { throw new IllegalStateException("expected action execution failure"); }, () -> {}) : Transaction.fail();
        }
        @Override public String describe() { return "unavailable fixture action"; }
        @Override public int getMaxRepeats(Entity entity, InventoryMultiplexer inventory) { return -1; }
        @Override public Transaction take(NPCShopStorage storage, Entity entity, InventoryMultiplexer inventory, int repeats) { return transaction(); }
        @Override public Transaction grant(NPCShopStorage storage, Entity entity, InventoryMultiplexer inventory, int repeats) { return transaction(); }
    }
    private static final class BrokenRefresh extends InventoryMultiplexer {
        boolean fail;
        BrokenRefresh(ServerPlayer player) { super(player.getInventory()); }
        @Override public void refresh() { if (fail) throw new IllegalStateException("expected inventory refresh failure"); super.refresh(); }
    }
    private static int count(ServerPlayer player, Item item) { return player.getInventory().items.stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum(); }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); passed++; LoggerFactory.getLogger("citizens").info("[SHOPCOMMANDAUDIT] PASS {}", label); }
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
