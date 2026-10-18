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
            auditItems(actor,other);
            auditMigration(actor);
            LoggerFactory.getLogger("interactions").info("[QUESTAUDIT] COMPLETE {} checks",passed);
        } catch(Throwable failure) { LoggerFactory.getLogger("interactions").error("[QUESTAUDIT] FAILED",failure); }
        finally {
            for(var player:players) { server.getPlayerList().remove(player); player.channel.finishAndReleaseAll(); }
            server.halt(false);
        }
    }
    private static void auditItems(AuditPlayer actor,AuditPlayer other) throws Exception {
        var server=actor.getServer();
        var stores=org.resonaresmp.rsitemeditor.storage.Stores.INSTANCE;
        var shared=stores.server(server); var personal=stores.personal(actor); var otherPersonal=stores.personal(other);
        String id="adapter-fixture";
        var saved=new ItemStack(Items.STONE,7);
        saved.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,Component.literal("Complete component fixture"));
        var tag=new net.minecraft.nbt.CompoundTag(); tag.putString("scope","shared");
        saved.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(tag));
        shared.put(actor.registryAccess(),id,saved);
        personal.put(actor.registryAccess(),id,new ItemStack(Items.DIAMOND,5));
        otherPersonal.put(actor.registryAccess(),id,new ItemStack(Items.PAPER,2));
        var sharedRef=new net.citizensnpcs.util.RSItemEditorItems.Reference(net.citizensnpcs.util.RSItemEditorItems.Scope.SHARED,id);
        var personalRef=new net.citizensnpcs.util.RSItemEditorItems.Reference(net.citizensnpcs.util.RSItemEditorItems.Scope.PERSONAL,id);
        var permissions=org.resonaresmp.rsitemeditor.permission.Permissions.INSTANCE;
        check("paradigm:internal".equals(String.valueOf(net.neoforged.neoforge.server.permission.PermissionAPI.getActivePermissionHandler())),
                "rsitems_use_actual_paradigm_permission_handler");
        try { net.citizensnpcs.util.RSItemEditorItems.snapshot(actor,sharedRef); throw new AssertionError("missing permission rejection"); }
        catch(IllegalStateException expected) { check(true,"rsitems_unprivileged_lookup_rejected"); }
        for(var node:List.of(permissions.serverItem("get"),permissions.serverItem("list"),permissions.itemStorage("get")))
            server.getCommands().getDispatcher().execute("paradigm group user perm add "+actor.getUUID()+" "+
                    com.mojang.brigadier.arguments.StringArgumentType.escapeIfRequired(node.getNodeName()),server.createCommandSourceStack());
        server.getCommands().getDispatcher().execute("paradigm group user perm add "+other.getUUID()+" "+
                com.mojang.brigadier.arguments.StringArgumentType.escapeIfRequired(permissions.itemStorage("get").getNodeName()),server.createCommandSourceStack());
        var snapshot=net.citizensnpcs.util.RSItemEditorItems.snapshot(actor,sharedRef);
        check(ItemStack.matches(snapshot,saved),"rsitems_snapshot_preserves_components_and_saved_count");
        snapshot.setCount(1); snapshot.remove(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
        check(ItemStack.matches(net.citizensnpcs.util.RSItemEditorItems.snapshot(actor,sharedRef),saved),"rsitems_snapshot_mutation_cannot_change_provider");
        check(net.citizensnpcs.util.RSItemEditorItems.snapshot(actor,personalRef).is(Items.DIAMOND)
                && net.citizensnpcs.util.RSItemEditorItems.snapshot(other,personalRef).is(Items.PAPER),"rsitems_personal_namespaces_are_owner_scoped");
        try { net.citizensnpcs.util.RSItemEditorItems.give(server.createCommandSourceStack(),actor,personalRef,1,true);
            throw new AssertionError("personal console access accepted"); }
        catch(IllegalStateException expected) { check(true,"rsitems_console_cannot_substitute_personal_owner"); }
        var ids=net.citizensnpcs.util.RSItemEditorItems.ids(actor,net.citizensnpcs.util.RSItemEditorItems.Scope.SHARED);
        try { ids.clear(); throw new AssertionError("mutable ids"); } catch(UnsupportedOperationException expected) { check(ids.contains(id),"rsitems_ids_are_defensive"); }
        actor.getInventory().clearContent();
        var actions=new Actions(new ItemLibrary(),new Economy());
        check(actions.validateAll(List.of("rs_item: shared;"+id+";130"),actor,Component.empty())
                && actor.getInventory().countItem(Items.STONE)==0,"rsitems_dialogue_preflight_has_no_inventory_effect");
        check(actions.runAll(List.of("rs_item: shared;"+id+";130"),actor,Component.empty())
                && actor.getInventory().countItem(Items.STONE)==130 && shared.get(actor.registryAccess(),id).getCount()==7,
                "rsitems_dialogue_grant_preserves_exact_requested_count");
        for(boolean packet:new boolean[]{false,true}) {
            actor.getInventory().clearContent();
            var npc=net.citizensnpcs.api.CitizensAPI.getNPCRegistry()
                    .createNPC(net.minecraft.world.entity.EntityType.COW,"SavedItem"+packet);
            try {
                if(packet) npc.getOrAddTrait(net.citizensnpcs.trait.PacketNPC.class);
                npc.spawn(new net.citizensnpcs.api.util.Location(actor.serverLevel(),2,-60,2));
                var commands=npc.getOrAddTrait(net.citizensnpcs.trait.CommandTrait.class);
                commands.addCommand(new net.citizensnpcs.trait.CommandTrait.NPCCommandBuilder(
                        "citizens saveditem shared "+id+" 2",net.citizensnpcs.trait.CommandTrait.Hand.RIGHT)
                        .player(true).op(true).cooldown(-1));
                commands.dispatch(actor,net.citizensnpcs.trait.CommandTrait.Hand.RIGHT);
                net.citizensnpcs.Citizens.getInstance().getScheduler().tick();
                check(actor.getInventory().countItem(Items.STONE)==2,"rsitems_native_npc_grant_"+packet);
            } finally { npc.destroy(); }
        }
        shared.put(actor.registryAccess(),id,new ItemStack(Items.GOLD_INGOT,9));
        check(net.citizensnpcs.util.RSItemEditorItems.snapshot(actor,sharedRef).is(Items.GOLD_INGOT)
                && snapshot.is(Items.STONE),"rsitems_redefinition_changes_future_references_only");
        shared.remove(id);
        try { net.citizensnpcs.util.RSItemEditorItems.snapshot(actor,sharedRef); throw new AssertionError("deleted reference accepted"); }
        catch(IllegalArgumentException expected) { check(snapshot.is(Items.STONE),"rsitems_deletion_retains_existing_snapshots"); }
        shared.put(actor.registryAccess(),id,saved);
        server.getCommands().getDispatcher().execute("paradigm group user perm remove "+actor.getUUID()+" "+
                com.mojang.brigadier.arguments.StringArgumentType.escapeIfRequired(permissions.serverItem("get").getNodeName()),server.createCommandSourceStack());
        try { net.citizensnpcs.util.RSItemEditorItems.snapshot(actor,sharedRef); throw new AssertionError("revoked lookup accepted"); }
        catch(IllegalStateException expected) { check(true,"rsitems_permission_revocation_takes_effect_immediately"); }
    }
    private static void auditMigration(AuditPlayer actor) throws Exception {
        var server=actor.getServer();
        var store=org.resonaresmp.rsitemeditor.storage.Stores.INSTANCE.server(server);
        String prefix="migration-"+UUID.randomUUID();
        var directory=java.nio.file.Files.createTempDirectory(server.getServerDirectory(),"migration-fixture-");
        var input=directory.resolve("items.yml");
        String yaml="""
                %s-valid:
                  item:
                    type: STONE
                    amount: 7
                    meta:
                      display-name: '{"text":"Preserved item"}'
                      lore: ['{"text":"Preserved lore"}']
                %s-conflict:
                  item: {type: DIAMOND, amount: 2}
                %s-unsupported:
                  item: {type: STONE, meta: {unbreakable: true}}
                %s-unavailable:
                  item: {type: absent:item}
                %s-malformed: invalid
                %s-amount:
                  item: {type: STONE, amount: 1.5}
                """.formatted(prefix,prefix,prefix,prefix,prefix,prefix);
        java.nio.file.Files.writeString(input,yaml);
        byte[] original=java.nio.file.Files.readAllBytes(input);
        store.put(actor.registryAccess(),prefix+"-conflict",new ItemStack(Items.GOLD_INGOT,3));
        var preview=LegacySavedItemMigration.run(server.createCommandSourceStack(),input,directory.resolve("reports"),false);
        var gson=new com.google.gson.Gson();
        var planned=gson.fromJson(java.nio.file.Files.readString(preview),LegacySavedItemMigration.Report.class);
        check(planned.entries().get(prefix+"-valid").status().equals("WOULD_IMPORT") && !store.contains(prefix+"-valid"),
                "migration_preview_never_writes_provider");
        check(planned.entries().get(prefix+"-conflict").status().equals("CONFLICT") &&
                planned.entries().values().stream().filter(e->e.status().equals("UNAVAILABLE")).count()==4,
                "migration_reports_conflicts_unsupported_missing_and_malformed_items");
        var applied=LegacySavedItemMigration.run(server.createCommandSourceStack(),input,directory.resolve("reports"),true);
        var report=gson.fromJson(java.nio.file.Files.readString(applied),LegacySavedItemMigration.Report.class);
        var imported=store.get(actor.registryAccess(),prefix+"-valid");
        check(report.entries().get(prefix+"-valid").status().equals("IMPORTED") && imported.getCount()==7
                && imported.getHoverName().getString().equals("Preserved item")
                && imported.get(net.minecraft.core.component.DataComponents.LORE).lines().getFirst().getString().equals("Preserved lore"),
                "migration_preserves_count_name_and_lore_in_native_provider");
        check(store.get(actor.registryAccess(),prefix+"-conflict").is(Items.GOLD_INGOT)
                && store.get(actor.registryAccess(),prefix+"-conflict").getCount()==3,
                "migration_never_overwrites_conflicting_provider_entry");
        check(Arrays.equals(original,java.nio.file.Files.readAllBytes(input)) &&
                Arrays.equals(original,java.nio.file.Files.readAllBytes(applied.getParent().resolve("source.yml"))) &&
                report.sha256().equals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original))),
                "migration_retains_exact_source_backup_and_hash");
        var repeated=LegacySavedItemMigration.run(server.createCommandSourceStack(),input,directory.resolve("reports"),true);
        check(gson.fromJson(java.nio.file.Files.readString(repeated),LegacySavedItemMigration.Report.class)
                .entries().get(prefix+"-valid").status().equals("UNCHANGED"),"migration_replay_is_idempotent");
        try {
            net.citizensnpcs.util.RSItemEditorItems.importShared(actor.createCommandSourceStack(),prefix+"-denied",new ItemStack(Items.STONE),true);
            throw new AssertionError("unprivileged import accepted");
        } catch(IllegalStateException expected) {
            check(!store.contains(prefix+"-denied"),"migration_requires_native_save_permission");
        }
        for(String suffix:List.of("valid","conflict")) store.remove(prefix+"-"+suffix);
        var originalInput=server.getServerDirectory().resolve("original-items.yml");
        if(java.nio.file.Files.isRegularFile(originalInput)) {
            ItemAliases.load(server.getServerDirectory().resolve("original-item-aliases.yml").toFile());
            var recovered=ItemLibrary.migrationSource(originalInput.toFile(),server.registryAccess());
            LoggerFactory.getLogger("interactions").info("[QUESTAUDIT] Original snapshots={}, unavailable={}",recovered.snapshots().size(),recovered.unavailable());
            check(recovered.snapshots().size()==139 && recovered.unavailable().size()==1
                    && recovered.unavailable().containsKey("163"),"migration_original_content_resolves_139_and_retains_missing_eyephone");
            var result=LegacySavedItemMigration.run(server.createCommandSourceStack(),originalInput,directory.resolve("original-reports"),true);
            var actual=gson.fromJson(java.nio.file.Files.readString(result),LegacySavedItemMigration.Report.class);
            var statuses=actual.entries().values().stream().collect(java.util.stream.Collectors.groupingBy(
                    LegacySavedItemMigration.Entry::status,java.util.TreeMap::new,java.util.stream.Collectors.counting()));
            LoggerFactory.getLogger("interactions").info("[QUESTAUDIT] Original migration results={}",statuses);
            for(var entry:recovered.snapshots().entrySet()) {
                var status=actual.entries().get(entry.getKey()).status();
                if(!(status.equals("IMPORTED") || status.equals("UNCHANGED")) ||
                        !ItemStack.matches(entry.getValue(),store.get(server.registryAccess(),entry.getKey())))
                    throw new AssertionError("Recovered item import mismatch: "+entry.getKey()+" "+status);
            }
            check(actual.entries().get("163").status().equals("UNAVAILABLE"),"migration_original_139_native_snapshots_roundtrip_and_unavailable_source_retained");
            check(Arrays.equals(recovered.original(),java.nio.file.Files.readAllBytes(originalInput)),"migration_original_database_is_unchanged");
            check(!recovered.legacyNicknames().isEmpty() && actual.legacyNicknames().equals(recovered.legacyNicknames()),
                    "migration_original_nicknames_retained_without_inventing_provider_aliases");
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
