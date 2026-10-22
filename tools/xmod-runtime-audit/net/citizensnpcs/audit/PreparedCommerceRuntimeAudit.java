package net.citizensnpcs.audit;

import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.task.CustomTask;
import net.citizensnpcs.util.*;
import net.citizensnpcs.util.CommerceTaskJournal.*;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.yuuniverse.economy.api.*;
import net.yuuniverse.economy.core.*;

/** Lost native commit notification followed by original-owner disconnect, with no payment replay. */
final class PreparedCommerceRuntimeAudit {
    private static int passed;
    static void seedFixture(Path database,String shop,String currency)throws Exception { FinancialSeed.seed(database,shop,currency); }
    static void run(ServerPlayer administrator,ServerQuestFile file,EconomyApi economy)throws Exception {
        var server=administrator.getServer();var runtime=(net.yuuniverse.economy.EconomyRuntime)economy;var api=economy.commerceApi().orElseThrow();
        var service=CommerceQuestTasks.current(server);var console=server.createCommandSourceStack();
        var ownerId=UUID.randomUUID();var profile=new GameProfile(ownerId,"PreparedOwner");
        ServerPlayer owner=new ServerPlayer(server,server.overworld(),profile,ClientInformation.createDefault());owner.setPos(1,-60,1);
        var channel=XModRuntimeAudit.connect(owner);
        try {
            economy.ensurePlayerAccount(ownerId,profile.getName());var currency=economy.defaultCurrency();
            economy.credit(ownerId,currency.id(),1000,"prepared-seed:"+ownerId,"fixture");
            String shop="prepared-shop-"+ownerId;FinancialSeed.seed(runtime.ledger().databasePath(),shop,currency.id());
            var chapter=new Chapter(file.newID(),file,file.getDefaultChapterGroup());chapter.onCreated();
            var quest=new Quest(file.newID(),chapter);quest.onCreated();var task=new CustomTask(file.newID(),quest);task.onCreated();task.setMaxProgress(4);
            file.clearCachedData();file.clearCachedProgress();var team=file.getTeamData(owner).orElseThrow();
            var binding=service.bind(owner.createCommandSourceStack().withPermission(2),task.getCodeString(),Shop.SYSTEM,shop,"paper",Action.BUY_FROM_SHOP,Measure.ITEMS,null,true);
            var request=new CommerceRequest(CommerceShopType.SYSTEM,shop,"paper",CommerceAction.BUY_FROM_SHOP,AccountIds.player(ownerId),1,"prepared:"+ownerId,"audit:suppress-commit");
            var outcome=api.execute(ownerId,api.quote(ownerId,request));
            var entry=service.entries(console).stream().filter(e->e.binding().equals(binding)).findFirst().orElseThrow();
            check(entry.state()==State.PREPARED && entry.receipt()==null && team.getProgress(task)==0 && owner.getInventory().countItem(Items.PAPER)==1,
                    "actual_missed_commit_retains_only_original_pretrade_attribution");
            var identity=new JsonObject();identity.addProperty("owner",ownerId.toString());identity.addProperty("team",team.getTeamId().toString());identity.addProperty("entry",entry.id());
            identity.addProperty("operation",outcome.receipt().operationId().toString());identity.addProperty("transaction",outcome.receipt().transactionId().toString());identity.addProperty("requestKey",request.idempotencyKey());
            Files.writeString(server.getServerDirectory().resolve("prepared-request-state.json"),identity.toString());
            var party=dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().getManager().createPartyTeam(owner,"PreparedParty"+ownerId,"fixture",dev.ftb.mods.ftblibrary.icon.Color4I.rgb(0x335577));
            var currentTeam=file.getTeamData(owner).orElseThrow();
            server.getPlayerList().remove(owner);channel.finishAndReleaseAll();channel=null;owner=null;
            long balance=economy.balance(ownerId,currency.id()),transactions=runtime.ledger().audit().transactionCount();var position=api.inspectOperation(ownerId,outcome.receipt().operationId()).position();
            check(server.getPlayerList().getPlayer(ownerId)==null,"original_request_owner_is_actually_disconnected_before_reconciliation");
            int count=service.reconcile(console);
            var recovered=service.entries(console).stream().filter(e->e.id().equals(entry.id())).findFirst().orElseThrow();
            check(count==1 && recovered.state()==State.APPLIED && recovered.attribution().equals(entry.attribution())
                    && recovered.receipt().operationId().equals(outcome.receipt().operationId()) && recovered.receipt().transactionId().equals(outcome.receipt().transactionId()) && team.getProgress(task)==1
                    && !currentTeam.getTeamId().equals(team.getTeamId()) && currentTeam.getProgress(task)==0,
                    "offline_prepared_request_discovers_original_receipt_and_projects_original_team_without_reconnect");
            check(service.reconcile(console)==0 && economy.balance(ownerId,currency.id())==balance && runtime.ledger().audit().transactionCount()==transactions
                    && api.inspectOperation(ownerId,outcome.receipt().operationId()).position().equals(position) && team.getProgress(task)==1,
                    "offline_prepared_recovery_is_idempotent_without_payment_delivery_or_history_mutation");
            owner=new ServerPlayer(server,server.overworld(),profile,ClientInformation.createDefault());owner.setPos(1,-60,1);channel=XModRuntimeAudit.connect(owner);
            owner.getInventory().clearContent();for(int slot=0;slot<owner.getInventory().items.size();slot++)owner.getInventory().setItem(slot,new ItemStack(Items.STONE,64));
            var pendingRequest=new CommerceRequest(CommerceShopType.SYSTEM,shop,"paper",CommerceAction.BUY_FROM_SHOP,AccountIds.player(ownerId),1,"prepared-pending:"+ownerId,"audit:suppress-commit");
            var pending=api.execute(ownerId,api.quote(ownerId,pendingRequest));
            check(pending.state()==CommerceOutcome.State.DELIVERY_PENDING && service.entries(console).stream().anyMatch(e->pendingRequest.idempotencyKey().equals(e.request().requestKey())&&e.state()==State.PREPARED),
                    "full_inventory_and_missed_notification_leave_an_actual_unretained_pending_delivery");
            var failedRequest=new CommerceRequest(CommerceShopType.SYSTEM,shop,"paper",CommerceAction.BUY_FROM_SHOP,AccountIds.player(ownerId),1,"prepared-uncommitted:"+ownerId,"audit:freeze-after-start");
            var terms=api.quote(ownerId,failedRequest);
            boolean failed=false;
            try{api.execute(ownerId,terms);}catch(ShopApiException expected){failed=true;}finally{runtime.ledger().setAccountFrozen(AccountIds.player(ownerId),false,"fixture","uncommitted-request-complete");}
            check(failed && api.inspectRequest(ownerId,failedRequest).entry().isEmpty() && service.entries(console).stream().anyMatch(e->failedRequest.idempotencyKey().equals(e.request().requestKey())&&e.state()==State.PREPARED),
                    "failed_native_commit_retains_a_request_without_invented_provider_evidence");
            identity.addProperty("pendingKey",pendingRequest.idempotencyKey());identity.addProperty("pendingOperation",pending.receipt().operationId().toString());identity.addProperty("pendingTransaction",pending.receipt().transactionId().toString());
            identity.addProperty("uncommittedKey",failedRequest.idempotencyKey());identity.addProperty("laterTeam",currentTeam.getTeamId().toString());
            Files.writeString(server.getServerDirectory().resolve("prepared-request-state.json"),identity.toString());
            server.getPlayerList().remove(owner);channel.finishAndReleaseAll();channel=null;owner=null;
            var originals=service.entries(console);balance=economy.balance(ownerId,currency.id());transactions=runtime.ledger().audit().transactionCount();
            YuuniverseEconomyApi.clear(economy);boolean unavailable=false;
            try{service.reconcile(console);}catch(IllegalStateException expected){unavailable=true;}finally{YuuniverseEconomyApi.publish(economy);}
            check(unavailable && service.entries(console).equals(originals) && economy.balance(ownerId,currency.id())==balance,"unavailable_provider_holds_offline_recovery_without_money_or_entry_mutation");
            var savedInspection=api.inspectOperation(ownerId,outcome.receipt().operationId());
            var legacyCommerce=(CommerceApi)java.lang.reflect.Proxy.newProxyInstance(CommerceApi.class.getClassLoader(),new Class<?>[]{CommerceApi.class},(proxy,method,args)->switch(method.getName()){
                case "version"->4;case "isAvailable"->true;case "inspectOperation"->savedInspection;case "history"->List.of();case "journalPosition"->savedInspection.position();
                default->throw new AssertionError("Legacy provider must not inspect requests or trade: "+method.getName());
            });
            var legacy=(EconomyApi)java.lang.reflect.Proxy.newProxyInstance(EconomyApi.class.getClassLoader(),new Class<?>[]{EconomyApi.class},(proxy,method,args)->{
                if(method.getName().equals("isAvailable"))return true;
                if(method.getName().equals("commerceApi"))return Optional.of(legacyCommerce);
                throw new AssertionError("Recovery must not pay through the legacy provider");
            });
            YuuniverseEconomyApi.clear(economy);YuuniverseEconomyApi.publish(legacy);boolean unsupported=false;
            try{service.reconcile(console);}catch(IllegalStateException expected){unsupported=expected.getMessage().contains("revision 5");}
            finally{YuuniverseEconomyApi.clear(legacy);YuuniverseEconomyApi.publish(economy);}
            check(unsupported && service.entries(console).equals(originals) && economy.balance(ownerId,currency.id())==balance,"capability_four_reports_unresolved_request_requirement_without_posting_or_changing_entries");
            check(service.reconcile(console)==1 && currentTeam.getProgress(task)==0 && service.entries(console).stream().anyMatch(e->e.receipt()!=null&&e.receipt().operationId().equals(pending.receipt().operationId())&&e.state()==State.WAITING)
                    && service.entries(console).stream().anyMatch(e->failedRequest.idempotencyKey().equals(e.request().requestKey())&&e.state()==State.PREPARED),
                    "offline_discovery_retains_waiting_delivery_and_leaves_missing_request_prepared");
            check(economy.balance(ownerId,currency.id())==balance && runtime.ledger().audit().transactionCount()==transactions
                    && runtime.ledger().pendingDeliveryClaims(AccountIds.player(ownerId)).size()==1,"offline_discovery_does_not_charge_or_deliver_pending_inventory");
            owner=new ServerPlayer(server,server.overworld(),profile,ClientInformation.createDefault());owner.setPos(1,-60,1);channel=XModRuntimeAudit.connect(owner);owner.getInventory().setItem(0,ItemStack.EMPTY);
            check(api.claimDeliveries(ownerId)==1 && owner.getInventory().countItem(Items.PAPER)==1,"actual_provider_delivery_finishes_before_owner_disconnects_again");
            server.getPlayerList().remove(owner);channel.finishAndReleaseAll();channel=null;owner=null;
            check(service.reconcile(console)==0 && currentTeam.getProgress(task)==1 && team.getProgress(task)==1
                    && service.entries(console).stream().anyMatch(e->e.receipt()!=null&&e.receipt().operationId().equals(pending.receipt().operationId())&&e.state()==State.APPLIED),
                    "settled_delivery_projects_its_original_later_team_without_an_online_contributor");
            check(service.reconcile(console)==0 && economy.balance(ownerId,currency.id())==balance && runtime.ledger().audit().transactionCount()==transactions && runtime.ledger().audit().valid(),
                    "repeated_offline_recovery_preserves_balanced_money_and_does_not_invent_uncommitted_receipts");
            org.slf4j.LoggerFactory.getLogger("citizens").info("[PREPAREDCOMMERCEAUDIT] COMPLETE {} checks",passed);
        } finally {if(owner!=null&&owner.connection!=null)server.getPlayerList().remove(owner);if(channel!=null)channel.finishAndReleaseAll();}
    }
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;org.slf4j.LoggerFactory.getLogger("citizens").info("[PREPAREDCOMMERCEAUDIT] PASS {}",name);}
    private static final class FinancialSeed {
        static void seed(Path database,String shop,String currency)throws Exception{
            try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+database);
                var s=c.prepareStatement("INSERT INTO system_shop VALUES (?,?,'fixture','fixture','{}',1)");
                var p=c.prepareStatement("INSERT INTO system_shop_page VALUES (?,'page1',0,'Prepared fixture',3,'{}')");
                var o=c.prepareStatement("INSERT INTO system_shop_item (shop_id,item_key,page_key,slot_key,legacy_material,item_config_json,currency_id,buy_price_minor_units,sell_price_minor_units,dynamic_pricing,enabled) VALUES (?,'paper','page1','1','PAPER','{\"material\":\"PAPER\"}',?,10,4,0,1)")){
                s.setString(1,shop);s.setString(2,shop);s.executeUpdate();p.setString(1,shop);p.executeUpdate();o.setString(1,shop);o.setString(2,currency);o.executeUpdate();
            }
        }
    }
}
