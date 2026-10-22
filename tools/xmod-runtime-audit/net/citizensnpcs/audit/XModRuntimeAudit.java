package net.citizensnpcs.audit;

import java.util.*;
import com.mojang.authlib.GameProfile;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.task.*;
import dev.ftb.mods.ftbquests.quest.reward.CustomReward;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.api.integration.CitizensScripts;
import net.citizensnpcs.util.CraftQuestEvidence;
import net.citizensnpcs.util.QuestEvidenceJournal;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid="citizens")
public final class XModRuntimeAudit {
    private static final List<Note> notes=new ArrayList<>();
    private static int passed;
    private static boolean ran;
    public record Note(String kind,String objectId,String playerId,long value) { }
    /** Called by actual Rhino callbacks, with no direct Java event dispatcher. */
    public static void note(String kind,String objectId,String playerId,long value) {
        notes.add(new Note(kind,objectId,playerId,value));
        LoggerFactory.getLogger("citizens").info("[XMODAUDIT] script {} {} {} {}",kind,objectId,playerId,value);
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if(ran) return;
        var server=event.getServer(); var level=server.overworld(); level.setChunkForced(0,0,true);
        if(server.getTickCount()<20 || !level.isPositionEntityTicking(new BlockPos(1,-60,1))) return;
        if(System.getenv("CITIZENS_REWARD_AUDIT_PHASE")!=null) { ran=true; crashRewards(server,System.getenv("CITIZENS_REWARD_AUDIT_PHASE")); return; }
        if(System.getenv("CITIZENS_COMMERCE_AUDIT_PHASE")!=null) { ran=true; CommerceTaskRuntimeAudit.crash(server,System.getenv("CITIZENS_COMMERCE_AUDIT_PHASE")); return; }
        ran=true; notes.clear(); ServerPlayer actor=null; EmbeddedChannel channel=null; CraftQuestEvidence service=null;
        try {
            var file=ServerQuestFile.getInstance().orElseThrow();
            var previous=file.getChapter(0x20101L); if(previous!=null) previous.deleteSelf();
            var chapter=new Chapter(0x20101L,file,file.getDefaultChapterGroup()); chapter.onCreated();
            var quest=new Quest(0x20102L,chapter);
            var definition=new net.minecraft.nbt.CompoundTag(); definition.putBoolean("can_repeat",true); definition.putInt("repeat_cooldown",0);
            quest.readData(definition,server.registryAccess()); quest.onCreated();
            var task=new CustomTask(0x20103L,quest); task.onCreated();
            var reward=new CustomReward(0x20104L,quest); reward.onCreated();
            var hiddenQuest=new Quest(0x20106L,chapter);
            var hiddenConfig=new net.minecraft.nbt.CompoundTag(); hiddenConfig.putBoolean("invisible",true);
            hiddenQuest.readData(hiddenConfig,server.registryAccess()); hiddenQuest.onCreated();
            var hiddenTask=new ItemTask(0x20107L,hiddenQuest).setStackAndCount(new ItemStack(Items.STONE),2);
            hiddenTask.setConsumeItems(dev.ftb.mods.ftblibrary.config.Tristate.TRUE); hiddenTask.onCreated();
            file.clearCachedData(); file.clearCachedProgress();
            check(count("customTask")==1 && task.getMaxProgress()==4 && task.autoSubmitOnPlayerTick()==1000000,
                    "actual_rhino_custom_task_registration_configures_native_checker");
            check(quest.canBeRepeated(),"native_repeatable_quest_fixture");
            actor=new ServerPlayer(server,level,new GameProfile(UUID.randomUUID(),"XModActor"),ClientInformation.createDefault()); actor.setPos(1,-60,1);
            channel=connect(actor); var player=actor;
            var team=file.getTeamData(actor).orElseThrow();
            var economy=net.yuuniverse.economy.api.YuuniverseEconomyApi.current().orElseThrow();
            economy.ensurePlayerAccount(actor.getUUID(),actor.getGameProfile().getName());
            var currency=economy.defaultCurrency().id(); long balance=economy.balance(actor.getUUID(),currency);
            var recipeId=ResourceLocation.parse("citizens_audit:xmod_recipe");
            check(CitizensScripts.apiVersion()==1 && CitizensScripts.recipe(actor,recipeId.toString()).orElseThrow().output().getCount()==4,
                    "public_script_api_uses_current_kubejs_recipe");
            task.submitTask(team,actor,ItemStack.EMPTY);
            check(count("check")==1 && last("check").playerId().equals(actor.getUUID().toString()) && team.getProgress(task)==0,
                    "script_checker_receives_public_snapshot_without_fabricating_craft_progress");
            check(CitizensScripts.tasks(actor,null,100).tasks().stream().anyMatch(view->view.taskId().equals(task.getCodeString())),
                    "public_script_discovery_uses_actor_visibility");
            try { CitizensScripts.task(actor,hiddenTask.getCodeString()); throw new AssertionError("Hidden task disclosed"); }
            catch(IllegalStateException expected) { check(true,"public_script_known_id_query_does_not_disclose_hidden_task"); }
            actor.getInventory().setItem(0,new ItemStack(Items.STONE,3));
            try { CitizensScripts.deliver(actor,hiddenTask.getCodeString()); throw new AssertionError("Hidden delivery accepted"); }
            catch(IllegalStateException expected) { check(actor.getInventory().countItem(Items.STONE)==3,"script_hidden_task_delivery_consumes_no_items"); }
            var second=new ServerPlayer(server,level,new GameProfile(UUID.randomUUID(),"XModOther"),ClientInformation.createDefault()); second.setPos(1,-60,3);
            var secondChannel=connect(second);
            try {
                check(!CitizensScripts.task(second,task.getCodeString()).teamId().equals(team.getTeamId()),
                        "public_script_queries_do_not_borrow_another_players_team");
            } finally { server.getPlayerList().remove(second); secondChannel.finishAndReleaseAll(); }
            var directory=java.nio.file.Files.createTempDirectory(server.getServerDirectory(),"xmod-evidence-");
            service=CraftQuestEvidence.open(server,directory); var source=actor.createCommandSourceStack().withPermission(2);
            var binding=service.bind(source,task.getCodeString(),recipeId);
            var station=new BlockPos(2,-60,2); level.setBlockAndUpdate(station,net.minecraft.world.level.block.Blocks.CRAFTING_TABLE.defaultBlockState());
            var menu=new net.minecraft.world.inventory.CraftingMenu(92,actor.getInventory(),net.minecraft.world.inventory.ContainerLevelAccess.create(level,station));
            actor.containerMenu=menu;
            actor.getInventory().setItem(0,new ItemStack(Items.PAPER,4));
            task.submitTask(team,actor,ItemStack.EMPTY);
            check(team.getProgress(task)==0 && count("started")==0 && count("completed")==0,
                    "possession_and_script_checks_do_not_satisfy_recipe_bound_task");
            craft(file,player,menu,recipeId);
            check(team.getProgress(task)==4 && service.entries(source).getFirst().state()==QuestEvidenceJournal.State.APPLIED,
                    "native_craft_evidence_projects_script_configured_custom_task");
            check(count("started")==1 && count("completed")==1 && last("completed").value()==4
                    && last("completed").playerId().isEmpty(),
                    "actual_rhino_evidence_callbacks_retain_native_team_progress_without_inventing_player_context");
            service.close(); service=CraftQuestEvidence.open(server,directory);
            check(service.bindings(source).getFirst().epoch().equals(binding.epoch()) && service.reconcile(source)==0 && count("completed")==1,
                    "evidence_reopen_does_not_repeat_script_completion");
            int beforeDiamonds=actor.getInventory().countItem(Items.DIAMOND); int beforeCycles=team.getCompletionCount(quest);
            claim(actor,reward,true);
            check(count("customReward")==1 && last("customReward").playerId().equals(actor.getUUID().toString())
                    && actor.getInventory().countItem(Items.DIAMOND)==beforeDiamonds+1,
                    "native_claim_invokes_actual_rhino_custom_reward_once");
            check(team.getProgress(task)==0 && team.getCompletionCount(quest)==beforeCycles+1 && last("customReward").value()==0,
                    "repeatable_native_reset_precedes_script_reward_callback");
            check(actor.containerMenu.getClass().getName().startsWith("net.yuuniverse.economy.")
                    && economy.balance(actor.getUUID(),currency)==balance,
                    "script_opens_economy_owned_browser_without_payment");
            claim(actor,reward,true);
            check(count("customReward")==1 && actor.getInventory().countItem(Items.DIAMOND)==beforeDiamonds+1,
                    "native_duplicate_claim_does_not_reissue_script_item_reward");
            actor.closeContainer(); actor.containerMenu=menu;
            craft(file,player,menu,recipeId); claim(actor,reward,false);
            check(count("completed")==2 && count("customReward")==2 && team.getCompletionCount(quest)==beforeCycles+2
                    && service.entries(source).size()==2 && actor.getInventory().countItem(Items.DIAMOND)==beforeDiamonds+2,
                    "next_native_repeat_cycle_receives_distinct_evidence_and_script_callback");
            check(economy.balance(actor.getUUID(),currency)==balance,"repeated_script_menu_opening_does_not_issue_money");
            var delivery=new ItemTask(0x20105L,quest).setStackAndCount(new ItemStack(Items.STONE),2);
            delivery.setConsumeItems(dev.ftb.mods.ftblibrary.config.Tristate.TRUE); delivery.onCreated();
            actor.getInventory().setItem(0,new ItemStack(Items.STONE,3));
            check(CitizensScripts.deliver(actor,delivery.getCodeString()).progress()==2 && actor.getInventory().countItem(Items.STONE)==1,
                    "public_script_delivery_consumes_only_native_required_items");
            try { CitizensScripts.deliver(actor,task.getCodeString()); throw new AssertionError("Custom task accepted as delivery"); }
            catch(IllegalArgumentException expected) { check(team.getProgress(task)==0,"script_delivery_cannot_forge_custom_craft_evidence"); }
            check(java.util.concurrent.CompletableFuture.supplyAsync(()->{
                try { CitizensScripts.recipe(player,recipeId.toString()); return false; }
                catch(IllegalStateException expected) { return true; }
            }).join(),"public_script_api_rejects_worker_thread_access");
            var retired=new ServerPlayer(server,level,actor.getGameProfile(),ClientInformation.createDefault());
            try { CitizensScripts.task(retired,task.getCodeString()); throw new AssertionError("Inactive session accepted"); }
            catch(IllegalStateException expected) { check(true,"public_script_api_rejects_unregistered_same_uuid_player"); }
            try { CitizensScripts.validateEconomyMenu(actor,List.of("browser","-1")); throw new AssertionError("Invalid menu accepted"); }
            catch(IllegalArgumentException expected) { check(economy.balance(actor.getUUID(),currency)==balance,"script_rejects_invalid_menu_without_payment"); }
            PreparedCommerceRuntimeAudit.run(actor, file, economy);
            CommerceTaskRuntimeAudit.run(actor, file, economy);
            team=file.getTeamData(actor).orElseThrow();
            monetaryRewards(actor, file, team, economy);
            PartyRewardRuntimeAudit.run(actor, file, economy);
            LoggerFactory.getLogger("citizens").info("[XMODAUDIT] COMPLETE {} checks",passed);
        } catch(Throwable failure) { LoggerFactory.getLogger("citizens").error("[XMODAUDIT] FAILED",failure); }
        finally {
            try { if(service!=null) service.close(); } catch(Exception failure) { LoggerFactory.getLogger("citizens").error("[XMODAUDIT] FAILED cleanup",failure); }
            if(actor!=null && actor.connection!=null) server.getPlayerList().remove(actor);
            if(channel!=null) channel.finishAndReleaseAll(); server.halt(false);
        }
    }
    private static void crashRewards(net.minecraft.server.MinecraftServer server,String phase) {
        ServerPlayer actor=null; EmbeddedChannel channel=null;
        try {
            var statePath=server.getServerDirectory().resolve("reward-crash-state.json");
            var json=new com.google.gson.Gson(); var file=ServerQuestFile.getInstance().orElseThrow();
            var economy=net.yuuniverse.economy.api.YuuniverseEconomyApi.current().orElseThrow();
            var service=net.citizensnpcs.util.FtbMonetaryRewards.current(server);
            UUID actorId;
            com.google.gson.JsonObject state;
            if(phase.equals("recover")) { state=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(statePath)).getAsJsonObject(); actorId=UUID.fromString(state.get("actor").getAsString()); }
            else { state=new com.google.gson.JsonObject(); actorId=UUID.randomUUID(); }
            actor=new ServerPlayer(server,server.overworld(),new GameProfile(actorId,"RewardCrashActor"),ClientInformation.createDefault()); actor.setPos(1,-60,1); channel=connect(actor);
            var source=actor.createCommandSourceStack().withPermission(2); var team=file.getTeamData(actor).orElseThrow();
            if(phase.equals("recover")) {
                var claim=service.claims(source).stream().filter(value->value.id().equals(state.get("claim").getAsString())).findFirst().orElseThrow();
                check(claim.state()==net.citizensnpcs.util.QuestRewardJournal.State.PAID && claim.transactionId().toString().equals(state.get("transaction").getAsString()),
                        "actual_process_restart_replays_committed_economy_transaction_into_pending_claim");
                check(economy.balance(actorId,state.get("currency").getAsString())==state.get("balance").getAsLong()+375,
                        "actual_process_kill_and_restart_preserves_one_exact_monetary_credit");
                var reward=file.getReward(Long.parseUnsignedLong(state.get("reward").getAsString(),16));
                check(team.getTeamId().equals(claim.intent().teamId()) && team.getCompletionCount(reward.getQuest())==claim.intent().nativeCycle(),
                        "crash_fixture_retains_older_native_ftb_save_in_separate_persistence_domain");
                try { team.claimReward(actor,reward,false); throw new AssertionError("Rolled back native claim recredited"); }
                catch(IllegalStateException expected) { check(economy.balance(actorId,state.get("currency").getAsString())==state.get("balance").getAsLong()+375,
                        "actual_restart_fences_unsaved_ftb_repeat_reset_without_duplicate_payment"); }
                check(service.reconcile(source)==0,"restart_reconciled_paid_receipt_is_stable");
                LoggerFactory.getLogger("citizens").info("[XMODAUDIT] COMPLETE {} crash recovery checks",passed);
            } else {
                var id=0x20300L+Math.floorMod(actorId.hashCode(),1000000)*16L;
                var chapter=new Chapter(id+1,file,file.getDefaultChapterGroup()); chapter.onCreated();
                var quest=new Quest(id+2,chapter); var definition=new net.minecraft.nbt.CompoundTag(); definition.putBoolean("can_repeat",true); definition.putInt("repeat_cooldown",0);
                quest.readData(definition,server.registryAccess()); quest.onCreated();
                var task=new CustomTask(id+3,quest); task.onCreated(); var reward=new CustomReward(id+4,quest); reward.onCreated();
                file.clearCachedData(); file.clearCachedProgress(); economy.ensurePlayerAccount(actorId,actor.getGameProfile().getName());
                var currency=economy.defaultCurrency(); var binding=service.bind(source,reward.getCodeString(),currency.id(),java.math.BigDecimal.valueOf(375,currency.scale()));
                team.setProgress(task,task.getMaxProgress());
                // Persist authoritative native state before claiming; the following reset deliberately remains unsaved.
                ((dev.ftb.mods.ftbteams.data.TeamManagerImpl)dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().getManager()).saveNow();
                team.saveIfChanged(); file.saveNow(); server.saveEverything(true,true,true);
                state.addProperty("actor",actorId.toString()); state.addProperty("reward",reward.getCodeString()); state.addProperty("currency",currency.id()); state.addProperty("balance",economy.balance(actorId,currency.id()));
                net.yuuniverse.economy.api.YuuniverseEconomyApi.clear(economy);
                team.claimReward(actor,reward,false);
                var claim=service.claims(source).stream().filter(value->value.intent().binding().epoch().equals(binding.epoch())).findFirst().orElseThrow();
                check(claim.state()==net.citizensnpcs.util.QuestRewardJournal.State.ACCEPTED,"crash_fixture_native_claim_is_durable_before_missing_payment_acknowledgement");
                var receipt=economy.credit(actorId,currency.id(),375,claim.intent().operationKey(),claim.intent().sourceReference());
                state.addProperty("claim",claim.id()); state.addProperty("transaction",receipt.transactionId().toString());
                java.nio.file.Files.writeString(statePath,json.toJson(state));
                LoggerFactory.getLogger("citizens").info("[XMODAUDIT] KILL_READY pid={} claim={}",ProcessHandle.current().pid(),claim.id());
                // The runner kills this exact JVM after the durable record/ledger commit marker. No shutdown/save runs.
                while(true) java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L);
            }
        } catch(Throwable failure) { LoggerFactory.getLogger("citizens").error("[XMODAUDIT] FAILED crash recovery",failure); }
        finally { if(actor!=null && actor.connection!=null) server.getPlayerList().remove(actor); if(channel!=null) channel.finishAndReleaseAll(); server.halt(false); }
    }
    private static void monetaryRewards(ServerPlayer actor, ServerQuestFile file, TeamData team,
            net.yuuniverse.economy.api.EconomyApi economy) throws Exception {
        var server=actor.getServer(); var source=actor.createCommandSourceStack().withPermission(2);
        var service=net.citizensnpcs.util.FtbMonetaryRewards.current(server);
        long baseId=file.newID();
        var chapter=new Chapter(baseId,file,file.getDefaultChapterGroup()); chapter.onCreated();
        var quest=new Quest(file.newID(),chapter);
        var definition=new net.minecraft.nbt.CompoundTag(); definition.putBoolean("can_repeat",true); definition.putInt("repeat_cooldown",0);
        quest.readData(definition,server.registryAccess()); quest.onCreated();
        var task=new CustomTask(file.newID(),quest); task.onCreated();
        var reward=new CustomReward(file.newID(),quest); reward.onCreated();
        var rewardConfig=new net.minecraft.nbt.CompoundTag(); rewardConfig.putBoolean("team_reward",true); reward.readData(rewardConfig,server.registryAccess());
        file.clearCachedData(); file.clearCachedProgress();
        var currency=economy.defaultCurrency(); long before=economy.balance(actor.getUUID(),currency.id());
        var amount=java.math.BigDecimal.valueOf(375,currency.scale());
        var binding=service.bind(source,reward.getCodeString(),currency.id(),amount);
        check(binding.amount()==375 && binding.currencyScale()==currency.scale() && binding.teamReward(),"native_monetary_binding_preserves_currency_precision_and_team_owner");
        check(service.bind(source,reward.getCodeString(),currency.id(),amount).epoch().equals(binding.epoch()),"identical_reward_binding_reuses_immutable_epoch");
        try { service.bind(source,reward.getCodeString(),currency.id(),amount.add(java.math.BigDecimal.ONE)); throw new AssertionError("Rebinding accepted"); }
        catch(IllegalStateException expected) { check(true,"changed_monetary_binding_requires_explicit_migration"); }
        int originalCount=service.claims(source).size(); claim(actor,reward,true);
        check(service.claims(source).size()==originalCount && economy.balance(actor.getUUID(),currency.id())==before,"incomplete_native_quest_cannot_admit_payment");
        var callbacks=new ArrayList<Boolean>();
        dev.ftb.mods.ftbquests.events.CustomRewardEvent.EVENT.register(event->{
            if(event.getReward()!=reward) return dev.architectury.event.EventResult.pass();
            var latest=service.claims(source).stream().filter(value->value.intent().binding().rewardId().equals(reward.getCodeString()) && value.intent().teamId().equals(team.getTeamId())).toList().getLast();
            callbacks.add(latest.state()==net.citizensnpcs.util.QuestRewardJournal.State.PAID && team.getProgress(task)==0);
            return dev.architectury.event.EventResult.pass();
        });
        team.setProgress(task,task.getMaxProgress()); claim(actor,reward,true);
        var first=service.claims(source).stream().filter(value->value.intent().binding().rewardId().equals(reward.getCodeString()) && value.intent().teamId().equals(team.getTeamId())).toList().getLast();
        check(first.state()==net.citizensnpcs.util.QuestRewardJournal.State.PAID && first.intent().beneficiaryId().equals(actor.getUUID())
                && first.intent().ownerId().equals(team.getTeamId()) && first.afterCycle()==first.intent().nativeCycle()+1
                && economy.balance(actor.getUUID(),currency.id())==before+375,"actual_native_claim_records_pre_reset_generation_and_pays_exact_beneficiary");
        check(callbacks.size()==1 && callbacks.getFirst(),"native_custom_reward_callback_observes_reset_and_durable_monetary_receipt");
        claim(actor,reward,true);
        check(service.claims(source).size()==originalCount+1 && economy.balance(actor.getUUID(),currency.id())==before+375,"duplicate_native_claim_has_no_second_intent_or_payment");
        team.setProgress(task,task.getMaxProgress());
        net.yuuniverse.economy.api.YuuniverseEconomyApi.clear(economy);
        try {
            try { claim(actor,reward,false); throw new AssertionError("Unverified PAID history admitted another claim"); }
            catch (IllegalStateException expected) {
                check(service.claims(source).size()==originalCount+1 && team.getProgress(task)==task.getMaxProgress()
                        && economy.balance(actor.getUUID(),currency.id())==before+375,
                        "provider_absence_holds_repeat_admission_before_native_or_monetary_mutation");
            }
        }
        finally { net.yuuniverse.economy.api.YuuniverseEconomyApi.publish(economy); }
        // Historical inspection remains valid while the actual provider holds new credits on a frozen account.
        var ledger=((net.yuuniverse.economy.EconomyRuntime)economy).ledger();
        var account=economy.playerAccount(actor.getUUID()).orElseThrow();
        ledger.setAccountFrozen(account.id(),true,"audit","isolated-test");
        try { claim(actor,reward,false); }
        finally { ledger.setAccountFrozen(account.id(),false,"audit","isolated-test"); }
        var second=service.claims(source).stream().filter(value->value.intent().binding().rewardId().equals(reward.getCodeString()) && value.intent().teamId().equals(team.getTeamId())).toList().getLast();
        check(second.state()==net.citizensnpcs.util.QuestRewardJournal.State.ACCEPTED && team.getProgress(task)==0
                && second.intent().nativeCycle()==first.afterCycle() && !first.intent().operationKey().equals(second.intent().operationKey()),
                "credit_failure_keeps_native_accepted_repeat_claim_pending_under_distinct_operation");
        // Actual Economy commit simulates a lost consumer acknowledgement; recovery must reuse this operation.
        var receipt=economy.credit(second.intent().beneficiaryId(),currency.id(),375,second.intent().operationKey(),second.intent().sourceReference());
        check(service.reconcile(source)==1 && service.claims(source).stream().filter(value->value.id().equals(second.id())).findFirst().orElseThrow().transactionId().equals(receipt.transactionId())
                && economy.balance(actor.getUUID(),currency.id())==before+750,"reconciliation_replays_actual_committed_economy_receipt_without_duplicate_credit");
        check(service.reconcile(source)==0 && economy.balance(actor.getUUID(),currency.id())==before+750,"paid_monetary_claims_are_not_reissued_by_reconciliation");
        var saved=team.serializeNBT();
        var countKey=saved.getAllKeys().stream().filter(key->key.contains("completion_count")).findFirst().orElseThrow();
        var rolled=saved.copy(); rolled.getCompound(countKey).putInt(quest.getCodeString(),first.intent().nativeCycle());
        // Use provider-owned native deserialization to reproduce backup rollback, never production mutation.
        var rollbackData=new dev.ftb.mods.ftblibrary.snbt.SNBTCompoundTag(); rollbackData.merge(rolled); team.deserializeNBT(rollbackData);
        team.setProgress(task,task.getMaxProgress());
        try { team.claimReward(actor,reward,false); throw new AssertionError("Rollback payment admitted"); }
        catch(IllegalStateException expected) { check(economy.balance(actor.getUUID(),currency.id())==before+750,"native_repeat_counter_rollback_is_fenced_before_another_payment"); }
        team.deserializeNBT(saved);
        rewardConfig.putBoolean("team_reward",false); reward.readData(rewardConfig,server.registryAccess()); team.setProgress(task,task.getMaxProgress());
        try { team.claimReward(actor,reward,false); throw new AssertionError("Changed reward ownership accepted"); }
        catch(IllegalStateException expected) { check(economy.balance(actor.getUUID(),currency.id())==before+750,"native_reward_redefinition_cannot_reinterpret_bound_money_or_ownership"); }
        rewardConfig.putBoolean("team_reward",true); reward.readData(rewardConfig,server.registryAccess());
        var party=dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().getManager().createPartyTeam(actor,"RewardAuditParty"+actor.getUUID(),"fixture",dev.ftb.mods.ftblibrary.icon.Color4I.rgb(0x335577));
        var partyTeam=file.getTeamData(actor).orElseThrow();
        check(!partyTeam.getTeamId().equals(team.getTeamId()) && partyTeam.getTeamId().equals(party.getId()),"monetary_claim_reacquires_current_native_party_identity");
        partyTeam.setProgress(task,task.getMaxProgress());
        net.yuuniverse.economy.api.YuuniverseEconomyApi.clear(economy);
        try { partyTeam.claimReward(actor,reward,false); }
        finally { net.yuuniverse.economy.api.YuuniverseEconomyApi.publish(economy); }
        var partyClaim=service.claims(source).stream().filter(value->value.intent().teamId().equals(partyTeam.getTeamId()) && value.intent().binding().rewardId().equals(reward.getCodeString())).findFirst().orElseThrow();
        check(partyClaim.intent().beneficiaryId().equals(actor.getUUID()) && partyClaim.state()==net.citizensnpcs.util.QuestRewardJournal.State.ACCEPTED,
                "new_native_party_claim_has_its_own_generation_and_immutable_beneficiary");
        check(service.reconcile(source)==1 && economy.balance(actor.getUUID(),currency.id())==before+1125,"historical_and_current_party_claims_reconcile_without_redirecting_beneficiary");
        var throwQuest=new Quest(file.newID(),chapter); throwQuest.onCreated(); var throwTask=new CustomTask(file.newID(),throwQuest); throwTask.onCreated();
        var throwReward=new CustomReward(file.newID(),throwQuest); throwReward.onCreated(); file.clearCachedData(); file.clearCachedProgress();
        service.bind(source,throwReward.getCodeString(),currency.id(),amount);
        dev.ftb.mods.ftbquests.events.CustomRewardEvent.EVENT.register(event->{ if(event.getReward()==throwReward) throw new IllegalStateException("Fixture callback fails after payment"); return dev.architectury.event.EventResult.pass(); });
        partyTeam.setProgress(throwTask,throwTask.getMaxProgress());
        try { partyTeam.claimReward(actor,throwReward,false); throw new AssertionError("Throwing callback completed"); }
        catch(IllegalStateException expected) { check(economy.balance(actor.getUUID(),currency.id())==before+1500 && service.claims(source).stream().anyMatch(value->value.intent().binding().rewardId().equals(throwReward.getCodeString()) && value.state()==net.citizensnpcs.util.QuestRewardJournal.State.PAID),
                "throwing_native_custom_callback_preserves_previously_durable_money_and_receipt"); }
        partyTeam.claimReward(actor,throwReward,false);
        check(economy.balance(actor.getUUID(),currency.id())==before+1500,"retry_after_throwing_reward_callback_does_not_reissue_payment");
        check(java.util.concurrent.CompletableFuture.supplyAsync(()->{ try { service.claims(source); return false; } catch(IllegalStateException expected) { return true; } }).join(),
                "monetary_administration_rejects_worker_thread_access");
        var noAuthority=actor.createCommandSourceStack().withPermission(0);
        try { service.claims(noAuthority); throw new AssertionError("Unauthorized reward audit accepted"); }
        catch(IllegalStateException expected) { check(true,"native_monetary_audit_requires_live_operator_authority"); }
    }
    @SuppressWarnings("unchecked") private static void craft(ServerQuestFile file,ServerPlayer player,net.minecraft.world.inventory.CraftingMenu menu,ResourceLocation recipeId) {
        var recipe=(net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe>)
                (net.minecraft.world.item.crafting.RecipeHolder<?>)player.getServer().getRecipeManager().byKey(recipeId).orElseThrow();
        menu.setCarried(ItemStack.EMPTY); menu.beginPlacingRecipe(); menu.clearCraftingContent(); menu.getSlot(1).set(new ItemStack(Items.STONE));
        menu.finishPlacingRecipe(recipe);
        menu.clicked(0,0,net.minecraft.world.inventory.ClickType.PICKUP,player);
        check(menu.getCarried().getCount()==4 && menu.getSlot(1).getItem().isEmpty(),"native_recipe_book_selection_and_result_consumption");
    }
    private static long count(String kind) { return notes.stream().filter(note->note.kind().equals(kind)).count(); }
    private static void claim(ServerPlayer player,CustomReward reward,boolean notify) {
        var payload=new dev.ftb.mods.ftbquests.net.ClaimRewardMessage(reward.id,notify);
        var context=new dev.architectury.networking.NetworkManager.PacketContext() {
            @Override public net.minecraft.world.entity.player.Player getPlayer() { return player; }
            @Override public void queue(Runnable work) { work.run(); }
            @Override public dev.architectury.utils.Env getEnvironment() { return dev.architectury.utils.Env.SERVER; }
            @Override public net.minecraft.core.RegistryAccess registryAccess() { return player.registryAccess(); }
        };
        var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            dev.ftb.mods.ftbquests.net.ClaimRewardMessage.STREAM_CODEC.encode(buffer,payload);
            dev.ftb.mods.ftbquests.net.ClaimRewardMessage.handle(dev.ftb.mods.ftbquests.net.ClaimRewardMessage.STREAM_CODEC.decode(buffer),context);
        } finally { buffer.release(); }
    }
    private static Note last(String kind) { return notes.stream().filter(note->note.kind().equals(kind)).toList().getLast(); }
    static EmbeddedChannel connect(ServerPlayer player) {
        var connection=new Connection(PacketFlow.SERVERBOUND);
        var channel=new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                Connection.configureInMemoryPipeline(channel.pipeline(),PacketFlow.SERVERBOUND); connection.configurePacketHandler(channel.pipeline());
            }
        });
        NetworkRegistry.configureMockConnection(connection);
        var cookie=new CommonListenerCookie(player.getGameProfile(),0,ClientInformation.createDefault(),false,ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(player.registryAccess(),cookie.connectionType())));
        player.getServer().getPlayerList().placeNewPlayer(connection,player,cookie); return channel;
    }
    private static void check(boolean condition,String name) {
        if(!condition) throw new AssertionError(name); passed++; LoggerFactory.getLogger("citizens").info("[XMODAUDIT] PASS {}",name);
    }
}
