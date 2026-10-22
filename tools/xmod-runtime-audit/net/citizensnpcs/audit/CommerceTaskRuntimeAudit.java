package net.citizensnpcs.audit;

import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.task.CustomTask;
import dev.ftb.mods.ftbquests.quest.reward.CustomReward;
import net.citizensnpcs.util.*;
import net.citizensnpcs.util.CommerceTaskJournal.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.yuuniverse.economy.api.*;
import net.yuuniverse.economy.core.*;

/** Actual public Economy/FTB contracts. Listener faults and SQL setup exist only in this opt-in fixture. */
@EventBusSubscriber(modid="citizens")
public final class CommerceTaskRuntimeAudit {
    private static int passed;
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void beforeStart(ServerAboutToStartEvent event) {
        // Register before Citizens' ServerStarted subscriptions to test a genuinely missed native observation.
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, CommerceStartingEvent.class, e -> {
            if ("audit:suppress-start".equals(e.request().origin())) throw new IllegalStateException("Fixture suppresses starting dispatch");
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, CommerceCommittedEvent.class, e -> {
            if ("audit:suppress-commit".equals(e.entry().origin())) throw new IllegalStateException("Fixture suppresses committed dispatch");
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, CommerceStartingEvent.class, e -> {
            if ("audit:freeze-after-start".equals(e.request().origin()))
                ((net.yuuniverse.economy.EconomyRuntime)YuuniverseEconomyApi.current().orElseThrow()).ledger()
                        .setAccountFrozen(e.request().playerAccountId(),true,"fixture","failure-after-native-attribution");
        });
    }
    public static void run(ServerPlayer actor, ServerQuestFile file, EconomyApi economy) throws Exception {
        var server=actor.getServer(); var source=actor.createCommandSourceStack().withPermission(2);
        var service=CommerceQuestTasks.current(server); var api=economy.commerceApi().orElseThrow();
        var runtime=(net.yuuniverse.economy.EconomyRuntime)economy; var ledger=runtime.ledger();
        var currency=economy.defaultCurrency(); var team=file.getTeamData(actor).orElseThrow();
        String shop="commerce-task-"+UUID.randomUUID(), offer="component-paper";
        seed(ledger.databasePath(), shop, currency.id());
        economy.credit(actor.getUUID(),currency.id(),10000,"commerce-seed:"+UUID.randomUUID(),"fixture");
        actor.closeContainer(); actor.getInventory().clearContent();
        var previous=execute(api,actor,request(actor,shop,offer,CommerceAction.BUY_FROM_SHOP,1,"audit:before-bind"));
        var chapter=new Chapter(file.newID(),file,file.getDefaultChapterGroup()); chapter.onCreated();
        var buy=task(file,chapter,4,true); var reward=new CustomReward(file.newID(),buy.getQuest()); reward.onCreated();
        var trades=task(file,chapter,100,false); var money=task(file,chapter,1000,false);
        var settlement=task(file,chapter,100,false); var sell=task(file,chapter,100,false);
        var missed=task(file,chapter,100,false); var craft=task(file,chapter,4,false);
        file.clearCachedData(); file.clearCachedProgress();
        var binding=service.bind(source,buy.getCodeString(),Shop.SYSTEM,shop,offer,Action.BUY_FROM_SHOP,Measure.ITEMS,null,true);
        service.bind(source,trades.getCodeString(),Shop.SYSTEM,shop,offer,Action.BUY_FROM_SHOP,Measure.TRADES,null,false);
        service.bind(source,money.getCodeString(),Shop.SYSTEM,shop,offer,Action.BUY_FROM_SHOP,Measure.MINOR_UNITS,currency.id(),false);
        service.bind(source,settlement.getCodeString(),Shop.SYSTEM,shop,offer,Action.BUY_FROM_SHOP,Measure.ITEMS,null,false);
        var sellBinding=service.bind(source,sell.getCodeString(),Shop.SYSTEM,shop,offer,Action.SELL_TO_SHOP,Measure.ITEMS,null,true);
        service.bind(source,missed.getCodeString(),Shop.SYSTEM,shop,offer,Action.BUY_FROM_SHOP,Measure.ITEMS,null,false);
        var recipe=net.minecraft.resources.ResourceLocation.parse("citizens_audit:xmod_recipe");
        CraftQuestEvidence.current(server).bind(source,craft.getCodeString(),recipe);
        check(service.reconcile(source)==0 && team.getProgress(buy)==0 && binding.startingSequence()>=api.journalPosition(actor.getUUID()).sequence(),"binding_does_not_retroactively_credit_historical_receipts");
        check(service.bind(source,buy.getCodeString(),Shop.SYSTEM,shop,offer,Action.BUY_FROM_SHOP,Measure.ITEMS,null,true).epoch().equals(binding.epoch()),"identical_binding_keeps_native_epoch");
        try { service.bind(source,craft.getCodeString(),Shop.SYSTEM,shop,offer,Action.BUY_FROM_SHOP,Measure.ITEMS,null,true); throw new AssertionError("Cross-source binding accepted"); }
        catch(IllegalStateException expected) { check(true,"commerce_cannot_own_a_recipe_bound_native_task"); }
        try { CraftQuestEvidence.current(server).bind(source,buy.getCodeString(),recipe); throw new AssertionError("Cross-source craft accepted"); }
        catch(IllegalStateException expected) { check(true,"craft_cannot_own_a_receipt_bound_native_task"); }
        var request=request(actor,shop,offer,CommerceAction.BUY_FROM_SHOP,2,"audit:system");
        long before=economy.balance(actor.getUUID(),currency.id());
        var bought=execute(api,actor,request);
        check(bought.state()==CommerceOutcome.State.COMMITTED && economy.balance(actor.getUUID(),currency.id())==before-20
                && team.getProgress(buy)==2 && team.getProgress(trades)==2 && team.getProgress(money)==20,"native_system_buy_projects_actual_item_trade_and_currency_measures");
        var entry=entry(service,source,binding,bought.receipt().operationId());
        check(entry.state()==State.APPLIED && entry.attribution().teamId().equals(team.getTeamId()) && entry.receipt().itemSnapshot().equals(bought.receipt().itemSnapshot())
                && actor.getInventory().items.stream().anyMatch(stack->stack.is(Items.PAPER) && stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)),"committed_evidence_retains_native_team_and_exact_item_components");
        api.execute(actor.getUUID(),api.quote(actor.getUUID(),request)); service.reconcile(source);
        check(team.getProgress(buy)==2 && economy.balance(actor.getUUID(),currency.id())==before-20,"duplicate_provider_execution_and_history_replay_do_not_double_count_or_charge");
        check(team.getProgress(craft)==0,"buying_the_same_recipe_output_cannot_masquerade_as_crafting");
        var sold=execute(api,actor,request(actor,shop,offer,CommerceAction.SELL_TO_SHOP,2,"audit:sell"));
        check(sold.state()==CommerceOutcome.State.COMMITTED && team.getProgress(sell)==2 && team.getProgress(buy)==2
                && economy.balance(actor.getUUID(),currency.id())==before-12,"native_system_sell_consumes_items_credits_money_and_matches_only_sell_task");
        check(entry(service,source,sellBinding,sold.receipt().operationId()).request().action()==Action.SELL_TO_SHOP,"receipt_retains_exact_trade_direction");
        var rewards=FtbMonetaryRewards.current(server);
        rewards.bind(source,reward.getCodeString(),currency.id(),java.math.BigDecimal.valueOf(375,currency.scale()));
        execute(api,actor,request(actor,shop,offer,CommerceAction.BUY_FROM_SHOP,2,"audit:complete"));
        check(team.getProgress(buy)==4,"receipt_total_completes_repeatable_native_task");
        before=economy.balance(actor.getUUID(),currency.id()); int cycle=team.getCompletionCount(buy.getQuest());
        team.claimReward(actor,reward,false);
        check(team.getProgress(buy)==0 && team.getCompletionCount(buy.getQuest())==cycle+1 && economy.balance(actor.getUUID(),currency.id())==before+375,"commerce_completion_uses_existing_native_repeat_reward_generation");
        team.claimReward(actor,reward,false); service.reconcile(source);
        check(team.getProgress(buy)==0 && economy.balance(actor.getUUID(),currency.id())==before+375,"replaying_old_receipts_after_reward_reset_cannot_complete_next_cycle");
        execute(api,actor,request(actor,shop,offer,CommerceAction.BUY_FROM_SHOP,2,"audit:next-cycle"));
        check(team.getProgress(buy)==2,"new_trade_enters_distinct_native_repeat_cycle");
        for(int slot=0;slot<actor.getInventory().getContainerSize();slot++) actor.getInventory().setItem(slot,new ItemStack(Items.DIAMOND,64));
        long oldSettlement=team.getProgress(settlement); var pending=execute(api,actor,request(actor,shop,offer,CommerceAction.BUY_FROM_SHOP,2,"audit:full"));
        check(pending.state()==CommerceOutcome.State.DELIVERY_PENDING && team.getProgress(buy)==2 && team.getProgress(settlement)==oldSettlement+2
                && entry(service,source,binding,pending.receipt().operationId()).state()==State.WAITING,"full_inventory_receipt_waits_for_delivery_under_explicit_task_policy");
        actor.getInventory().setItem(0,ItemStack.EMPTY); check(api.claimDeliveries(actor.getUUID())==1,"provider_completes_actual_pending_item_delivery");
        service.reconcile(source);
        check(team.getProgress(buy)==4 && entry(service,source,binding,pending.receipt().operationId()).state()==State.APPLIED,"checkpointed_waiting_receipt_recovers_when_native_delivery_completes");
        team.claimReward(actor,reward,false); actor.getInventory().clearContent();
        long missedBefore=team.getProgress(missed);
        var unattributed=execute(api,actor,request(actor,shop,offer,CommerceAction.BUY_FROM_SHOP,2,"audit:suppress-start"));
        var missedBinding=service.bindings(source).stream().filter(b->b.taskId().equals(missed.getCodeString())).findFirst().orElseThrow();
        var uncertain=entry(service,source,missedBinding,unattributed.receipt().operationId());
        check(uncertain.state()==State.REVIEW && uncertain.attribution()==null && team.getProgress(missed)==missedBefore,"missed_starting_notification_retains_receipt_for_review_without_inventing_team");
        service.resolve(source,uncertain.id(),team.getTeamId(),0,true,"Fixture confirms native team at exact committed trade");
        check(team.getProgress(missed)==missedBefore+2 && entry(service,source,missedBinding,unattributed.receipt().operationId()).resolution().operatorId().equals(actor.getUUID()),"authenticated_review_records_operator_and_exact_historical_attribution");
        // Other affected tasks remain explicitly uncertain; reject without minting any progress.
        for(var value:service.entries(source)) if(value.state()==State.REVIEW && value.receipt().operationId().equals(unattributed.receipt().operationId()))
            service.resolve(source,value.id(),team.getTeamId(),value.binding().repeatable()?team.getCompletionCount(buy.getQuest()):0,false,"Fixture rejects remaining unattributed evidence");
        long beforeBuy=team.getProgress(buy); var suppressedRequest=request(actor,shop,offer,CommerceAction.BUY_FROM_SHOP,2,"audit:suppress-commit");
        var suppressed=execute(api,actor,suppressedRequest);
        check(team.getProgress(buy)==beforeBuy && service.entries(source).stream().anyMatch(e->e.binding().equals(binding)
                && suppressedRequest.idempotencyKey().equals(e.request().requestKey()) && e.state()==State.PREPARED),"missed_commit_notification_leaves_durable_precommit_attribution");
        var party=dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().getManager().createPartyTeam(actor,"CommerceParty"+actor.getUUID(),"fixture",dev.ftb.mods.ftblibrary.icon.Color4I.rgb(0x445577));
        var currentTeam=file.getTeamData(actor).orElseThrow(); service.reconcile(source);
        check(currentTeam.getTeamId().equals(party.getId()) && currentTeam.getProgress(buy)==0 && team.getProgress(buy)==beforeBuy+2
                && entry(service,source,binding,suppressed.receipt().operationId()).attribution().teamId().equals(team.getTeamId()),"history_replay_after_party_change_uses_original_trade_team_and_cycle");
        var coordinate=task(file,chapter,20,false); file.clearCachedData(); file.clearCachedProgress();
        var pos=new BlockPos(5,-60,5); actor.setPos(4,-60,4); server.overworld().setBlock(pos,Blocks.CHEST.defaultBlockState(),3);
        // This exact location is dedicated to the isolated fixture; remove its previous native fixture shop on rerun.
        var existing=economy.coordinateShops().orElseThrow().shop(actor.getUUID(),new CoordinateShopReference.Position(server.overworld().dimension().location().toString(),5,-60,5));
        if(existing.isPresent()) ledger.removeCoordinateShop(existing.get().id(),"fixture-repeat");
        Container stock=(Container)server.overworld().getBlockEntity(pos); stock.setItem(0,new ItemStack(Items.STONE,10));
        var owner=economy.ensurePlayerAccount(UUID.randomUUID(),"CommerceTaskOwner");
        var playerShop=ledger.createCoordinateShop(new CoordinateShopDraft(server.overworld().dimension().location().toString(),"fixture",5,-60,5,owner.id(),
                "SELL_TO_PLAYER","{\"material\":\"STONE\"}","STONE",currency.id(),10,1,false,10L,false,0,null),"fixture");
        var coordinateBinding=service.bind(source,coordinate.getCodeString(),Shop.COORDINATE,playerShop.id(),"",Action.BUY_FROM_SHOP,Measure.ITEMS,null,true);
        var finite=new CommerceRequest(CommerceShopType.COORDINATE,playerShop.id(),"",CommerceAction.BUY_FROM_SHOP,AccountIds.player(actor.getUUID()),2,"finite:"+UUID.randomUUID(),"audit:finite");
        before=economy.balance(actor.getUUID(),currency.id()); var finiteReceipt=execute(api,actor,finite);
        check(stock.getItem(0).getCount()==8 && ledger.coordinateShopDetails(playerShop.id()).orElseThrow().availableStock()==8L
                && actor.getInventory().countItem(Items.STONE)==2 && economy.balance(actor.getUUID(),currency.id())==before-20
                && currentTeam.getProgress(coordinate)==2 && entry(service,source,coordinateBinding,finiteReceipt.receipt().operationId()).state()==State.APPLIED,"actual_finite_coordinate_trade_correlates_physical_stock_money_and_task_receipt");
        var retired=new ServerPlayer(server,server.overworld(),actor.getGameProfile(),net.minecraft.server.level.ClientInformation.createDefault());
        try { service.replay(retired); throw new AssertionError("Retired actor accepted"); } catch(IllegalStateException expected) { check(true,"receipt_replay_rejects_retired_same_uuid_session"); }
        try { service.entries(source.withPermission(0)); throw new AssertionError("Unauthorized read accepted"); } catch(IllegalStateException expected) { check(true,"commerce_binding_and_review_require_live_operator_authority"); }
        check(java.util.concurrent.CompletableFuture.supplyAsync(()->{try { service.entries(source); return false; } catch(IllegalStateException expected){return true;}}).join(),"receipt_administration_rejects_worker_thread_access");
        var definition=new net.minecraft.nbt.CompoundTag(); definition.putBoolean("can_repeat",false); buy.getQuest().readData(definition,server.registryAccess());
        var changed=execute(api,actor,request(actor,shop,offer,CommerceAction.BUY_FROM_SHOP,2,"audit:reload"));
        check(currentTeam.getProgress(buy)==0 && entry(service,source,binding,changed.receipt().operationId()).state()==State.REVIEW,"native_quest_redefinition_is_fenced_without_reinterpreting_receipts");
        check(ledger.audit().valid(),"ledger_remains_balanced_after_receipt_projection_and_rewards");
        ledger.removeCoordinateShop(playerShop.id(),"fixture-cleanup");
        definition.putBoolean("can_repeat",true); buy.getQuest().readData(definition,server.registryAccess());
        ((dev.ftb.mods.ftbteams.data.PartyTeam)party).leave(actor.getUUID());
        org.slf4j.LoggerFactory.getLogger("citizens").info("[COMMERCETASKAUDIT] COMPLETE {} checks",passed);
    }
    public static void crash(net.minecraft.server.MinecraftServer server,String phase) {
        ServerPlayer actor=null; io.netty.channel.embedded.EmbeddedChannel channel=null;
        try {
            var statePath=server.getServerDirectory().resolve("commerce-crash-state.json");
            var json=new com.google.gson.Gson(); boolean recovery=phase.equals("recover");
            var state=recovery?com.google.gson.JsonParser.parseString(Files.readString(statePath)).getAsJsonObject():new com.google.gson.JsonObject();
            UUID actorId=recovery?UUID.fromString(state.get("actor").getAsString()):UUID.randomUUID();
            actor=new ServerPlayer(server,server.overworld(),new com.mojang.authlib.GameProfile(actorId,"CommerceCrash"),net.minecraft.server.level.ClientInformation.createDefault());
            actor.setPos(1,-60,1); channel=XModRuntimeAudit.connect(actor);
            var source=actor.createCommandSourceStack().withPermission(2); var service=CommerceQuestTasks.current(server);
            var file=ServerQuestFile.getInstance().orElseThrow(); var team=file.getTeamData(actor).orElseThrow();
            var economy=YuuniverseEconomyApi.current().orElseThrow(); var api=economy.commerceApi().orElseThrow();
            if(recovery) {
                var task=file.getTask(Long.parseUnsignedLong(state.get("task").getAsString(),16));
                var evidence=service.entries(source).stream().filter(e->e.id().equals(state.get("entry").getAsString())).findFirst().orElseThrow();
                State retained=switch(state.get("stage").getAsString()) {case "receipt"->State.PREPARED;case "applied"->State.APPLIED;default->State.CONFIRMED;};
                check(team.getProgress(task)==0 && evidence.state()==retained,"actual_process_restart_retains_old_ftb_save_and_durable_receipt_phase");
                service.reconcile(source); evidence=service.entries(source).stream().filter(e->e.id().equals(state.get("entry").getAsString())).findFirst().orElseThrow();
                check(evidence.state()==State.APPLIED && evidence.receipt().operationId().toString().equals(state.get("operation").getAsString())
                        && evidence.receipt().transactionId().toString().equals(state.get("transaction").getAsString()) && team.getProgress(task)==1,"actual_process_restart_replays_original_receipt_into_absolute_native_task_progress");
                check(economy.balance(actorId,state.get("currency").getAsString())==state.get("balance").getAsLong()-10,"actual_process_kill_keeps_one_economy_settlement");
                check(api.pendingDeliveries(actorId).stream().anyMatch(c->c.claimId().toString().equals(state.get("delivery").getAsString())),"full_inventory_delivery_claim_survives_process_kill");
                actor.getInventory().setItem(0,ItemStack.EMPTY); check(api.claimDeliveries(actorId)==1 && actor.getInventory().countItem(Items.PAPER)==1,"restarted_pending_delivery_supplies_exact_original_item_once");
                service.reconcile(source); check(team.getProgress(task)==1 && api.claimDeliveries(actorId)==0,"post_restart_receipt_replay_and_delivery_retries_do_not_duplicate");
                org.slf4j.LoggerFactory.getLogger("citizens").info("[XMODAUDIT] COMPLETE {} commerce crash recovery checks",passed);
            } else {
                String stage=phase.substring("commit:".length());
                economy.ensurePlayerAccount(actorId,actor.getGameProfile().getName());
                var currency=economy.defaultCurrency(); String shop="commerce-crash-"+actorId;
                seed(((net.yuuniverse.economy.EconomyRuntime)economy).ledger().databasePath(),shop,currency.id());
                economy.credit(actorId,currency.id(),1000,"crash-seed:"+actorId,"fixture");
                var chapter=new Chapter(file.newID(),file,file.getDefaultChapterGroup());chapter.onCreated();
                // Native default CustomTask maximum is one, preserved by ordinary FTB serialization on restart.
                var quest=new Quest(file.newID(),chapter);quest.onCreated();var task=new CustomTask(file.newID(),quest);task.onCreated();
                file.clearCachedData();file.clearCachedProgress();
                var binding=service.bind(source,task.getCodeString(),Shop.SYSTEM,shop,"component-paper",Action.BUY_FROM_SHOP,Measure.ITEMS,null,false);
                for(int slot=0;slot<actor.getInventory().getContainerSize();slot++) actor.getInventory().setItem(slot,new ItemStack(Items.DIAMOND,64));
                ((dev.ftb.mods.ftbteams.data.TeamManagerImpl)dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().getManager()).saveNow();
                team.saveIfChanged();file.saveNow();server.saveEverything(true,true,true);
                state.addProperty("actor",actorId.toString());state.addProperty("task",task.getCodeString());state.addProperty("stage",stage);
                state.addProperty("currency",currency.id());state.addProperty("balance",economy.balance(actorId,currency.id()));
                ServerPlayer nativeActor=actor;
                if(stage.equals("projection")) dev.ftb.mods.ftbquests.events.ObjectCompletedEvent.TASK.register(event->{
                    if(event.getTask()==task) {
                        var evidence=service.entries(source).stream().filter(e->e.binding().equals(binding)&&e.state()==State.CONFIRMED).findFirst().orElseThrow();
                        killMarker(statePath,state,evidence,api,nativeActor);
                    }
                    return dev.architectury.event.EventResult.pass();
                });
                var request=request(actor,shop,"component-paper",CommerceAction.BUY_FROM_SHOP,1,stage.equals("receipt")?"audit:suppress-commit":"audit:projection-kill");
                var outcome=execute(api,actor,request);
                var evidence=service.entries(source).stream().filter(e->e.binding().equals(binding)&&e.request().requestKey().equals(request.idempotencyKey())).findFirst().orElseThrow();
                if(stage.equals("applied")) {
                    check(evidence.state()==State.APPLIED && team.getProgress(task)==1,"applied_kill_point_is_after_durable_consumer_acknowledgement_before_ftb_save");
                    killMarker(statePath,state,evidence,api,actor);
                }
                if(!stage.equals("receipt")) throw new AssertionError("Native projection did not reach kill callback");
                check(evidence.state()==State.PREPARED && team.getProgress(task)==0 && outcome.state()==CommerceOutcome.State.DELIVERY_PENDING,"receipt_kill_point_is_after_actual_economy_commit_before_consumer_replay");
                killMarker(statePath,state,evidence,api,actor);
            }
        } catch(Throwable failure) {org.slf4j.LoggerFactory.getLogger("citizens").error("[XMODAUDIT] FAILED commerce crash recovery",failure);}
        finally {if(actor!=null&&actor.connection!=null)server.getPlayerList().remove(actor);if(channel!=null)channel.finishAndReleaseAll();server.halt(false);}
    }
    private static void killMarker(Path path,com.google.gson.JsonObject state,Entry evidence,CommerceApi api,ServerPlayer actor) {
        try {
            var history=api.history(actor.getUUID(),evidence.binding().startingSequence(),1000).stream()
                    .filter(e->e.requestKey().equals(evidence.request().requestKey())).findFirst().orElseThrow();
            state.addProperty("entry",evidence.id());state.addProperty("operation",history.receipt().operationId().toString());
            state.addProperty("transaction",history.receipt().transactionId().toString());state.addProperty("delivery",history.receipt().deliveryClaimId().toString());
            try(var channel=java.nio.channels.FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
                var bytes=java.nio.charset.StandardCharsets.UTF_8.encode(new com.google.gson.Gson().toJson(state));while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
            }
            org.slf4j.LoggerFactory.getLogger("citizens").info("[COMMERCETASKAUDIT] KILL_READY pid={} entry={}",ProcessHandle.current().pid(),evidence.id());
            while(true)java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L);
        } catch(java.io.IOException failure) {throw new IllegalStateException(failure);}
    }
    private static CustomTask task(ServerQuestFile file,Chapter chapter,long maximum,boolean repeatable) {
        var quest=new Quest(file.newID(),chapter); var data=new net.minecraft.nbt.CompoundTag(); data.putBoolean("can_repeat",repeatable); data.putInt("repeat_cooldown",0);
        quest.readData(data,file.holderLookup()); quest.onCreated();
        var task=new CustomTask(file.newID(),quest); task.onCreated(); task.setMaxProgress(maximum); task.setCheckTimer(1000000); task.setEnableButton(false); return task;
    }
    private static CommerceRequest request(ServerPlayer actor,String shop,String offer,CommerceAction action,int count,String origin) {
        return new CommerceRequest(CommerceShopType.SYSTEM,shop,offer,action,AccountIds.player(actor.getUUID()),count,"task-trade:"+UUID.randomUUID(),origin);
    }
    private static CommerceOutcome execute(CommerceApi api,ServerPlayer actor,CommerceRequest request) {return api.execute(actor.getUUID(),api.quote(actor.getUUID(),request));}
    private static Entry entry(CommerceQuestTasks service,net.minecraft.commands.CommandSourceStack source,Binding binding,UUID operation) {
        return service.entries(source).stream().filter(e->e.binding().equals(binding) && e.receipt()!=null && e.receipt().operationId().equals(operation)).findFirst().orElseThrow();
    }
    private static void seed(Path database,String shop,String currency) throws Exception {
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+database);
            var s=c.prepareStatement("INSERT INTO system_shop VALUES (?,?,'fixture','fixture','{}',1)");
            var p=c.prepareStatement("INSERT INTO system_shop_page VALUES (?,'page1',0,'Commerce fixture',3,'{}')");
            var o=c.prepareStatement("INSERT INTO system_shop_item (shop_id,item_key,page_key,slot_key,legacy_material,item_config_json,currency_id,buy_price_minor_units,sell_price_minor_units,dynamic_pricing,enabled) VALUES (?,'component-paper','page1','1','PAPER','{\"material\":\"PAPER\",\"name\":\"Commerce component fixture\"}',?,10,4,0,1)")) {
            s.setString(1,shop);s.setString(2,shop);s.executeUpdate();p.setString(1,shop);p.executeUpdate();o.setString(1,shop);o.setString(2,currency);o.executeUpdate();
        }
    }
    private static void check(boolean condition,String name) {if(!condition)throw new AssertionError(name);passed++;org.slf4j.LoggerFactory.getLogger("citizens").info("[COMMERCETASKAUDIT] PASS {}",name);}
}
