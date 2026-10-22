package net.citizensnpcs.audit;

import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.task.CustomTask;
import net.citizensnpcs.util.*;
import net.citizensnpcs.util.CommerceTaskJournal.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.yuuniverse.economy.api.*;
import net.yuuniverse.economy.core.*;

/** Native party and definition mutation at the exact commerce projection boundary. */
final class TeamMutationRuntimeAudit {
    private static int passed;
    static void run(MinecraftServer server,String mode) {
        ServerPlayer actor=null;io.netty.channel.embedded.EmbeddedChannel channel=null;
        try {
            if(!Set.of("removed","callback","definition","replacement","normal").contains(mode))throw new IllegalArgumentException("Unknown mutation mode");
            var economy=YuuniverseEconomyApi.current().orElseThrow();var runtime=(net.yuuniverse.economy.EconomyRuntime)economy;var api=economy.commerceApi().orElseThrow();
            var file=ServerQuestFile.getInstance().orElseThrow();var service=CommerceQuestTasks.current(server);var console=server.createCommandSourceStack();
            UUID owner=UUID.randomUUID();actor=new ServerPlayer(server,server.overworld(),new GameProfile(owner,"TeamMutation"),ClientInformation.createDefault());actor.setPos(1,-60,1);channel=XModRuntimeAudit.connect(actor);
            var manager=dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().getManager();
            var party=(dev.ftb.mods.ftbteams.data.PartyTeam)manager.createPartyTeam(actor,"MutationParty"+owner,"fixture",dev.ftb.mods.ftblibrary.icon.Color4I.rgb(0x335577));
            var team=file.getTeamData(actor).orElseThrow();var currency=economy.defaultCurrency();economy.ensurePlayerAccount(owner,"TeamMutation");economy.credit(owner,currency.id(),1000,"team-mutation-seed:"+owner,"fixture");
            String shop="team-mutation-"+owner;PreparedCommerceRuntimeAudit.seedFixture(runtime.ledger().databasePath(),shop,currency.id());
            var chapter=new Chapter(file.newID(),file,file.getDefaultChapterGroup());chapter.onCreated();var quest=new Quest(file.newID(),chapter);quest.onCreated();var task=new CustomTask(file.newID(),quest);task.onCreated();file.clearCachedData();file.clearCachedProgress();
            var binding=service.bind(actor.createCommandSourceStack().withPermission(2),task.getCodeString(),Shop.SYSTEM,shop,"paper",Action.BUY_FROM_SHOP,Measure.ITEMS,null,true);
            var definition=new net.minecraft.nbt.CompoundTag();quest.writeData(definition,server.registryAccess());
            int[] callbacks={0};CustomTask[] replacement={null};
            var request=new CommerceRequest(CommerceShopType.SYSTEM,shop,"paper",CommerceAction.BUY_FROM_SHOP,AccountIds.player(owner),1,"team-mutation:"+owner,"audit:suppress-commit");
            var outcome=api.execute(owner,api.quote(owner,request));var original=service.entries(console).stream().filter(e->e.binding().equals(binding)).findFirst().orElseThrow();
            check(original.state()==State.PREPARED && team.getProgress(task)==0 && economy.balance(owner,currency.id())==990,"actual_trade_retains_original_team_attribution_before_projection");
            if(mode.equals("removed"))party.leave(owner);
            else dev.ftb.mods.ftbquests.events.ObjectCompletedEvent.TASK.register(event->{
                if(event.getTask()==task)try{
                    callbacks[0]++;
                    switch(mode){
                        case "callback" -> party.leave(owner);
                        case "definition" -> {var changed=definition.copy();changed.putBoolean("disable_toast",true);quest.readData(changed,server.registryAccess());}
                        case "replacement" -> {task.deleteSelf();replacement[0]=new CustomTask(task.getId(),quest);replacement[0].setMaxProgress(2);replacement[0].onCreated();file.clearCachedData();}
                        default -> { }
                    }
                }catch(Exception failure){throw new IllegalStateException(failure);}
                return dev.architectury.event.EventResult.pass();
            });
            long balance=economy.balance(owner,currency.id()),transactions=runtime.ledger().audit().transactionCount();
            boolean reported=false;try{service.reconcile(console);}catch(IllegalStateException expected){reported=true;}
            var latest=service.entries(console).stream().filter(e->e.id().equals(original.id())).findFirst().orElseThrow();
            var state=new JsonObject();state.addProperty("mode",mode);state.addProperty("owner",owner.toString());state.addProperty("team",team.getTeamId().toString());state.addProperty("task",task.getCodeString());
            state.addProperty("entry",original.id());state.addProperty("operation",outcome.receipt().operationId().toString());state.addProperty("transaction",outcome.receipt().transactionId().toString());state.addProperty("requestKey",request.idempotencyKey());
            state.addProperty("nativeTeamPresent",manager.getTeamByID(team.getTeamId()).isPresent());state.addProperty("retainedTeamDataPresent",file.getNullableTeamData(team.getTeamId())!=null);state.addProperty("entryState",latest.state().name());state.addProperty("reported",reported);state.addProperty("callbacks",callbacks[0]);
            Files.writeString(server.getServerDirectory().resolve("team-mutation-state.json"),state.toString());
            boolean deleted=Set.of("removed","callback").contains(mode),normal=mode.equals("normal");
            var observedDefinition=new net.minecraft.nbt.CompoundTag();quest.writeData(observedDefinition,server.registryAccess());
            check(manager.getTeamByID(team.getTeamId()).isEmpty()==deleted && callbacks[0]==(mode.equals("removed")?0:1)
                    && (!mode.equals("definition") || !observedDefinition.equals(definition))
                    && (!mode.equals("replacement") || file.getTask(task.getId())==replacement[0]),"public_native_mutation_or_control_actually_ran");
            check(reported!=normal && latest.state()==(normal?State.APPLIED:State.CONFIRMED) && latest.attribution().equals(original.attribution())
                    && latest.receipt().transactionId().equals(outcome.receipt().transactionId()),"projection_acknowledges_only_current_unchanged_native_target");
            if(mode.equals("definition"))quest.readData(definition,server.registryAccess());
            if(mode.equals("replacement"))replacement[0].setMaxProgress(task.getMaxProgress());
            boolean retryReported=false;try{service.reconcile(console);}catch(IllegalStateException expected){retryReported=true;}
            var recovered=service.entries(console).stream().filter(e->e.id().equals(original.id())).findFirst().orElseThrow();
            check(retryReported==deleted && recovered.state()==(deleted?State.CONFIRMED:State.APPLIED) && callbacks[0]==(mode.equals("removed")?0:1),"retry_holds_deleted_teams_or_acknowledges_restored_stable_target_without_repeating_callback");
            check(economy.balance(owner,currency.id())==balance && runtime.ledger().audit().transactionCount()==transactions && runtime.ledger().audit().valid(),"target_mutation_recovery_never_redirects_or_repeats_original_payment");
            state.addProperty("recoveredState",recovered.state().name());state.addProperty("retryReported",retryReported);
            Files.writeString(server.getServerDirectory().resolve("team-mutation-state.json"),state.toString());
            org.slf4j.LoggerFactory.getLogger("citizens").info("[XMODAUDIT] COMPLETE {} team mutation checks mode={}",passed,mode);
        }catch(Throwable failure){org.slf4j.LoggerFactory.getLogger("citizens").error("[XMODAUDIT] FAILED team mutation {}",mode,failure);}
        finally{if(actor!=null&&actor.connection!=null)server.getPlayerList().remove(actor);if(channel!=null)channel.finishAndReleaseAll();server.halt(false);}
    }
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;org.slf4j.LoggerFactory.getLogger("citizens").info("[TEAMMUTATIONAUDIT] PASS {}",name);}
}
