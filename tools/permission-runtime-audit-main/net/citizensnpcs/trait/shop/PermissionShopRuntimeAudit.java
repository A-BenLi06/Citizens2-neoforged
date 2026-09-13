package net.citizensnpcs.trait.shop;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.citizensnpcs.api.util.EconomyProvider;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.slf4j.LoggerFactory;

/** Opt-in only: actual shop callbacks and actual Paradigm records, with a controlled wallet for compensation. */
public final class PermissionShopRuntimeAudit {
    public static int run(ServerPlayer player) throws Exception {
        return new Audit(player).run();
    }

    private static final class Audit {
        final ServerPlayer player;
        final String prefix = "permissionshop." + UUID.randomUUID().toString().substring(0, 8) + ".";
        final Object handler;
        final Class<?> handlerType, contextType;
        final Method info, assignments, entryId;
        final Map<String, String> before;
        int passed;

        Audit(ServerPlayer player) throws Exception {
            this.player = player;
            Object services = Class.forName("eu.avalanche7.paradigm.Paradigm").getMethod("getServices").invoke(null);
            handler = Class.forName("eu.avalanche7.paradigm.core.Services").getMethod("getPermissionsHandler").invoke(services);
            handlerType = Class.forName("eu.avalanche7.paradigm.modules.permissions.PermissionsHandler");
            contextType = Class.forName("eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet");
            info = handlerType.getMethod("getPlayerPermissionInfo", UUID.class);
            assignments = Class.forName("eu.avalanche7.paradigm.modules.permissions.PermissionAPI$UserInfo").getMethod("assignments");
            entryId = Class.forName("eu.avalanche7.paradigm.modules.permissions.PermissionAssignment").getMethod("id");
            before = snapshot();
        }

        int run() throws Exception {
            var originalWriter = PermissionUtil.getPermissionWriter();
            try {
                check(PermissionUtil.canWritePermissions(), "real_permission_writer_installed");
                String simple = prefix + "simple";
                check(PermissionUtil.addPermission(player, simple) && PermissionUtil.hasPermission(player, simple), "basic_write_grants_real_permission");
                Map<String, String> granted = snapshot();
                check(PermissionUtil.addPermission(player, simple) && snapshot().equals(granted), "repeated_basic_grant_is_idempotent");
                check(PermissionUtil.removePermission(player, simple) && !PermissionUtil.hasPermission(player, simple), "basic_write_removes_real_global_grant");
                check(!PermissionUtil.removePermission(player, simple), "basic_remove_reports_missing_record");

                String first = prefix + "first", second = prefix + "second", redirected = prefix + "redirected";
                List<String> mutable = new ArrayList<>(List.of(first, second));
                var action = new PermissionAction(mutable);
                var change = action.grant(player, 1);
                mutable.clear(); mutable.add(redirected);
                Map<String, String> initial = snapshot();
                check(change.isPossible() && snapshot().equals(initial), "permission_action_preparation_is_read_only");
                change.run();
                check(PermissionUtil.hasPermission(player, first) && PermissionUtil.hasPermission(player, second)
                        && !PermissionUtil.hasPermission(player, redirected), "permission_action_copies_resolved_targets");
                change.rollback(); change.rollback();
                check(snapshot().equals(initial), "multi_permission_rollback_restores_exact_record_set");

                String unicode = prefix + "中文.*";
                var unicodeGrant = new PermissionAction(List.of(unicode)).grant(player, 1);
                unicodeGrant.run();
                check(PermissionUtil.hasPermission(player, prefix + "中文.child"), "writer_handles_unicode_and_wildcard_permissions");
                unicodeGrant.rollback();
                check(snapshot().equals(initial), "unicode_grant_rollback_uses_provider_id");

                String policy = prefix + "signed-rule";
                if (!PermissionUtil.addPermission(player, policy)) throw new AssertionError("Could not prepare signed-rule fixture");
                Map<String, String> beforeDeny = snapshot();
                var deny = new PermissionAction(List.of("- " + policy.toUpperCase(java.util.Locale.ROOT))).grant(player, 1);
                deny.run();
                check(!PermissionUtil.hasPermission(player, policy), "signed_permission_action_writes_a_real_denial");
                deny.rollback(); deny.rollback();
                check(PermissionUtil.hasPermission(player, policy) && snapshot().equals(beforeDeny),
                        "signed_rule_rollback_restores_existing_positive_access");
                check(PermissionUtil.addPermission(player, "-" + policy) && !PermissionUtil.hasPermission(player, policy),
                        "basic_writer_supports_explicit_signed_rules");
                check(PermissionUtil.removePermission(player, "-" + policy) && snapshot().equals(beforeDeny),
                        "removing_signed_rule_does_not_remove_same_named_allow");

                String guarded = prefix + "guarded";
                rule(guarded, true, Map.of(), null);
                rule(guarded, false, Map.of("dimension", "minecraft:the_nether"), null);
                rule(guarded, false, Map.of(), System.currentTimeMillis() + 300_000L);
                Map<String, String> protectedRules = snapshot();
                var guardedGrant = new PermissionAction(List.of(guarded)).grant(player, 1);
                guardedGrant.run();
                check(!PermissionUtil.hasPermission(player, guarded), "adding_allow_does_not_override_existing_denial");
                guardedGrant.rollback();
                check(snapshot().equals(protectedRules), "rollback_keeps_denied_contextual_and_temporary_records");
                check(PermissionUtil.addPermission(player, guarded), "basic_add_coexists_with_protected_rules");
                check(PermissionUtil.removePermission(player, guarded) && snapshot().equals(protectedRules), "basic_remove_only_deletes_permanent_global_allow");

                String timed = prefix + "timed", contextual = prefix + "contextual";
                rule(timed, false, Map.of(), System.currentTimeMillis() + 300_000L);
                rule(contextual, false, Map.of("dimension", "minecraft:overworld"), null);
                check(PermissionUtil.hasPermission(player, timed) && !new PermissionAction(List.of(timed)).take(player, 1).isPossible(),
                        "temporary_permission_cannot_pay_a_permanent_permission_cost");
                check(PermissionUtil.hasPermission(player, contextual) && !new PermissionAction(List.of(contextual)).take(player, 1).isPossible(),
                        "contextual_permission_cannot_pay_a_global_permission_cost");

                shopCallbacks(originalWriter);
                check(PermissionUtil.getPermissionWriter() == originalWriter, "shop_fixture_restores_permission_provider");
                LoggerFactory.getLogger("citizens").info("[PERMISSIONSHOPAUDIT] COMPLETE {} checks", passed);
                return passed;
            } finally {
                PermissionUtil.setPermissionWriter(originalWriter);
                for (String id : snapshot().keySet()) {
                    if (!before.containsKey(id)) removeId(id);
                }
                if (!snapshot().equals(before)) throw new AssertionError("Permission shop audit cleanup changed original records");
            }
        }

        void shopCallbacks(PermissionUtil.PermissionWriter writer) throws Exception {
            EconomyProvider oldEconomy = EconomyProvider.getProvider();
            var wallet = new Wallet(); EconomyProvider.setProvider(wallet);
            try {
                var storage = new NPCShopStorage(); storage.setUnlimited(false); storage.setBalance(50);
                var inventory = new InventoryMultiplexer(player.getInventory());
                var money = new MoneyAction(); money.money = 10;
                var shop = new NPCShop("PermissionAudit");
                String existing = prefix + "existing", purchased = prefix + "purchased";
                check(PermissionUtil.addPermission(player, existing), "seed_existing_global_grant");
                Map<String, String> original = snapshot();
                var reward = new Reward();
                var item = new NPCShopItem();
                item.getCost().add(money);
                item.getResult().add(new PermissionAction(List.of(existing, purchased)));
                item.getResult().add(reward);
                item.getResult().add(new RejectedReward());
                item.onClick(shop, storage, player, inventory, false, true);
                check(wallet.balance == 100 && storage.getBalance() == 50 && reward.delivered == 0,
                        "later_shop_failure_refunds_money_and_reward");
                check(snapshot().equals(original), "later_shop_failure_preserves_existing_grant_and_removes_only_new_grant");
                item.getResult().removeLast();
                item.onClick(shop, storage, player, inventory, false, true);
                check(wallet.balance == 90 && storage.getBalance() == 60 && reward.delivered == 1
                        && PermissionUtil.hasPermission(player, purchased), "successful_shop_purchase_delivers_permission_and_reward");

                String token = "permissionaudit.inherited.writer-token-" + UUID.randomUUID().toString().substring(0, 8);
                check(PermissionUtil.hasPermission(player, token) && !new PermissionAction(List.of(token)).take(player, 1).isPossible(),
                        "inherited_access_does_not_mint_a_permission_payment");
                check(PermissionUtil.addPermission(player, token), "seed_direct_token_alongside_inherited_access");
                var tokenReward = new Reward(); var tokenItem = new NPCShopItem();
                tokenItem.getCost().add(new PermissionAction(List.of(token))); tokenItem.getResult().add(tokenReward);
                tokenItem.onClick(shop, storage, player, inventory, false, true);
                tokenItem.onClick(shop, storage, player, inventory, false, true);
                check(tokenReward.delivered == 1 && PermissionUtil.hasPermission(player, token), "permission_cost_consumes_direct_token_once_without_removing_inheritance");

                String refunded = prefix + "refunded-token";
                check(PermissionUtil.addPermission(player, refunded), "seed_refundable_permission_token");
                Map<String, String> beforeTake = snapshot();
                var failedTrade = new NPCShopItem(); failedTrade.getCost().add(new PermissionAction(List.of(refunded)));
                failedTrade.getResult().add(new RejectedReward());
                failedTrade.onClick(shop, storage, player, inventory, false, true);
                check(snapshot().equals(beforeTake), "failed_reward_restores_consumed_permission_with_same_id");

                final int[] calls = {0};
                if (!PermissionUtil.addPermission(player, "citizens.npc.shop.editor.actions.edit-permission"))
                    throw new AssertionError("Could not grant the fixture editor permission");
                check(new PermissionAction.PermissionActionGUI().canUse(player), "permission_editor_available_with_authorization_and_receipts");
                PermissionUtil.setPermissionWriter(new PermissionUtil.PermissionWriter() {
                    public boolean add(ServerPlayer p, String permission) { calls[0]++; return true; }
                    public boolean remove(ServerPlayer p, String permission) { calls[0]++; return true; }
                });
                check(PermissionUtil.canWritePermissions() && !PermissionUtil.canWriteReversiblePermissions()
                        && !new PermissionAction.PermissionActionGUI().canUse(player), "permission_editor_hides_unsupported_reversible_actions");
                var unsupported = new NPCShopItem(); unsupported.getCost().add(money);
                unsupported.getResult().add(new PermissionAction(List.of(prefix + "unsupported")));
                unsupported.getResult().add(new Reward());
                double balance = wallet.balance, till = storage.getBalance();
                unsupported.onClick(shop, storage, player, inventory, false, true);
                check(calls[0] == 0 && wallet.balance == balance && storage.getBalance() == till,
                        "shop_refuses_writer_without_reversible_capability_and_refunds_payment");
                PermissionUtil.setPermissionWriter(writer);
            } finally { EconomyProvider.setProvider(oldEconomy); PermissionUtil.setPermissionWriter(writer); }
        }

        Map<String, String> snapshot() throws Exception {
            Object state = info.invoke(handler, player.getUUID());
            Collection<?> records = (Collection<?>) assignments.invoke(state);
            Map<String, String> result = new LinkedHashMap<>();
            for (Object entry : records) result.put((String) entryId.invoke(entry), entry.toString());
            return result;
        }

        void rule(String permission, boolean denied, Map<String, String> context, Long expiry) throws Exception {
            Object scope = contextType.getMethod("of", Map.class).invoke(null, context);
            Object result = handlerType.getMethod("addPermissionToPlayer", UUID.class, String.class, boolean.class, contextType, Long.class)
                    .invoke(handler, player.getUUID(), permission, denied, scope, expiry);
            if (!Boolean.TRUE.equals(result)) throw new AssertionError("Could not create fixture permission rule");
        }

        void removeId(String id) throws Exception {
            if (!Boolean.TRUE.equals(handlerType.getMethod("removePermissionFromPlayerById", UUID.class, String.class)
                    .invoke(handler, player.getUUID(), id))) throw new AssertionError("Could not clean fixture permission rule");
        }

        void check(boolean condition, String name) {
            if (!condition) throw new AssertionError(name);
            passed++; LoggerFactory.getLogger("citizens").info("[PERMISSIONAUDIT] PASS {}", name);
        }
    }

    private static class Reward extends NPCShopAction {
        int delivered;
        public String describe() { return "Audit reward"; }
        public int getMaxRepeats(Entity entity, InventoryMultiplexer inventory) { return 1; }
        public Transaction grant(NPCShopStorage storage, Entity entity, InventoryMultiplexer inventory, int repeats) {
            return Transaction.create(() -> true, () -> delivered += repeats, () -> delivered -= repeats);
        }
        public Transaction take(NPCShopStorage storage, Entity entity, InventoryMultiplexer inventory, int repeats) { return Transaction.fail(); }
    }

    private static final class RejectedReward extends Reward {
        @Override public Transaction grant(NPCShopStorage storage, Entity entity, InventoryMultiplexer inventory, int repeats) {
            return Transaction.create(() -> true, () -> { throw new IllegalStateException("Injected later reward failure"); }, () -> {});
        }
    }

    private static final class Wallet implements EconomyProvider {
        double balance = 100;
        public double getBalance(ServerPlayer player) { return balance; }
        public String format(double amount) { return Double.toString(amount); }
        public boolean deposit(ServerPlayer player, double amount) { balance += amount; return true; }
        public boolean withdraw(ServerPlayer player, double amount) {
            if (balance < amount) return false;
            balance -= amount; return true;
        }
    }
}
