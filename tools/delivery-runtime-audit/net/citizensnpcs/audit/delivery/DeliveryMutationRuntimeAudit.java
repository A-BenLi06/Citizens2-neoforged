package net.citizensnpcs.audit.delivery;

import java.nio.file.Files;
import java.util.*;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import dev.architectury.event.EventResult;
import dev.ftb.mods.ftbquests.events.ObjectCompletedEvent;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.reward.ItemReward;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.data.PartyTeam;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;

/** Completion callbacks mutate actual installed FTB objects and player sessions. */
final class DeliveryMutationRuntimeAudit {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("citizens");
    private static final List<String> MODES=List.of("removed","replacement","retired","definition","deleted","moved","repeat","normal");
    private static int passed;
    static void run(MinecraftServer server,String selected) {
        var states=new JsonArray();var failures=new ArrayList<Throwable>();
        try {
            if(!selected.equals("all")&&!MODES.contains(selected))throw new IllegalArgumentException("Unknown delivery mutation mode");
            for(String mode:selected.equals("all")?MODES:List.of(selected)) {
                try{exercise(server,mode,states);}catch(Throwable failure){failures.add(failure);LOG.error("[DELIVERYMUTATIONAUDIT] CASE_FAILED {}",mode,failure);}
                Files.writeString(server.getServerDirectory().resolve("delivery-mutation-state.json"),states.toString());
            }
            if(!failures.isEmpty()){var failure=new AssertionError("Native delivery mutation cases failed: "+failures.size());failures.forEach(failure::addSuppressed);throw failure;}
            LOG.info("[DELIVERYAUDIT] COMPLETE {} mutation checks",passed);
        }catch(Throwable failure){LOG.error("[DELIVERYAUDIT] FAILED mutation {}",selected,failure);}
        finally{server.halt(false);}
    }
    private static void exercise(MinecraftServer server,String mode,JsonArray states)throws Exception {
        var players=new ArrayList<ServerPlayer>();var channels=new ArrayList<EmbeddedChannel>();
        var file=ServerQuestFile.getInstance().orElseThrow();var service=QuestDeliveryRecovery.current(server);var console=server.createCommandSourceStack();
        UUID owner=UUID.randomUUID();var profile=new GameProfile(owner,"DeliveryMutate");
        var actor=connect(server,profile,players,channels);
        try {
            var manager=FTBTeamsAPI.api().getManager();
            var party=(PartyTeam)manager.createPartyTeam(actor,"DeliveryMutation"+owner,"fixture",dev.ftb.mods.ftblibrary.icon.Color4I.rgb(0x335577));
            var team=file.getTeamData(actor).orElseThrow();
            if(mode.equals("moved")){
                var member=connect(server,new GameProfile(UUID.randomUUID(),"DeliveryMember"),players,channels);
                party.invite(actor,List.of(member.getGameProfile()));party.join(member);
                party.transferOwnership(actor.createCommandSourceStack(),member.getGameProfile());
            }
            var chapter=new Chapter(file.newID(),file,file.getDefaultChapterGroup());chapter.onCreated();
            var quest=new Quest(file.newID(),chapter);quest.onCreated();
            if(mode.equals("repeat")){var config=new CompoundTag();config.putBoolean("can_repeat",true);config.putInt("repeat_cooldown",0);quest.readData(config,server.registryAccess());}
            var taskData=new CompoundTag();file.getTask(FtbQuestTasks.parseId("00000000004D1003")).writeData(taskData,server.registryAccess());
            var task=new ItemTask(file.newID(),quest);task.readData(taskData,server.registryAccess());task.onCreated();
            ItemReward reward=mode.equals("repeat")?new ItemReward(file.newID(),quest,new ItemStack(Items.DIAMOND)):null;
            if(reward!=null)reward.onCreated();
            file.clearCachedData();file.clearCachedProgress();
            var questData=new CompoundTag();quest.writeData(questData,server.registryAccess());
            int[] callbacks={0};ItemTask[] replacement={null};
            if(mode.equals("repeat"))ObjectCompletedEvent.QUEST.register(event->{
                if(event.getQuest()==quest){callbacks[0]++;team.claimReward(actor,reward,false);}return EventResult.pass();
            });
            else ObjectCompletedEvent.TASK.register(event->{
                if(event.getTask()==task)try{
                    callbacks[0]++;
                    switch(mode){
                        case "removed","moved" -> party.leave(owner);
                        case "replacement" -> {task.deleteSelf();replacement[0]=new ItemTask(task.getId(),quest);replacement[0].readData(taskData,server.registryAccess());replacement[0].onCreated();file.clearCachedData();}
                        case "retired" -> server.getPlayerList().remove(actor);
                        case "definition" -> {var changed=questData.copy();changed.putBoolean("disable_toast",true);quest.readData(changed,server.registryAccess());}
                        case "deleted" -> task.deleteSelf();
                        default -> { }
                    }
                }catch(Exception failure){throw new IllegalStateException(failure);}
                return EventResult.pass();
            });
            actor.getInventory().clearContent();actor.getInventory().setItem(0,DeliveryRuntimeAudit.parcel(5));
            int count=service.entries(console).size();FtbQuestTasks.submit(actor,task.getCodeString(),true);
            check(service.entries(console).size()==count&&actor.getInventory().countItem(Items.PAPER)==5,mode,"preflight_retains_components_and_creates_no_intent");
            boolean reported=false;String error=null;
            try{FtbQuestTasks.submit(actor,task.getCodeString(),false);}catch(RuntimeException failure){reported=true;error=failure.getClass().getName()+": "+failure.getMessage();}
            var entry=service.entries(console).stream().filter(e->e.actorId().equals(owner)).findFirst().orElseThrow();
            boolean uncertain=!Set.of("normal","moved","repeat").contains(mode);
            var state=new JsonObject();state.addProperty("mode",mode);state.addProperty("actor",owner.toString());state.addProperty("team",team.getTeamId().toString());state.addProperty("task",task.getCodeString());state.addProperty("quest",quest.getCodeString());state.addProperty("entry",entry.id().toString());
            state.addProperty("reported",reported);state.addProperty("error",error);state.addProperty("state",entry.state().name());state.addProperty("callbacks",callbacks[0]);state.addProperty("paper",actor.getInventory().countItem(Items.PAPER));state.addProperty("nativeTeamPresent",manager.getTeamByID(team.getTeamId()).isPresent());state.addProperty("actorCurrent",server.getPlayerList().getPlayer(owner)==actor);states.add(state);
            check(callbacks[0]==1&&actor.getInventory().countItem(Items.PAPER)==0,mode,"actual_native_completion_and_inventory_consumption_are_retained");
            check(reported==uncertain&&entry.state()==(uncertain?QuestDeliveryJournal.State.REVIEW:QuestDeliveryJournal.State.CONFIRMED)
                    &&entry.before().teamId().equals(team.getTeamId())&&entry.inventoryBefore().contains("native-delivery")
                    &&(uncertain?entry.after()==null:entry.after()!=null),mode,"only_live_unchanged_native_outcomes_receive_confirmation");
            if(mode.equals("removed"))check(manager.getTeamByID(team.getTeamId()).isEmpty()&&file.getNullableTeamData(team.getTeamId())!=null,mode,"native_party_is_deleted_while_quest_data_is_retained");
            if(mode.equals("replacement"))check(file.getTask(task.getId())==replacement[0]&&replacement[0]!=task,mode,"native_task_replacement_reuses_original_public_id");
            if(mode.equals("definition")){var changed=new CompoundTag();quest.writeData(changed,server.registryAccess());check(!changed.equals(questData),mode,"serialized_quest_definition_actually_changed");}
            if(mode.equals("deleted"))check(file.getTask(task.getId())==null||!task.isValid(),mode,"native_task_is_actually_deleted");
            if(mode.equals("retired")){
                var returned=connect(server,profile,players,channels);
                state.addProperty("savedPaperOnReconnect",returned.getInventory().countItem(Items.PAPER));
                check(returned!=actor&&returned.getInventory().countItem(Items.PAPER)==5,mode,"reconnect_observes_inventory_saved_before_retired_native_tail");
                rejected(()->FtbQuestTasks.submit(returned,task.getCodeString(),false),mode,"reconnected_actor_cannot_reconsume_uncertain_saved_inventory");
                check(returned.getInventory().countItem(Items.PAPER)==5,mode,"rejected_reconnect_preserves_actual_saved_inventory");
            }
            if(mode.equals("moved"))check(manager.getTeamByID(team.getTeamId()).isPresent()&&!file.getTeamData(actor).orElseThrow().getTeamId().equals(team.getTeamId())
                    &&entry.after().teamId().equals(team.getTeamId()),mode,"native_party_change_keeps_original_delivery_attribution");
            if(mode.equals("repeat"))check(entry.after().cycle()==entry.before().cycle()+1&&entry.after().progress()==0&&actor.getInventory().countItem(Items.DIAMOND)==1,mode,"actual_repeat_reward_reset_is_a_valid_confirmed_delivery");
            int retained=service.entries(console).size();
            if(uncertain){
                actor.getInventory().setItem(0,DeliveryRuntimeAudit.parcel(2));
                rejected(()->FtbQuestTasks.submit(actor,task.getCodeString(),true),mode,"uncertain_preflight_is_fenced");
                rejected(()->FtbQuestTasks.submit(actor,task.getCodeString(),false),mode,"uncertain_execution_is_fenced");
                check(actor.getInventory().countItem(Items.PAPER)==2&&service.entries(console).size()==retained,mode,"rejected_retries_create_no_intent_and_consume_nothing");
                rejected(()->service.resolve(console,entry.id(),false,"Console lacks authenticated operator"),mode,"console_cannot_resolve_uncertain_native_effects");
            }
            check(service.entries(console).size()==retained&&callbacks[0]==1,mode,"validation_does_not_repeat_native_completion");
        }finally{
            for(var player:players)if(server.getPlayerList().getPlayer(player.getUUID())==player)server.getPlayerList().remove(player);
            channels.forEach(EmbeddedChannel::finishAndReleaseAll);
        }
    }
    private static ServerPlayer connect(MinecraftServer server,GameProfile profile,List<ServerPlayer> players,List<EmbeddedChannel> channels){
        var player=new ServerPlayer(server,server.overworld(),profile,ClientInformation.createDefault());player.setPos(1,-60,1);players.add(player);channels.add(DeliveryRuntimeAudit.connect(player));return player;
    }
    private static void rejected(Runnable action,String mode,String name){try{action.run();throw new AssertionError(name);}catch(RuntimeException expected){check(true,mode,name);}}
    private static void check(boolean condition,String mode,String name){if(!condition)throw new AssertionError(mode+": "+name);passed++;LOG.info("[DELIVERYMUTATIONAUDIT] PASS {} {}",mode,name);}
}
