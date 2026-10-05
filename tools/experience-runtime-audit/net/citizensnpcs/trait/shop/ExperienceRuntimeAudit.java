package net.citizensnpcs.trait.shop;

import java.nio.file.*;
import java.util.UUID;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class ExperienceRuntimeAudit {
    private static ServerPlayer player;
    private static EmbeddedChannel channel;
    private static boolean done;
    private static int passed;
    private static Mode mode = Mode.NORMAL;
    private enum Mode { NORMAL, CANCEL, MODIFY, CLAMP, THROW }

    @SubscribeEvent public static void levelChange(PlayerXpEvent.LevelChange event) {
        if (event.getEntity() != player) return;
        switch (mode) {
            case NORMAL -> { }
            case CANCEL -> event.setCanceled(true);
            case MODIFY -> event.setLevels(-event.getLevels());
            case CLAMP -> event.setLevels(-Integer.MAX_VALUE);
            case THROW -> {
                player.experienceLevel = 0; player.experienceProgress = 0; player.totalExperience = 0;
                throw new IllegalStateException("expected XP listener failure");
            }
        }
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done || event.getServer().getTickCount() < 10) return;
        done = true;
        try {
            check(Files.isRegularFile(Path.of("experience-audit-fixture.txt")), "isolated_fixture");
            player = admit(event.getServer());
            player.experienceLevel = 10; player.experienceProgress = 0.35F; player.totalExperience = 1234;
            normalAndInvalid(); changedBalances(); eventFailures(); nativePurchase();
            LoggerFactory.getLogger("citizens").info("[EXPERIENCEAUDIT] COMPLETE {} checks", passed);
        } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[EXPERIENCEAUDIT] FAILED", failure); }
        finally {
            mode = Mode.NORMAL;
            if (player != null) { player.experienceLevel = 0; event.getServer().getPlayerList().remove(player); }
            if (channel != null) channel.finishAndReleaseAll();
            event.getServer().halt(false);
        }
    }

    private static InventoryMultiplexer inventory() { return new InventoryMultiplexer(player.getInventory()); }
    private static void unchangedProgress(String label) {
        check(player.experienceProgress == 0.35F && player.totalExperience == 1234, label);
    }
    private static void normalAndInvalid() {
        NPCShopStorage stock = new NPCShopStorage();
        ExperienceAction action = new ExperienceAction(2);
        var charge = action.take(stock, player, inventory(), 3);
        check(charge.isPossible(), "normal_charge_affordable"); charge.run();
        check(player.experienceLevel == 4, "exact_level_charge"); unchangedProgress("charge_keeps_fraction_and_points");
        charge.rollback(); check(player.experienceLevel == 10, "exact_level_refund");
        var grant = action.grant(stock, player, inventory(), 3); grant.run();
        check(player.experienceLevel == 16, "exact_level_reward"); grant.rollback();
        check(player.experienceLevel == 10, "reward_rollback");
        check(!new ExperienceAction(-2).take(stock, player, inventory(), 1).isPossible(), "negative_cost_rejected");
        check(!action.grant(stock, player, inventory(), -1).isPossible(), "negative_repeat_rejected");
        check(!new ExperienceAction(1 << 30).take(stock, player, inventory(), 4).isPossible(), "overflow_cannot_be_free_charge");
        check(!new ExperienceAction(Integer.MAX_VALUE).grant(stock, player, inventory(), 2).isPossible(), "overflow_cannot_be_negative_reward");
        check(new ExperienceAction(-1).getMaxRepeats(player, inventory()) == 0, "invalid_cost_has_no_affordable_repeats");
        check(new ExperienceAction(0).getMaxRepeats(player, inventory()) == -1, "free_cost_has_no_repeat_limit");
        new ExperienceAction(0).grant(stock, player, inventory(), Integer.MAX_VALUE).run();
        check(player.experienceLevel == 10, "free_reward_is_noop");
    }

    private static void changedBalances() {
        NPCShopStorage stock = new NPCShopStorage();
        player.experienceLevel = 5;
        var exact = new ExperienceAction(5).take(stock, player, inventory(), 1); exact.run();
        check(player.experienceLevel == 0, "exact_charge_reaches_zero"); unchangedProgress("zero_level_keeps_fraction_and_points");
        exact.rollback(); check(player.experienceLevel == 5, "zero_level_refund");
        var stale = new ExperienceAction(5).take(stock, player, inventory(), 1);
        check(stale.isPossible(), "initial_affordability"); player.experienceLevel = 4;
        reject(stale::run, "late_insufficient_charge_rejected");
        check(player.experienceLevel == 4, "late_charge_does_not_clamp_to_zero"); unchangedProgress("late_rejection_keeps_xp_metadata");
        player.experienceLevel = Integer.MAX_VALUE;
        var overflow = new ExperienceAction(1).grant(stock, player, inventory(), 1);
        check(!overflow.isPossible(), "reward_at_level_limit_unavailable");
        reject(overflow::run, "direct_reward_overflow_rejected");
        check(player.experienceLevel == Integer.MAX_VALUE, "reward_overflow_preserves_level");
        player.experienceLevel = 10;
        var refund = new ExperienceAction(2).take(stock, player, inventory(), 1); refund.run();
        player.experienceLevel = Integer.MAX_VALUE;
        reject(refund::rollback, "refund_overflow_rejected");
        check(player.experienceLevel == Integer.MAX_VALUE, "refund_overflow_preserves_level");
        player.experienceLevel = 10;
        var reward = new ExperienceAction(2).grant(stock, player, inventory(), 1); reward.run();
        player.experienceLevel = 1;
        reject(reward::rollback, "underfunded_reward_rollback_rejected");
        check(player.experienceLevel == 1, "rollback_does_not_erase_other_xp"); unchangedProgress("rollback_rejection_keeps_xp_metadata");
    }

    private static void nativePurchase() {
        player.experienceLevel = 10; player.getInventory().clearContent();
        NPCShop shop = new NPCShop("experience-batch");
        NPCShopItem item = new NPCShopItem(); item.setDisplayItem(new ItemStack(Items.DIAMOND));
        item.maxRepeatsOnShiftClick = true; item.getCost().add(new ExperienceAction(3));
        item.getResult().add(new ItemAction(new ItemStack(Items.DIAMOND)));
        shop.getOrCreatePage(0).setItem(0, item); shop.display(player);
        player.containerMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        int diamonds = player.getInventory().items.stream().filter(stack -> stack.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum();
        check(player.experienceLevel == 1 && diamonds == 3, "native_shift_purchase_uses_level_repeat_count");
        unchangedProgress("native_purchase_keeps_fraction_and_points");
        player.experienceLevel = 10;
        NPCShopItem failed = new NPCShopItem(); failed.getCost().add(new ExperienceAction(3));
        failed.getResult().add(new FailedAction());
        failed.onClick(shop, shop.getStorage(), player, inventory(), false, true);
        check(player.experienceLevel == 10, "failed_purchase_refunds_exact_levels");
        shop.retire();
    }

    private static void eventFailures() {
        NPCShopStorage stock = new NPCShopStorage(); player.experienceLevel = 10;
        for (Mode fault : new Mode[] { Mode.CANCEL, Mode.MODIFY, Mode.CLAMP, Mode.THROW }) {
            mode = fault;
            var charge = new ExperienceAction(2).take(stock, player, inventory(), 1);
            reject(charge::run, "native_event_failure_rejected_" + fault);
            check(player.experienceLevel == 10, "native_event_failure_restores_levels_" + fault);
            unchangedProgress("native_event_failure_restores_metadata_" + fault);
        }
        mode = Mode.NORMAL;
        var charge = new ExperienceAction(2).take(stock, player, inventory(), 1); charge.run();
        mode = Mode.CANCEL; reject(charge::rollback, "cancelled_refund_reported");
        check(player.experienceLevel == 8, "cancelled_refund_does_not_claim_success");
        mode = Mode.NORMAL; charge.rollback(); check(player.experienceLevel == 10, "refund_can_retry_after_event_recovery");
        NPCShop shop = new NPCShop("cancelled-xp-cost"); NPCShopItem item = new NPCShopItem();
        item.setDisplayItem(new ItemStack(Items.DIAMOND)); item.timesPurchasable = 1;
        item.getCost().add(new ExperienceAction(2)); item.getResult().add(new ItemAction(new ItemStack(Items.DIAMOND)));
        shop.getOrCreatePage(0).setItem(0, item); player.getInventory().clearContent(); shop.display(player);
        mode = Mode.CANCEL; player.containerMenu.clicked(0, 0, ClickType.PICKUP, player);
        check(player.experienceLevel == 10 && player.getInventory().isEmpty() && item.purchases.isEmpty(), "cancelled_xp_cost_does_not_give_free_items");
        mode = Mode.NORMAL; player.containerMenu.clicked(0, 0, ClickType.PICKUP, player);
        check(player.experienceLevel == 8 && item.purchases.getOrDefault(player.getUUID(), 0) == 1, "shop_retries_after_xp_event_recovers");
        shop.retire();
    }

    private static final class FailedAction extends NPCShopAction {
        @Override public String describe() { return "unavailable fixture action"; }
        @Override public int getMaxRepeats(Entity entity, InventoryMultiplexer inventory) { return -1; }
        @Override public Transaction take(NPCShopStorage stock, Entity entity, InventoryMultiplexer inventory, int repeats) { return Transaction.fail(); }
        @Override public Transaction grant(NPCShopStorage stock, Entity entity, InventoryMultiplexer inventory, int repeats) { return Transaction.fail(); }
    }
    private static void reject(Runnable operation, String label) { try { operation.run(); } catch (IllegalStateException expected) { check(true, label); return; } throw new AssertionError(label); }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); passed++; LoggerFactory.getLogger("citizens").info("[EXPERIENCEAUDIT] PASS {}", label); }
    private static ServerPlayer admit(MinecraftServer server) {
        ServerPlayer who = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), "ExperienceBuyer"), ClientInformation.createDefault());
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
