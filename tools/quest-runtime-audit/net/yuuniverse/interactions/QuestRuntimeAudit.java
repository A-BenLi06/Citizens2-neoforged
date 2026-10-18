package net.yuuniverse.interactions;

import java.util.*;
import com.mojang.authlib.GameProfile;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.task.*;
import dev.ftb.mods.ftblibrary.config.Tristate;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.util.FtbQuestTasks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.*;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid="interactions")
public final class QuestRuntimeAudit {
    private static boolean ran;
    private static int passed;
    private static final List<AuditPlayer> players = new ArrayList<>();
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (ran) return;
        var server = event.getServer(); var level = server.overworld();
        level.setChunkForced(0,0,true);
        if (server.getTickCount() < 20 || !level.isPositionEntityTicking(new BlockPos(1,-60,1))) return;
        ran = true;
        try {
            var file = ServerQuestFile.getInstance().orElseThrow();
            var old = file.getChapter(0x10101L); if (old != null) old.deleteSelf();
            var chapter = new Chapter(0x10101L,file,file.getDefaultChapterGroup()); chapter.onCreated();
            var quest = new Quest(0x10102L,chapter); quest.onCreated();
            var task = new ItemTask(0x10103L,quest).setStackAndCount(new ItemStack(Items.STONE),3);
            task.setConsumeItems(Tristate.TRUE); task.onCreated();
            var custom = new CustomTask(0x10104L,quest); custom.onCreated();
            var actor = player(level,"QuestActor"); var other = player(level,"QuestOther");
            // PlayerList login publishes FTB's player/team lifecycle before returning.
            var team = file.getTeamData(actor).orElseThrow();
            String id = task.getCodeString();
            actor.getInventory().setItem(0,new ItemStack(Items.STONE,5));
            var before = FtbQuestTasks.query(actor,id);
            check(before.teamId().equals(team.getTeamId()) && before.progress()==0 && before.maximum()==3,
                    "query_uses_current_authoritative_team");
            FtbQuestTasks.submit(actor,id,true);
            check(actor.getInventory().countItem(Items.STONE)==5 && team.getProgress(task)==0,
                    "preflight_consumes_no_items_or_progress");
            check(!Conditions.all(List.of("%ftb_task_completed_"+id+"% == true"),actor), "condition_reads_incomplete_task");
            check(server.getCommands().getDispatcher().execute("citizens quest submit "+id,
                    actor.createCommandSourceStack().withPermission(2))==1, "native_quest_submit_command_dispatches");
            check(actor.getInventory().countItem(Items.STONE)==2 && team.getProgress(task)==3,
                    "native_delivery_consumes_only_required_count");
            check(Conditions.all(List.of("%ftb_task_completed_"+id+"% == true", "%ftb_task_progress_"+id+"% == 3"),actor),
                    "dialogue_conditions_share_ftb_progress");
            var actions = new Actions(new ItemLibrary(),new Economy());
            check(actions.validateAll(List.of("ftb_submit: "+id),actor,Component.empty())
                    && actions.runAll(List.of("ftb_submit: "+id),actor,Component.empty())
                    && actor.getInventory().countItem(Items.STONE)==2, "dialogue_duplicate_delivery_does_not_consume_again");
            check(FtbQuestTasks.query(other,id).progress()==0, "different_team_progress_is_independent");
            check(!actions.runAll(List.of("ftb_submit: "+custom.getCodeString()),actor,Component.empty())
                    && team.getProgress(custom)==0, "custom_task_cannot_be_faked_as_item_delivery");
            check(!Conditions.all(List.of("%ftb_task_completed_FFFFFFFFFFFFFFFE% != true"),actor),
                    "missing_task_never_satisfies_negative_condition");
            check(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { FtbQuestTasks.query(actor,id); return false; } catch (IllegalStateException expected) { return true; }
            }).join(), "worker_thread_quest_access_rejected");
            for (boolean packet : new boolean[]{false,true}) {
                var npcTask = new ItemTask(packet ? 0x10112L : 0x10111L,quest)
                        .setStackAndCount(new ItemStack(Items.STONE),3);
                npcTask.setConsumeItems(Tristate.TRUE); npcTask.onCreated();
                actor.getInventory().setItem(0,new ItemStack(Items.STONE,3));
                var npc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry()
                        .createNPC(net.minecraft.world.entity.EntityType.COW,"QuestDelivery"+packet);
                try {
                    if(packet) npc.getOrAddTrait(net.citizensnpcs.trait.PacketNPC.class);
                    npc.spawn(new net.citizensnpcs.api.util.Location(level,2,-60,2));
                    var commands = npc.getOrAddTrait(net.citizensnpcs.trait.CommandTrait.class);
                    commands.addCommand(new net.citizensnpcs.trait.CommandTrait.NPCCommandBuilder(
                            "citizens quest submit "+npcTask.getCodeString(),net.citizensnpcs.trait.CommandTrait.Hand.RIGHT)
                            .player(true).cooldown(-1));
                    commands.dispatch(actor,net.citizensnpcs.trait.CommandTrait.Hand.RIGHT);
                    net.citizensnpcs.Citizens.getInstance().getScheduler().tick();
                    check(team.getProgress(npcTask)==3 && actor.getInventory().countItem(Items.STONE)==0,
                            "npc_delivery_uses_native_team_task_"+packet);
                } finally { npc.destroy(); }
            }
            var recursion = new java.util.concurrent.atomic.AtomicBoolean();
            var recursiveTask = new ItemTask(0x10113L,quest) {
                @Override public void submitTask(TeamData data,ServerPlayer player,ItemStack stack) {
                    try { FtbQuestTasks.submit(player,getCodeString(),false); }
                    catch (IllegalStateException expected) { recursion.set(true); }
                    super.submitTask(data,player,stack);
                }
            };
            recursiveTask.setStackAndCount(new ItemStack(Items.STONE),1);
            recursiveTask.setConsumeItems(Tristate.TRUE); recursiveTask.onCreated();
            actor.getInventory().setItem(0,new ItemStack(Items.STONE,2));
            FtbQuestTasks.submit(actor,recursiveTask.getCodeString(),false);
            check(recursion.get() && actor.getInventory().countItem(Items.STONE)==1 && team.getProgress(recursiveTask)==1,
                    "recursive_delivery_callback_cannot_reuse_inflight_inventory");
            check(!actions.validateAll(List.of("ftb_submit: "+id),other,Component.empty())
                    && FtbQuestTasks.query(other,id).progress()==0, "empty_inventory_delivery_fails_during_preflight");
            var craftTask = new ItemTask(0x10114L,quest) {
                @Override public boolean isOnlyFromCrafting() { return true; }
            };
            craftTask.setStackAndCount(new ItemStack(Items.STONE),1);
            craftTask.setConsumeItems(Tristate.TRUE); craftTask.onCreated();
            check(!actions.validateAll(List.of("ftb_submit: "+craftTask.getCodeString()),actor,Component.empty())
                    && team.getProgress(craftTask)==0 && actor.getInventory().countItem(Items.STONE)==1,
                    "craft_only_task_rejects_possessed_items_even_if_consuming");
            var party = FTBTeamsAPI.api().getManager().createPartyTeam(actor,"QuestAuditParty","fixture",
                    dev.ftb.mods.ftblibrary.icon.Color4I.WHITE);
            check(FtbQuestTasks.query(actor,id).teamId().equals(party.getId()), "party_change_reacquires_team_identity");
            LoggerFactory.getLogger("interactions").info("[QUESTAUDIT] COMPLETE {} checks",passed);
        } catch(Throwable failure) { LoggerFactory.getLogger("interactions").error("[QUESTAUDIT] FAILED",failure); }
        finally {
            for(var player:players) { server.getPlayerList().remove(player); player.channel.finishAndReleaseAll(); }
            server.halt(false);
        }
    }
    private static AuditPlayer player(ServerLevel level,String name) {
        var player = new AuditPlayer(level,name); players.add(player); player.setPos(1,-60,1);
        var connection = new Connection(PacketFlow.SERVERBOUND);
        player.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                Connection.configureInMemoryPipeline(channel.pipeline(),PacketFlow.SERVERBOUND);
                connection.configurePacketHandler(channel.pipeline());
            }
        });
        NetworkRegistry.configureMockConnection(connection);
        var cookie = new CommonListenerCookie(player.getGameProfile(),0,ClientInformation.createDefault(),false,ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(level.registryAccess(),cookie.connectionType())));
        level.getServer().getPlayerList().placeNewPlayer(connection,player,cookie);
        return player;
    }
    private static final class AuditPlayer extends ServerPlayer {
        EmbeddedChannel channel;
        AuditPlayer(ServerLevel level,String name) { super(level.getServer(),level,new GameProfile(UUID.randomUUID(),name),ClientInformation.createDefault()); }
        @Override public void sendSystemMessage(Component message) { }
    }
    private static void check(boolean condition,String name) {
        if(!condition) throw new AssertionError(name);
        passed++; LoggerFactory.getLogger("interactions").info("[QUESTAUDIT] PASS {}",name);
    }
}
