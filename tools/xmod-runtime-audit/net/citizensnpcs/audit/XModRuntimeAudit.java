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
            LoggerFactory.getLogger("citizens").info("[XMODAUDIT] COMPLETE {} checks",passed);
        } catch(Throwable failure) { LoggerFactory.getLogger("citizens").error("[XMODAUDIT] FAILED",failure); }
        finally {
            try { if(service!=null) service.close(); } catch(Exception failure) { LoggerFactory.getLogger("citizens").error("[XMODAUDIT] FAILED cleanup",failure); }
            if(actor!=null && actor.connection!=null) server.getPlayerList().remove(actor);
            if(channel!=null) channel.finishAndReleaseAll(); server.halt(false);
        }
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
    private static EmbeddedChannel connect(ServerPlayer player) {
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
