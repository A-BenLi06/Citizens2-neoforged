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
import net.citizensnpcs.util.QuestEvidenceJournal;
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
    private static java.util.concurrent.CompletableFuture<Void> recipeReload;
    private static String beforeRecipeReload;
    private static boolean recipeReloadChecked;
    private static final List<AuditPlayer> players = new ArrayList<>();
    private static final Map<ServerPlayer,EmbeddedChannel> realPlayers = new LinkedHashMap<>();
    private static final List<net.citizensnpcs.util.CraftingEvidence.Observation> crafts = new ArrayList<>();
    private static final List<net.citizensnpcs.api.event.CraftingOutcomeEvent> craftOutcomes = new ArrayList<>();
    private static final Map<UUID,List<ItemStack>> outcomeGrids = new HashMap<>();
    private static net.neoforged.neoforge.event.entity.player.PlayerEvent.ItemCraftedEvent lastCraft;
    private static boolean recordingCrafts;
    private static boolean failCraftConsumer;
    private static boolean reenterCraftBridge;
    private static boolean failOutcomeConsumer;
    private static QuestEvidenceJournal craftJournal;
    private static QuestEvidenceJournal.Generation craftGeneration;
    private static String craftJournalTask;
    @SubscribeEvent public static void craftOutcome(net.citizensnpcs.api.event.CraftingOutcomeEvent event) {
        if(!recordingCrafts) return;
        craftOutcomes.add(event);
        if(craftJournal!=null) {
            var entry=craftJournal.entries().stream().filter(candidate->candidate.admission().observationId().equals(event.getObservation().id())).findFirst().orElseThrow();
            try { craftJournal.outcome(entry.id(),QuestEvidenceJournal.Outcome.valueOf(event.getOutcome().name())); }
            catch(java.io.IOException failure) { throw new IllegalStateException("Fixture outcome journal failed",failure); }
        }
        realPlayers.keySet().stream().filter(player->player.getUUID().equals(event.getObservation().actorId())).findFirst().ifPresent(player->{
            if(player.containerMenu.getSlot(1).container instanceof net.minecraft.world.inventory.CraftingContainer grid)
                outcomeGrids.put(event.getObservation().id(),grid.getItems().stream().map(ItemStack::copy).toList());
        });
        if(failOutcomeConsumer) throw new IllegalStateException("Expected fixture outcome listener failure");
    }
    @SubscribeEvent public static void craftResult(net.citizensnpcs.api.event.CraftingResultEvent event) {
        if (!recordingCrafts) return;
        crafts.add(event.getObservation());
        if(craftJournal!=null) {
            var observation=event.getObservation();
            try { craftJournal.admit(new QuestEvidenceJournal.Admission(observation.id(),craftGeneration.id(),craftJournalTask,
                    observation.actorId(),observation.recipe().id().toString(),observation.recipe().revision(),observation.output().getCount(),
                    net.citizensnpcs.util.CraftingEvidence.encode(observation,event.getPlayer().registryAccess()))); }
            catch(java.io.IOException failure) { throw new IllegalStateException("Fixture admission journal failed",failure); }
        }
        if (reenterCraftBridge) { reenterCraftBridge=false; net.citizensnpcs.util.CraftingEvidence.onCrafted(lastCraft); }
        if (failCraftConsumer) throw new IllegalStateException("Expected fixture consumer failure");
    }
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void nativeCraft(net.neoforged.neoforge.event.entity.player.PlayerEvent.ItemCraftedEvent event) {
        if (recordingCrafts) lastCraft=event;
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (ran) return;
        var server = event.getServer(); var level = server.overworld();
        level.setChunkForced(0,0,true);
        if (server.getTickCount() < 20 || !level.isPositionEntityTicking(new BlockPos(1,-60,1))) return;
        try {
            var script=server.getServerDirectory().resolve("kubejs/server_scripts/citizens_recipe_audit.js");
            if(java.nio.file.Files.isRegularFile(script) && !recipeReloadChecked) {
                var recipeId=net.minecraft.resources.ResourceLocation.parse("citizens_audit:kubejs_recipe");
                if(recipeReload==null) {
                    var before=net.citizensnpcs.util.CraftingRecipes.find(server,recipeId).orElseThrow();
                    LoggerFactory.getLogger("interactions").info("[QUESTAUDIT] KubeJS recipe kind={}, output={}",before.kind(),before.output());
                    check(before.supported() && before.output().getCount()==3,"recipes_kubejs_initial_script_loaded_before_reload");
                    beforeRecipeReload=before.revision();
                    String original=java.nio.file.Files.readString(script);
                    String updated=original.replace("Item.of('minecraft:paper', 3)","Item.of('minecraft:paper', 4)");
                    if(original.equals(updated)) throw new AssertionError("Unexpected KubeJS fixture script");
                    java.nio.file.Files.writeString(script,updated);
                    recipeReload=server.reloadResources(server.getPackRepository().getSelectedIds());
                    return;
                }
                if(!recipeReload.isDone()) return;
                recipeReload.join();
                check(net.citizensnpcs.util.CraftingRecipes.find(server,recipeId).orElseThrow().output().getCount()==4
                        && !net.citizensnpcs.util.CraftingRecipes.current(server,recipeId,beforeRecipeReload),
                        "recipes_actual_kubejs_datapack_reload_invalidates_old_snapshot");
                recipeReloadChecked=true;
            }
        } catch(Throwable failure) {
            ran=true; LoggerFactory.getLogger("interactions").error("[QUESTAUDIT] FAILED",failure); server.halt(false); return;
        }
        ran = true;
        try {
            var file = ServerQuestFile.getInstance().orElseThrow();
            var old = file.getChapter(0x10101L); if (old != null) old.deleteSelf();
            var chapter = new Chapter(0x10101L,file,file.getDefaultChapterGroup()); chapter.onCreated();
            var quest = new Quest(0x10102L,chapter); quest.onCreated();
            var task = new ItemTask(0x10103L,quest).setStackAndCount(new ItemStack(Items.STONE),3);
            task.setConsumeItems(Tristate.TRUE); task.onCreated();
            var custom = new CustomTask(0x10104L,quest); custom.onCreated();
            var nativeCraftTask = (ItemTask) Objects.requireNonNull(file.getTask(0x10403L),"Missing prepared native crafting task");
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
            auditDiscovery(actor,other);
            auditRecipes(actor);
            var craftActor=realPlayer(level,"CraftActor");
            FTBTeamsAPI.api().getManager().createPartyTeam(craftActor,"CraftAuditParty","fixture",
                    dev.ftb.mods.ftblibrary.icon.Color4I.WHITE);
            auditCrafting(craftActor,other,nativeCraftTask);
            auditMigration(actor);
            LoggerFactory.getLogger("interactions").info("[QUESTAUDIT] COMPLETE {} checks",passed);
        } catch(Throwable failure) { LoggerFactory.getLogger("interactions").error("[QUESTAUDIT] FAILED",failure); }
        finally {
            for(var player:players) { server.getPlayerList().remove(player); player.channel.finishAndReleaseAll(); }
            for(var entry:realPlayers.entrySet()) { server.getPlayerList().remove(entry.getKey()); entry.getValue().finishAndReleaseAll(); }
            server.halt(false);
        }
    }
    private static void auditCrafting(ServerPlayer actor,AuditPlayer other,ItemTask task) throws Exception {
        var server=actor.getServer(); var manager=server.getRecipeManager();
        var original=new ArrayList<net.minecraft.world.item.crafting.RecipeHolder<?>>(manager.getRecipes());
        var id=net.minecraft.resources.ResourceLocation.parse("citizens_audit:craft_evidence");
        var required=new ItemStack(Items.STONE);
        required.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,Component.literal("Craft evidence input"));
        var output=new ItemStack(Items.PAPER,3);
        output.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,Component.literal("Craft evidence output"));
        var recipe=new net.minecraft.world.item.crafting.ShapelessRecipe("",net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                output,net.minecraft.core.NonNullList.of(net.minecraft.world.item.crafting.Ingredient.EMPTY,
                        net.neoforged.neoforge.common.crafting.DataComponentIngredient.of(false,required),
                        net.minecraft.world.item.crafting.Ingredient.of(Items.NETHER_STAR)));
        var augmented=new ArrayList<net.minecraft.world.item.crafting.RecipeHolder<?>>(original);
        augmented.add(new net.minecraft.world.item.crafting.RecipeHolder<>(id,recipe)); manager.replaceRecipes(augmented);
        var previousMenu=actor.containerMenu;
        var station=actor.blockPosition().offset(1,0,0); var stationBefore=actor.serverLevel().getBlockState(station);
        actor.serverLevel().setBlockAndUpdate(station,net.minecraft.world.level.block.Blocks.CRAFTING_TABLE.defaultBlockState());
        var menu=new net.minecraft.world.inventory.CraftingMenu(91,actor.getInventory(),
                net.minecraft.world.inventory.ContainerLevelAccess.create(actor.serverLevel(),station));
        actor.containerMenu=menu; actor.getInventory().clearContent(); crafts.clear(); craftOutcomes.clear(); outcomeGrids.clear(); recordingCrafts=true;
        var team=ServerQuestFile.getInstance().orElseThrow().getTeamData(actor).orElseThrow();
        long before=team.getProgress(task);
        var journalPath=java.nio.file.Files.createTempDirectory(server.getServerDirectory(),"craft-evidence-journal-");
        craftJournal=QuestEvidenceJournal.open(journalPath);
        craftGeneration=craftJournal.generation(new QuestEvidenceJournal.Scope(UUID.randomUUID(),team.getTeamId(),task.getQuest().getCodeString()),
                team.getCompletionCount(task.getQuest()));
        craftJournalTask=task.getCodeString();
        try {
            check(!dev.architectury.hooks.level.entity.PlayerHooks.isFake(actor) && task.isOnlyFromCrafting()
                    && ServerQuestFile.getInstance().orElseThrow().getCraftingTasks().contains(task),
                    "craft_evidence_fixture_uses_native_actor_and_registered_crafting_task");
            menu.getSlot(1).set(required.copyWithCount(3));
            menu.getSlot(2).set(new ItemStack(Items.NETHER_STAR,3));
            check(ItemStack.matches(menu.getSlot(0).getItem(),output),"craft_evidence_native_menu_resolves_actual_recipe_output");
            reenterCraftBridge=true; menu.clicked(0,0,net.minecraft.world.inventory.ClickType.PICKUP,actor);
            check(crafts.size()==1 && !reenterCraftBridge && ItemStack.matches(menu.getCarried(),output) && menu.getSlot(1).getItem().getCount()==2,
                    "craft_evidence_pickup_with_callback_reentry_records_one_full_batch_and_native_consumption");
            var first=crafts.getFirst();
            check(craftOutcomes.size()==1 && craftOutcomes.getFirst().getOutcome()==net.citizensnpcs.api.event.CraftingOutcomeEvent.Outcome.RETURNED
                    && craftOutcomes.getFirst().getObservation().id().equals(first.id()) && outcomeGrids.get(first.id()).getFirst().getCount()==2,
                    "craft_outcome_pickup_returns_only_after_native_input_processing");
            check(first.actorId().equals(actor.getUUID()) && first.recipe().id().equals(id)
                    && first.recipe().revision().equals(net.citizensnpcs.util.CraftingRecipes.find(server,id).orElseThrow().revision())
                    && first.source()==net.citizensnpcs.util.CraftingEvidence.Source.CRAFTING_TABLE
                    && first.width()==3 && first.height()==3 && first.inputs().getFirst().getCount()==3
                    && ItemStack.matches(first.output(),output),"craft_evidence_attributes_recipe_revision_actor_and_preconsumption_grid");
            check(team.getProgress(task)==before+3,"craft_evidence_native_ftb_hook_counts_actual_output_batch");
            menu.setCarried(ItemStack.EMPTY);
            menu.clicked(0,0,net.minecraft.world.inventory.ClickType.QUICK_MOVE,actor);
            check(craftOutcomes.size()==3 && craftOutcomes.stream().allMatch(event->event.getOutcome()==net.citizensnpcs.api.event.CraftingOutcomeEvent.Outcome.RETURNED)
                    && craftOutcomes.stream().map(event->event.getObservation().id()).distinct().count()==3,
                    "craft_outcome_shift_scopes_publish_one_return_per_batch");
            check(crafts.size()==3 && menu.getSlot(1).getItem().isEmpty() && actor.getInventory().countItem(Items.PAPER)==6
                    && crafts.stream().mapToInt(craft->craft.output().getCount()).sum()==9
                    && crafts.stream().map(craft->craft.id()).distinct().count()==3,
                    "craft_evidence_shift_crafting_counts_each_completed_batch_once");
            check(team.getProgress(task)==before+9
                    && ServerQuestFile.getInstance().orElseThrow().getTeamData(other).orElseThrow().getProgress(task)==0,
                    "craft_evidence_native_ftb_progress_uses_current_party_only");
            var nativeEvent=lastCraft; int count=crafts.size();
            // Re-enter this bridge only: replaying the provider event itself would also replay FTB's own handler.
            net.citizensnpcs.util.CraftingEvidence.onCrafted(nativeEvent);
            check(crafts.size()==count,"craft_evidence_same_native_event_cannot_publish_twice");
            var outputCopy=first.output(); outputCopy.setCount(1);
            var inputCopy=first.inputs().getFirst(); inputCopy.setCount(1);
            check(first.output().getCount()==3 && first.inputs().getFirst().getCount()==3,
                    "craft_evidence_snapshots_are_defensive");
            actor.getInventory().setItem(0,output.copyWithCount(12));
            check(crafts.size()==count && team.getProgress(task)==before+9,
                    "craft_evidence_inventory_assignment_is_not_crafting");
            for(int slot=0;slot<actor.getInventory().items.size();slot++) actor.getInventory().setItem(slot,new ItemStack(Items.DIAMOND,64));
            actor.getInventory().setItem(0,output.copyWithCount(63));
            var dropArea=actor.getBoundingBox().inflate(4);
            var existingDrops=actor.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,dropArea)
                    .stream().map(entity->entity.getUUID()).collect(java.util.stream.Collectors.toSet());
            menu.getSlot(1).set(required.copy()); menu.getSlot(2).set(new ItemStack(Items.NETHER_STAR));
            failOutcomeConsumer=true; menu.clicked(0,0,net.minecraft.world.inventory.ClickType.QUICK_MOVE,actor); failOutcomeConsumer=false;
            var newDrops=actor.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,dropArea)
                    .stream().filter(entity->!existingDrops.contains(entity.getUUID())).toList();
            check(crafts.size()==count+1 && crafts.getLast().output().getCount()==3
                    && actor.getInventory().countItem(Items.PAPER)==64 && menu.getSlot(1).getItem().isEmpty()
                    && newDrops.stream().filter(entity->ItemStack.isSameItemSameComponents(entity.getItem(),output))
                        .mapToInt(entity->entity.getItem().getCount()).sum()==2 && team.getProgress(task)==before+12,
                    "craft_evidence_partial_space_counts_full_batch_including_native_dropped_output");
            for(var entity:newDrops) entity.discard(); count=crafts.size();
            menu.getSlot(1).set(required.copy()); menu.getSlot(2).set(new ItemStack(Items.NETHER_STAR));
            menu.clicked(0,0,net.minecraft.world.inventory.ClickType.QUICK_MOVE,actor);
            check(crafts.size()==count && menu.getSlot(1).getItem().getCount()==1 && team.getProgress(task)==before+12,
                    "craft_evidence_full_inventory_failed_shift_emits_no_observation_or_progress");
            actor.containerMenu=actor.inventoryMenu;
            actor.inventoryMenu.getCraftSlots().setItem(0,required.copy());
            actor.inventoryMenu.getCraftSlots().setItem(1,new ItemStack(Items.NETHER_STAR));
            actor.inventoryMenu.clicked(0,0,net.minecraft.world.inventory.ClickType.PICKUP,actor);
            check(crafts.size()==count+1 && crafts.getLast().source()==net.citizensnpcs.util.CraftingEvidence.Source.PLAYER_GRID
                    && crafts.getLast().width()==2 && crafts.getLast().height()==2,
                    "craft_evidence_player_grid_uses_native_recipe_holder");
            actor.inventoryMenu.setCarried(ItemStack.EMPTY); actor.containerMenu=menu;
            var kubeId=net.minecraft.resources.ResourceLocation.parse("citizens_audit:kubejs_recipe");
            if(manager.byKey(kubeId).isPresent()) {
                @SuppressWarnings("unchecked")
                var kubeRecipe=(net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe>)
                        (net.minecraft.world.item.crafting.RecipeHolder<?>)manager.byKey(kubeId).orElseThrow();
                int beforeKube=crafts.size();
                // The recipe-book placement path selects this valid recipe when another recipe accepts the same input.
                menu.beginPlacingRecipe(); menu.clearCraftingContent(); menu.getSlot(1).set(new ItemStack(Items.STONE));
                menu.finishPlacingRecipe(kubeRecipe);
                menu.clicked(0,0,net.minecraft.world.inventory.ClickType.PICKUP,actor);
                check(crafts.size()==beforeKube+1 && crafts.getLast().recipe().id().equals(kubeId)
                        && crafts.getLast().output().getCount()==4 && menu.getCarried().getCount()==4
                        && menu.getSlot(1).getItem().isEmpty(),"craft_evidence_actual_kubejs_reloaded_recipe_is_attributed_and_consumed");
                menu.setCarried(ItemStack.EMPTY);
            }
            menu.getSlot(1).set(required.copy()); menu.getSlot(2).set(new ItemStack(Items.NETHER_STAR)); failCraftConsumer=true;
            menu.clicked(0,0,net.minecraft.world.inventory.ClickType.PICKUP,actor);
            check(menu.getSlot(1).getItem().isEmpty() && ItemStack.matches(menu.getCarried(),output),
                    "craft_evidence_consumer_failure_does_not_abort_native_consumption");
            failCraftConsumer=false;
            var failureId=net.minecraft.resources.ResourceLocation.parse("citizens_audit:craft_native_failure");
            var failureRecipe=new net.minecraft.world.item.crafting.ShapelessRecipe("",net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                    output,net.minecraft.core.NonNullList.of(net.minecraft.world.item.crafting.Ingredient.EMPTY,
                            net.minecraft.world.item.crafting.Ingredient.of(CraftFailureIngredient.item),
                            net.minecraft.world.item.crafting.Ingredient.of(Items.NETHER_STAR)));
            augmented.add(new net.minecraft.world.item.crafting.RecipeHolder<>(failureId,failureRecipe)); manager.replaceRecipes(augmented);
            for(var click:new net.minecraft.world.inventory.ClickType[]{net.minecraft.world.inventory.ClickType.PICKUP,net.minecraft.world.inventory.ClickType.QUICK_MOVE}) {
                menu.clearCraftingContent(); menu.setCarried(ItemStack.EMPTY); actor.getInventory().clearContent();
                menu.getSlot(1).set(new ItemStack(CraftFailureIngredient.item)); menu.getSlot(2).set(new ItemStack(Items.NETHER_STAR));
                CraftFailureIngredient.fail=true; int prior=craftOutcomes.size();
                try { menu.clicked(0,0,click,actor); throw new AssertionError("Native remainder failure did not escape"); }
                catch(RuntimeException expected) {
                    if(!expected.toString().contains("Expected native remainder failure") && (expected.getCause()==null
                            || !expected.getCause().toString().contains("Expected native remainder failure"))) throw expected;
                } finally { CraftFailureIngredient.fail=false; }
                check(craftOutcomes.size()==prior+1 && craftOutcomes.getLast().getOutcome()==net.citizensnpcs.api.event.CraftingOutcomeEvent.Outcome.INTERRUPTED
                        && craftOutcomes.getLast().getObservation().id().equals(crafts.getLast().id()) && menu.getSlot(1).getItem().getCount()==1,
                        "craft_outcome_actual_native_remainder_failure_is_not_confirmed_"+click);
                menu.setCarried(ItemStack.EMPTY); actor.getInventory().clearContent(); menu.getSlot(1).set(new ItemStack(CraftFailureIngredient.item));
                menu.clicked(0,0,click,actor);
                check(craftOutcomes.getLast().getOutcome()==net.citizensnpcs.api.event.CraftingOutcomeEvent.Outcome.RETURNED
                        && menu.getSlot(1).getItem().isEmpty(),"craft_outcome_failed_scope_does_not_leak_into_next_take_"+click);
            }
            long returnedUnits=craftOutcomes.stream().filter(event->event.getOutcome()==net.citizensnpcs.api.event.CraftingOutcomeEvent.Outcome.RETURNED)
                    .mapToLong(event->event.getObservation().output().getCount()).sum();
            check(craftJournal.entries().size()==crafts.size() && craftJournal.confirmedTotal(craftGeneration.id(),craftJournalTask)==returnedUnits
                    && craftJournal.entries().stream().filter(entry->entry.state()==QuestEvidenceJournal.State.REVIEW).count()==2,
                    "craft_journal_admits_each_native_observation_and_excludes_interrupted_processing");
            var admitted=craftJournal.entries().stream().filter(entry->entry.admission().observationId().equals(first.id())).findFirst().orElseThrow();
            craftJournal.close(); craftJournal=QuestEvidenceJournal.open(journalPath);
            check(craftJournal.generation(craftGeneration.scope(),craftGeneration.nativeCycle()).id().equals(craftGeneration.id())
                    && craftJournal.admit(admitted.admission()).id().equals(admitted.id())
                    && craftJournal.confirmedTotal(craftGeneration.id(),craftJournalTask)==returnedUnits,
                    "craft_journal_reopen_reuses_generation_and_deduplicates_native_observation");
            var retained=com.google.gson.JsonParser.parseString(craftJournal.admit(admitted.admission()).admission().evidence()).getAsJsonObject();
            var decoded=ItemStack.CODEC.parse(actor.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE),retained.get("output")).getOrThrow();
            check(ItemStack.matches(decoded,first.output()) && retained.getAsJsonArray("inputs").size()==9
                    && retained.getAsJsonObject("recipe").get("revision").getAsString().equals(first.recipe().revision()),
                    "craft_journal_retains_full_native_components_grid_and_recipe_revision");
        } finally {
            recordingCrafts=false; failCraftConsumer=false; reenterCraftBridge=false; failOutcomeConsumer=false; CraftFailureIngredient.fail=false;
            if(craftJournal!=null) { craftJournal.close(); craftJournal=null; }
            menu.setCarried(ItemStack.EMPTY); menu.clearCraftingContent();
            actor.inventoryMenu.clearCraftingContent(); actor.containerMenu=previousMenu;
            actor.serverLevel().setBlockAndUpdate(station,stationBefore);
            manager.replaceRecipes(original);
        }
    }
    private static void auditDiscovery(AuditPlayer actor,AuditPlayer other) throws Exception {
        var file=ServerQuestFile.getInstance().orElseThrow(); var registries=actor.registryAccess();
        var old=file.getChapter(0x10301L); if(old!=null) old.deleteSelf();
        var chapter=new Chapter(0x10301L,file,file.getDefaultChapterGroup()); chapter.onCreated();
        var prerequisite=new Quest(0x10302L,chapter); prerequisite.setRawTitle("Visible prerequisite"); prerequisite.onCreated();
        var prerequisiteTask=new CustomTask(0x10303L,prerequisite); prerequisiteTask.onCreated();
        var hidden=new Quest(0x10304L,chapter);
        var hiddenConfig=new net.minecraft.nbt.CompoundTag(); hiddenConfig.putBoolean("invisible",true);
        hidden.readData(hiddenConfig,registries); hidden.setRawTitle("Hidden prerequisite"); hidden.onCreated();
        var hiddenTask=new CustomTask(0x10305L,hidden); hiddenTask.onCreated();
        var quest=new Quest(0x10306L,chapter);
        var config=new net.minecraft.nbt.CompoundTag(); config.putBoolean("hide_until_deps_visible",false);
        config.putBoolean("hide_until_deps_complete",false); config.putBoolean("hide_details_until_startable",false);
        config.putBoolean("require_sequential_tasks",true); config.putBoolean("can_repeat",true);
        quest.readData(config,registries); quest.setRawTitle("Discovery quest"); quest.addDependency(prerequisite); quest.addDependency(hidden); quest.onCreated();
        var delivery=new ItemTask(0x10307L,quest).setStackAndCount(new ItemStack(Items.STONE),10);
        delivery.setConsumeItems(Tristate.TRUE); delivery.onCreated();
        var possession=new ItemTask(0x10308L,quest).setStackAndCount(new ItemStack(Items.DIAMOND),5);
        possession.setConsumeItems(Tristate.FALSE); possession.onCreated();
        var craft=new ItemTask(0x10309L,quest) {
            @Override public boolean isOnlyFromCrafting() { return true; }
            @Override public net.minecraft.network.chat.MutableComponent getAltTitle() { return Component.translatable("item.minecraft.stone"); }
        };
        craft.setStackAndCount(new ItemStack(Items.STONE),12); craft.setConsumeItems(Tristate.FALSE); craft.onCreated();
        var custom=new CustomTask(Long.MAX_VALUE-2,quest); custom.onCreated();
        var personalReward=new dev.ftb.mods.ftbquests.quest.reward.CustomReward(0x10310L,quest);
        var personalConfig=new net.minecraft.nbt.CompoundTag(); personalConfig.putBoolean("team_reward",false);
        personalReward.readData(personalConfig,registries); personalReward.onCreated();
        var sharedReward=new dev.ftb.mods.ftbquests.quest.reward.CustomReward(0x10311L,quest);
        var sharedConfig=new net.minecraft.nbt.CompoundTag(); sharedConfig.putBoolean("team_reward",true);
        sharedReward.readData(sharedConfig,registries); sharedReward.onCreated();
        var team=file.getTeamData(actor).orElseThrow(); var otherTeam=file.getTeamData(other).orElseThrow();
        team.setProgress(delivery,4); otherTeam.setProgress(delivery,1);
        String before=team.serializeNBT().toString(); int teamCount=file.getAllTeamData().size();
        var view=FtbQuestTasks.describe(actor,delivery.getCodeString()).orElseThrow();
        check(view.actorId().equals(actor.getUUID()) && view.state().teamId().equals(team.getTeamId()) && view.state().progress()==4
                && view.questId().equals(quest.getCodeString()) && view.chapterId().equals(chapter.getCodeString()),
                "task_discovery_uses_current_party_and_authoritative_ids_progress");
        check(view.kind()==FtbQuestTasks.Kind.DELIVERY && FtbQuestTasks.describe(actor,possession.getCodeString()).orElseThrow().kind()==FtbQuestTasks.Kind.POSSESSION
                && FtbQuestTasks.describe(actor,craft.getCodeString()).orElseThrow().kind()==FtbQuestTasks.Kind.CRAFT
                && FtbQuestTasks.describe(actor,custom.getCodeString()).orElseThrow().kind()==FtbQuestTasks.Kind.CUSTOM,
                "task_discovery_distinguishes_native_craft_possession_delivery_and_custom");
        check(FtbQuestTasks.describe(actor,craft.getCodeString()).orElseThrow().titleJson().contains("\"translate\"")
                && FtbQuestTasks.describe(actor,craft.getCodeString()).orElseThrow().type().equals(craft.getType().getTypeId()),
                "task_discovery_retains_native_localizable_titles_and_type_ids");
        check(view.prerequisites().hiddenCount()==1 && view.prerequisites().visible().size()==1
                && view.prerequisites().visible().getFirst().id().equals(prerequisite.getCodeString())
                && !view.prerequisites().complete(),"task_discovery_masks_hidden_dependency_identity_and_title");
        check(FtbQuestTasks.describe(actor,hiddenTask.getCodeString()).isEmpty(),"task_discovery_never_describes_invisible_quest_tasks");
        var sequential=FtbQuestTasks.describe(actor,possession.getCodeString()).orElseThrow();
        check(sequential.order().sequential() && sequential.order().index()==1
                && sequential.order().previous().orElseThrow().id().equals(delivery.getCodeString())
                && !sequential.order().previous().orElseThrow().completed() && !sequential.state().available(),
                "task_discovery_exposes_native_sequential_dependency");
        check(view.cycle().repeatable() && view.cycle().completedCycles()==team.getCompletionCount(quest)
                && view.cycle().startedAt().orElseThrow()==team.getStartedTime(quest.id).orElseThrow().getTime(),
                "task_discovery_copies_native_cycle_observations_without_inventing_claim_generations");
        check(view.rewards().stream().anyMatch(reward->reward.rewardId().equals(personalReward.getCodeString())
                    && reward.scope()==FtbQuestTasks.ClaimScope.PLAYER && reward.ownerId().equals(actor.getUUID()))
                && view.rewards().stream().anyMatch(reward->reward.rewardId().equals(sharedReward.getCodeString())
                    && reward.scope()==FtbQuestTasks.ClaimScope.TEAM && reward.ownerId().equals(team.getTeamId())),
                "task_discovery_preserves_player_and_team_claim_ownership");
        var seen=new LinkedHashSet<String>(); String after=null; boolean paged=false;
        for(int pageNumber=0;pageNumber<=file.getAllObjects().size();pageNumber++) {
            var page=FtbQuestTasks.discover(actor,after,2);
            for(var task:page.tasks()) if(!seen.add(task.taskId())) throw new AssertionError("duplicate discovery cursor");
            if(page.nextAfterId()==null) { paged=true; break; }
            after=page.nextAfterId();
        }
        check(paged && seen.contains(custom.getCodeString()) && !seen.contains(hiddenTask.getCodeString()),
                "task_discovery_high_id_pagination_is_complete_and_filters_hidden_entries");
        var page=FtbQuestTasks.discover(actor,null,2);
        try { page.tasks().clear(); throw new AssertionError("mutable discovery page"); }
        catch(UnsupportedOperationException expected) { check(true,"task_discovery_pages_are_immutable"); }
        check(before.equals(team.serializeNBT().toString()) && file.getAllTeamData().size()==teamCount,
                "task_discovery_does_not_mutate_progress_claims_or_create_teams");
        check(FtbQuestTasks.describe(other,delivery.getCodeString()).orElseThrow().state().progress()==1,
                "task_discovery_separate_party_progress_stays_separate");
        var actions=new Actions(new ItemLibrary(),new Economy()); int messages=actor.messages.size();
        check(actions.validateAll(List.of("ftb_tasks:","ftb_task: "+delivery.getCodeString()),actor,Component.empty())
                && actor.messages.size()==messages,"task_discovery_dialogue_preflight_is_read_only");
        check(actions.runAll(List.of("ftb_task: "+delivery.getCodeString()),actor,Component.empty())
                && actor.messages.subList(messages,actor.messages.size()).stream().anyMatch(message->message.contains(delivery.getCodeString())),
                "task_discovery_dialogue_displays_live_visible_task");
        check(!actions.validateAll(List.of("ftb_task: "+hiddenTask.getCodeString()),actor,Component.empty()),
                "task_discovery_hidden_detail_action_rejects_in_preflight");
        check(actor.getServer().getCommands().getDispatcher().execute("citizens quest list",actor.createCommandSourceStack().withPermission(2))==1,
                "task_discovery_list_command_dispatches_without_requiring_a_task_id");
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
    private static void auditRecipes(AuditPlayer actor) throws Exception {
        var server=actor.getServer(); var manager=server.getRecipeManager();
        var original=List.copyOf(manager.getRecipes());
        var kubeScript=server.getServerDirectory().resolve("kubejs/server_scripts/citizens_recipe_audit.js");
        if(java.nio.file.Files.isRegularFile(kubeScript)) {
            var scripted=net.citizensnpcs.util.CraftingRecipes.find(server,net.minecraft.resources.ResourceLocation.parse("citizens_audit:kubejs_recipe")).orElseThrow();
            check(scripted.supported() && scripted.output().is(Items.PAPER) && scripted.output().getCount()==4,
                    "recipes_actual_kubejs_server_script_definition_is_discovered");
        }
        var id=net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("citizens_audit","component_recipe");
        var shapedId=net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("citizens_audit","shaped_recipe");
        var name=Component.literal("Component ingredient");
        var required=new ItemStack(Items.STONE); required.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,name);
        var ingredient=net.neoforged.neoforge.common.crafting.DataComponentIngredient.of(false,required);
        var output=new ItemStack(Items.PAPER,3); output.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,Component.literal("Recipe result"));
        var recipe=new net.minecraft.world.item.crafting.ShapelessRecipe("",net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                output,net.minecraft.core.NonNullList.of(net.minecraft.world.item.crafting.Ingredient.EMPTY,ingredient));
        var shaped=new net.minecraft.world.item.crafting.ShapedRecipe("",net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                net.minecraft.world.item.crafting.ShapedRecipePattern.of(Map.of('A',net.minecraft.world.item.crafting.Ingredient.of(Items.COBBLESTONE)),"AA"," A"),new ItemStack(Items.STONE,2));
        var augmented=new ArrayList<net.minecraft.world.item.crafting.RecipeHolder<?>>(original);
        augmented.add(new net.minecraft.world.item.crafting.RecipeHolder<>(id,recipe));
        augmented.add(new net.minecraft.world.item.crafting.RecipeHolder<>(shapedId,shaped));
        var dynamicId=net.minecraft.resources.ResourceLocation.parse("citizens_audit:dynamic_recipe");
        if(java.nio.file.Files.isRegularFile(kubeScript)) {
            var dynamic=(net.minecraft.world.item.crafting.Recipe<?>)Class.forName("dev.latvian.mods.kubejs.recipe.special.ShapelessKubeJSRecipe")
                    .getConstructor(net.minecraft.world.item.crafting.ShapelessRecipe.class,List.class,String.class)
                    .newInstance(recipe,List.of(),"citizens_audit:result_callback");
            augmented.add(new net.minecraft.world.item.crafting.RecipeHolder<>(dynamicId,dynamic));
        }
        manager.replaceRecipes(augmented);
        try {
            if(java.nio.file.Files.isRegularFile(kubeScript))
                check(!net.citizensnpcs.util.CraftingRecipes.find(server,dynamicId).orElseThrow().supported(),
                        "recipes_kubejs_dynamic_result_callback_is_explicitly_unsupported");
            var view=net.citizensnpcs.util.CraftingRecipes.find(server,id).orElseThrow();
            check(view.supported() && view.kind()==net.citizensnpcs.util.CraftingRecipes.Kind.SHAPELESS
                    && view.output().getCount()==3 && view.inputs().getFirst().custom(),"recipes_native_component_output_and_custom_ingredient_discovered");
            var candidate=view.inputs().getFirst().alternatives().getFirst(); candidate.remove(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
            var copy=view.output(); copy.setCount(1); copy.remove(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
            check(ItemStack.matches(view.output(),output) && view.inputs().getFirst().alternatives().getFirst()
                    .has(net.minecraft.core.component.DataComponents.CUSTOM_NAME),"recipes_outputs_and_candidates_are_defensive");
            check(net.citizensnpcs.util.CraftingRecipes.matchesInput(server,id,view.revision(),0,required)
                    && !net.citizensnpcs.util.CraftingRecipes.matchesInput(server,id,view.revision(),0,new ItemStack(Items.STONE)),
                    "recipes_custom_matching_uses_native_predicate_not_display_candidates");
            var grid=net.citizensnpcs.util.CraftingRecipes.find(server,shapedId).orElseThrow();
            check(grid.width()==2 && grid.height()==2 && grid.inputs().size()==4 && grid.inputs().get(2).alternatives().isEmpty()
                    && grid.workstation().equals(net.minecraft.resources.ResourceLocation.parse("minecraft:crafting_table")),
                    "recipes_shaped_grid_preserves_empty_slots_and_workstation");
            var sticks=net.citizensnpcs.util.CraftingRecipes.find(server,net.minecraft.resources.ResourceLocation.parse("minecraft:stick")).orElseThrow();
            check(sticks.inputs().stream().anyMatch(input->input.definition().contains("\"tag\"") && input.alternatives().size()>1),
                    "recipes_native_tag_identity_and_resolved_alternatives_are_preserved");
            var cooking=original.stream().filter(holder->holder.value().getType()==net.minecraft.world.item.crafting.RecipeType.SMELTING).findFirst().orElseThrow();
            check(!net.citizensnpcs.util.CraftingRecipes.find(server,cooking.id()).orElseThrow().supported(),
                    "recipes_unsupported_machine_type_is_not_fabricated_as_table_crafting");
            check(net.citizensnpcs.util.CraftingRecipes.byOutput(server,output,true,100).stream().anyMatch(found->found.id().equals(id))
                    && net.citizensnpcs.util.CraftingRecipes.byOutput(server,new ItemStack(Items.PAPER),true,100).stream().noneMatch(found->found.id().equals(id)),
                    "recipes_output_lookup_preserves_component_filter");
            check(net.citizensnpcs.util.CraftingRecipes.byType(server,view.type(),100).stream().anyMatch(found->found.id().equals(id)),
                    "recipes_type_lookup_uses_registered_type_and_current_manager");
            var actions=new Actions(new ItemLibrary(),new Economy());
            int messagesBeforeGuidance=actor.messages.size();
            check(actions.validateAll(List.of("recipe: "+id),actor,Component.empty()) && actor.messages.size()==messagesBeforeGuidance,
                    "recipes_dialogue_preflight_has_no_feedback_or_item_effect");
            check(actions.runAll(List.of("recipe: "+id),actor,Component.empty()) && actor.messages.size()>messagesBeforeGuidance
                    && actor.getInventory().countItem(Items.PAPER)==0,"recipes_dialogue_guidance_does_not_create_crafted_output");
            for(boolean packet:new boolean[]{false,true}) {
                var npc=net.citizensnpcs.api.CitizensAPI.getNPCRegistry().createNPC(net.minecraft.world.entity.EntityType.COW,"Recipe"+packet);
                try {
                    if(packet) npc.getOrAddTrait(net.citizensnpcs.trait.PacketNPC.class);
                    npc.spawn(new net.citizensnpcs.api.util.Location(actor.serverLevel(),2,-60,2));
                    var commands=npc.getOrAddTrait(net.citizensnpcs.trait.CommandTrait.class);
                    commands.addCommand(new net.citizensnpcs.trait.CommandTrait.NPCCommandBuilder("citizens recipe "+id,
                            net.citizensnpcs.trait.CommandTrait.Hand.RIGHT).player(true).op(true).cooldown(-1));
                    int messagesBefore=actor.messages.size();
                    commands.dispatch(actor,net.citizensnpcs.trait.CommandTrait.Hand.RIGHT);
                    net.citizensnpcs.Citizens.getInstance().getScheduler().tick();
                    check(actor.messages.size()>messagesBefore && actor.messages.getLast().contains(id.toString())
                            && actor.getInventory().countItem(Items.PAPER)==0,"recipes_npc_guidance_creates_no_output_"+packet);
                } finally { npc.destroy(); }
            }
            var replacement=new net.minecraft.world.item.crafting.ShapelessRecipe("",net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                    new ItemStack(Items.PAPER,4),net.minecraft.core.NonNullList.of(net.minecraft.world.item.crafting.Ingredient.EMPTY,ingredient));
            augmented.removeIf(holder->holder.id().equals(id)); augmented.add(new net.minecraft.world.item.crafting.RecipeHolder<>(id,replacement));
            manager.replaceRecipes(augmented);
            check(!net.citizensnpcs.util.CraftingRecipes.current(server,id,view.revision()) &&
                    net.citizensnpcs.util.CraftingRecipes.find(server,id).orElseThrow().output().getCount()==4,"recipes_live_replacement_invalidates_previous_revision");
            try { net.citizensnpcs.util.CraftingRecipes.matchesInput(server,id,view.revision(),0,required); throw new AssertionError("Stale recipe accepted"); }
            catch(IllegalStateException expected) { check(true,"recipes_stale_revision_cannot_validate_ingredients"); }
            check(java.util.concurrent.CompletableFuture.supplyAsync(()->{
                try { net.citizensnpcs.util.CraftingRecipes.find(server,id); return false; }
                catch(IllegalStateException expected) { return true; }
            }).get(),"recipes_worker_thread_access_is_rejected");
        } finally { manager.replaceRecipes(original); }
        check(net.citizensnpcs.util.CraftingRecipes.find(server,id).isEmpty(),"recipes_removed_definition_stays_unavailable");
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
        player.channel=connect(player); return player;
    }
    private static ServerPlayer realPlayer(ServerLevel level,String name) {
        var player=new ServerPlayer(level.getServer(),level,new GameProfile(UUID.randomUUID(),name),ClientInformation.createDefault());
        player.setPos(1,-60,1); realPlayers.put(player,connect(player)); return player;
    }
    private static EmbeddedChannel connect(ServerPlayer player) {
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                Connection.configureInMemoryPipeline(channel.pipeline(),PacketFlow.SERVERBOUND);
                connection.configurePacketHandler(channel.pipeline());
            }
        });
        NetworkRegistry.configureMockConnection(connection);
        var cookie = new CommonListenerCookie(player.getGameProfile(),0,ClientInformation.createDefault(),false,ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(player.registryAccess(),cookie.connectionType())));
        player.getServer().getPlayerList().placeNewPlayer(connection,player,cookie);
        return channel;
    }
    private static final class AuditPlayer extends ServerPlayer {
        EmbeddedChannel channel;
        final List<String> messages=new ArrayList<>();
        AuditPlayer(ServerLevel level,String name) { super(level.getServer(),level,new GameProfile(UUID.randomUUID(),name),ClientInformation.createDefault()); }
        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
    }
    private static void check(boolean condition,String name) {
        if(!condition) throw new AssertionError(name);
        passed++; LoggerFactory.getLogger("interactions").info("[QUESTAUDIT] PASS {}",name);
    }
}
