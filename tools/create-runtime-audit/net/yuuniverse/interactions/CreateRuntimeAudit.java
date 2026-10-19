package net.yuuniverse.interactions;

import java.util.*;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.citizensnpcs.util.CraftingRecipes;
import net.citizensnpcs.util.CreateProcessingRecipes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid="interactions")
public final class CreateRuntimeAudit {
    private static int passed;
    private static boolean ran;
    private static String oldRevision;
    private static java.util.concurrent.CompletableFuture<Void> reload;
    private static final List<net.citizensnpcs.util.CreateMachineEvidence.Observation> observations=new ArrayList<>();
    private static final List<net.citizensnpcs.api.event.MachineOutcomeEvent> outcomes=new ArrayList<>();
    private static boolean recordMachines;
    @SubscribeEvent public static void machine(net.citizensnpcs.api.event.MachineResultEvent event) {
        if(recordMachines) observations.add(event.getObservation());
    }
    @SubscribeEvent public static void outcome(net.citizensnpcs.api.event.MachineOutcomeEvent event) {
        if(recordMachines) outcomes.add(event);
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (ran) return;
        var server=event.getServer(); var level=server.overworld();
        level.setChunkForced(0,0,true);
        if(server.getTickCount()<20 || !level.isPositionEntityTicking(new BlockPos(1,-60,1))) return;
        var crashPhase = System.getenv("CITIZENS_MACHINE_AUDIT_PHASE");
        if (crashPhase != null) {
            ran = true;
            MachineCrashRuntimeAudit.run(server, crashPhase);
            return;
        }
        try {
            var id=ResourceLocation.parse("citizens_audit:create_milling");
            if(reload==null) {
                var recipe=CraftingRecipes.find(server,id).orElseThrow();
                check(recipe.processing()!=null && recipe.processing().itemResults().getFirst().stack().getCount()==3,
                        "kubejs_native_create_recipe_loaded");
                oldRevision=recipe.revision();
                var script=server.getServerDirectory().resolve("kubejs/server_scripts/citizens_create_audit.js");
                var original=java.nio.file.Files.readString(script); var updated=original.replace("count: 3","count: 4");
                if(updated.equals(original)) throw new AssertionError("Unexpected Create fixture script");
                java.nio.file.Files.writeString(script,updated);
                reload=server.reloadResources(server.getPackRepository().getSelectedIds()); return;
            }
            if(!reload.isDone()) return;
            reload.join(); ran=true;
            check(!CraftingRecipes.current(server,id,oldRevision) && CraftingRecipes.find(server,id).orElseThrow().output().getCount()==4,
                    "actual_resource_reload_invalidates_create_revision");
            recipes(server);
            machines(server);
            BatchMachineRuntimeAudit.run(server);
            PressMachineRuntimeAudit.run(server);
            TransportPressRuntimeAudit.run(server);
            BasinMachineRuntimeAudit.run(server);
            FluidMachineRuntimeAudit.run(server);
            DeployerRuntimeAudit.run(server);
            FanRuntimeAudit.run(server);
            LoggerFactory.getLogger("interactions").info("[CREATEAUDIT] COMPLETE {} checks",passed);
        } catch(Throwable failure) {
            ran=true; LoggerFactory.getLogger("interactions").error("[CREATEAUDIT] FAILED",failure);
        } finally { if(ran) server.halt(false); }
    }
    private static void machines(MinecraftServer server) throws Exception {
        var level=server.overworld();
        var file=dev.ftb.mods.ftbquests.quest.ServerQuestFile.getInstance().orElseThrow();
        var old=file.getChapter(0x10801L); if(old!=null) old.deleteSelf();
        var chapter=new dev.ftb.mods.ftbquests.quest.Chapter(0x10801L,file,file.getDefaultChapterGroup()); chapter.onCreated();
        var quest=new dev.ftb.mods.ftbquests.quest.Quest(0x10802L,chapter);
        var task=new dev.ftb.mods.ftbquests.quest.task.CustomTask(0x10803L,quest); task.setMaxProgress(1000);
        var stoneTask=new dev.ftb.mods.ftbquests.quest.task.CustomTask(0x10804L,quest); stoneTask.setMaxProgress(1000);
        var blocker=new dev.ftb.mods.ftbquests.quest.task.CustomTask(0x10805L,quest); blocker.setMaxProgress(1);
        quest.onCreated(); task.onCreated(); stoneTask.onCreated(); blocker.onCreated();
        var repeatQuest=new dev.ftb.mods.ftbquests.quest.Quest(0x10806L,chapter);
        var repeatConfig=new net.minecraft.nbt.CompoundTag(); repeatConfig.putBoolean("can_repeat",true);
        repeatQuest.readData(repeatConfig,server.registryAccess()); repeatQuest.onCreated();
        var repeatTask=new dev.ftb.mods.ftbquests.quest.task.CustomTask(0x10807L,repeatQuest); repeatTask.setMaxProgress(1); repeatTask.onCreated();
        var reward=new dev.ftb.mods.ftbquests.quest.reward.CustomReward(0x10808L,repeatQuest); reward.onCreated();
        var actor=new net.minecraft.server.level.ServerPlayer(server,level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"MachineActor"),
                net.minecraft.server.level.ClientInformation.createDefault()); actor.setPos(1,-60,1);
        var channel=connect(actor); var source=actor.createCommandSourceStack();
        var grant=net.citizensnpcs.api.util.PermissionUtil.grantTemporary(actor,List.of("citizens.admin"));
        var team=file.getTeamData(actor).orElseThrow();
        var directory=java.nio.file.Files.createTempDirectory(server.getServerDirectory(),"machine-evidence-");
        var service=net.citizensnpcs.util.CraftQuestEvidence.open(server,directory);
        var position=new BlockPos(4,-58,4); var motorPos=position.below();
        level.setBlockAndUpdate(motorPos,com.simibubi.create.AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING,net.minecraft.core.Direction.UP));
        level.setBlockAndUpdate(position,com.simibubi.create.AllBlocks.MILLSTONE.getDefaultState());
        var motor=(com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity)level.getBlockEntity(motorPos);
        var mill=(com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity)level.getBlockEntity(position);
        motor.initialize(); mill.initialize(); motor.generatedSpeed.setValue(256); motor.updateGeneratedRotation();
        recordMachines=true;
        try {
            service.bind(source,task.getCodeString(),ResourceLocation.parse("create:milling/wheat"));
            service.bind(source,stoneTask.getCodeString(),ResourceLocation.parse("citizens_audit:create_milling"));
            service.bind(source,repeatTask.getCodeString(),ResourceLocation.parse("create:milling/wheat"));
            process(motor,mill,new ItemStack(Items.WHEAT));
            check(observations.isEmpty() && team.getProgress(task)==0,"unassigned_native_automation_has_no_player_credit");
            clearOutput(mill);
            grant.remove();
            try { net.citizensnpcs.util.CreateMachineEvidence.assign(source,actor.getUUID(),position); throw new AssertionError("Unprivileged assignment accepted"); }
            catch(IllegalStateException expected) { check(true,"unprivileged_machine_assignment_rejected"); }
            grant=net.citizensnpcs.api.util.PermissionUtil.grantTemporary(actor,List.of("citizens.admin"));
            check(server.getCommands().getDispatcher().execute("citizens craftevidence assignmachine "+actor.getUUID()+" 4 -58 4",source)==1,
                    "authenticated_native_machine_assignment_command");
            var assignment=net.citizensnpcs.util.CreateMachineEvidence.attribution(mill);
            check(assignment.operatorId().equals(actor.getUUID()) && assignment.beneficiaryId().equals(actor.getUUID()),"machine_assignment_retains_operator_and_beneficiary");
            process(motor,mill,new ItemStack(Items.WHEAT));
            var observation=observations.getFirst(); var outcome=outcomes.getFirst();
            int flour=observation.rolledOutputs().stream().filter(stack->ItemStack.isSameItemSameComponents(stack,observation.recipe().output())).mapToInt(ItemStack::getCount).sum();
            check(mill.getSpeed()!=0 && outcome.getOutcome()==net.citizensnpcs.api.event.MachineOutcomeEvent.Outcome.RETURNED
                    && team.getProgress(task)==flour && flour>=1,"real_native_powered_wheat_milling_projects_actual_rolled_output");
            check(observation.consumedInput().getCount()==1 && observation.consumedInput().is(Items.WHEAT)
                    && observation.position().equals(position) && observation.attribution().equals(assignment),"machine_evidence_retains_exact_input_location_attribution");
            check(service.entries(source).getFirst().state()==net.citizensnpcs.util.QuestEvidenceJournal.State.APPLIED
                    && service.entries(source).getFirst().admission().evidence().contains("rolledOutputs"),"durable_machine_evidence_applied_after_native_output_verification");
            observation.rolledOutputs().getFirst().setCount(99); observation.consumedInput().setCount(99);
            check(observation.consumedInput().getCount()==1 && observation.rolledOutputs().getFirst().getCount()!=99,"machine_observation_is_defensive");
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(outcome);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.citizensnpcs.api.event.MachineResultEvent(actor,observation));
            check(team.getProgress(task)==flour && service.entries(source).size()==2,"duplicate_machine_notifications_do_not_increment_progress");
            team.claimReward(actor,reward,false);
            check(team.getCompletionCount(repeatQuest)==1 && team.getProgress(repeatTask)==0,"native_machine_reward_claim_advances_repeat_generation");
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.citizensnpcs.api.event.MachineResultEvent(actor,observation));
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(outcome);
            check(team.getProgress(repeatTask)==0 && service.entries(source).size()==2,"old_machine_notification_cannot_enter_new_repeat_generation");
            clearOutput(mill);
            check(team.getProgress(task)==flour,"output_extraction_does_not_create_processing_evidence");
            var saved=mill.saveWithFullMetadata(server.registryAccess());
            var restored=(com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity)net.minecraft.world.level.block.entity.BlockEntity
                    .loadStatic(position,mill.getBlockState(),saved,server.registryAccess()); restored.setLevel(level);
            check(net.citizensnpcs.util.CreateMachineEvidence.attribution(restored).equals(assignment),"native_blockentity_save_load_preserves_machine_assignment");
            service.close(); service=net.citizensnpcs.util.CraftQuestEvidence.open(server,directory);
            check(service.reconcile(source)==0 && team.getProgress(task)==flour && service.entries(source).size()==2,"journal_reopen_preserves_machine_absolute_progress");
            for(int i=0;i<mill.outputInv.getSlots();i++) mill.outputInv.setStackInSlot(i,new ItemStack(Items.COBBLESTONE,mill.outputInv.getSlotLimit(i)));
            int blockedCount=observations.size(); mill.inputInv.setStackInSlot(0,new ItemStack(Items.WHEAT));
            for(int i=0;i<200;i++) { motor.tick(); mill.tick(); }
            check(observations.size()==blockedCount && mill.inputInv.getStackInSlot(0).getCount()==1 && team.getProgress(task)==flour,
                    "full_native_output_inventory_blocks_processing_without_evidence");
            clearOutput(mill); mill.inputInv.setStackInSlot(0,ItemStack.EMPTY);
            for(int i=0;i<mill.outputInv.getSlots();i++) mill.outputInv.setStackInSlot(i,new ItemStack(Items.COBBLESTONE,mill.outputInv.getSlotLimit(i)-1));
            process(motor,mill,new ItemStack(Items.STONE));
            check(team.getProgress(stoneTask)==0 && outcomes.getLast().getOutcome()==net.citizensnpcs.api.event.MachineOutcomeEvent.Outcome.UNCONFIRMED
                    && service.entries(source).getLast().state()==net.citizensnpcs.util.QuestEvidenceJournal.State.REVIEW,
                    "partial_capacity_native_output_loss_requires_review_not_task_credit");
            var review=service.entries(source).getLast();
            service.resolve(source,review.id(),false,"Native output did not fit the recorded inventory");
            clearOutput(mill);
            mill.outputInv=new net.neoforged.neoforge.items.ItemStackHandler(9) {
                @Override public ItemStack insertItem(int slot,ItemStack stack,boolean simulate) { throw new IllegalStateException("Expected native insertion failure"); }
            };
            try { process(motor,mill,new ItemStack(Items.STONE)); throw new AssertionError("Native insertion failure hidden"); }
            catch(IllegalStateException expected) { check(outcomes.getLast().getOutcome()==net.citizensnpcs.api.event.MachineOutcomeEvent.Outcome.INTERRUPTED
                    && team.getProgress(stoneTask)==0,"native_insertion_exception_preserved_and_evidence_reviewed"); }
            mill.outputInv=new net.neoforged.neoforge.items.ItemStackHandler(9);
            net.citizensnpcs.util.CreateMachineEvidence.clear(source,position);
            check(net.citizensnpcs.util.CreateMachineEvidence.attribution(mill)==null,"explicit_machine_assignment_clear");
            net.citizensnpcs.util.CreateMachineEvidence.assign(source,actor.getUUID(),position);
            var original=List.copyOf(server.getRecipeManager().getRecipes());
            try {
                var changed=new ArrayList<RecipeHolder<?>>(original);
                var selected=server.getRecipeManager().byKey(ResourceLocation.parse("citizens_audit:create_milling")).orElseThrow();
                var fresh=Recipe.CODEC.parse(server.registryAccess().createSerializationContext(JsonOps.INSTANCE),
                        Recipe.CODEC.encodeStart(server.registryAccess().createSerializationContext(JsonOps.INSTANCE),selected.value()).getOrThrow()).getOrThrow();
                changed.removeIf(holder->holder.id().equals(selected.id())); changed.add(new RecipeHolder<>(selected.id(),fresh));
                server.getRecipeManager().replaceRecipes(changed);
                int before=observations.size(); process(motor,mill,new ItemStack(Items.STONE));
                check(observations.size()==before,"cached_retired_create_recipe_cannot_masquerade_as_current_holder");
            } finally { server.getRecipeManager().replaceRecipes(original); }
            var remainderOriginal=List.copyOf(server.getRecipeManager().getRecipes());
            var remainderId=ResourceLocation.parse("citizens_audit:machine_remainder");
            var remainderRecipe=new com.simibubi.create.content.processing.recipe.StandardProcessingRecipe.Builder<>(
                    com.simibubi.create.content.kinetics.millstone.MillingRecipe::new,remainderId)
                    .withItemIngredients(net.minecraft.core.NonNullList.of(Ingredient.EMPTY,Ingredient.of(MachineRemainderItem.item)))
                    .withSingleItemOutput(new ItemStack(Items.PAPER,2)).duration(16).build();
            var remainderRecipes=new ArrayList<RecipeHolder<?>>(remainderOriginal); remainderRecipes.add(new RecipeHolder<>(remainderId,remainderRecipe));
            server.getRecipeManager().replaceRecipes(remainderRecipes);
            try {
                clearOutput(mill); MachineRemainderItem.calls=0;
                process(motor,mill,new ItemStack(MachineRemainderItem.item));
                var last=observations.getLast();
                check(MachineRemainderItem.calls==1 && last.nativeRemainder().is(Items.BUCKET)
                        && outcomes.getLast().getOutcome()==net.citizensnpcs.api.event.MachineOutcomeEvent.Outcome.RETURNED,
                        "actual_native_remainder_callback_called_once_and_saved_separately");
                clearOutput(mill); MachineRemainderItem.fail=true; int before=observations.size();
                try { process(motor,mill,new ItemStack(MachineRemainderItem.item)); throw new AssertionError("Native remainder failure hidden"); }
                catch(IllegalStateException expected) { check(observations.size()==before && mill.inputInv.getStackInSlot(0).getCount()==1,
                        "native_remainder_failure_preserves_input_and_emits_no_completed_evidence"); }
            } finally { MachineRemainderItem.fail=false; server.getRecipeManager().replaceRecipes(remainderOriginal); mill.inputInv.setStackInSlot(0,ItemStack.EMPTY); }
            var party=dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().getManager().createPartyTeam(actor,"MachineAuditParty","fixture",
                    dev.ftb.mods.ftblibrary.icon.Color4I.WHITE);
            var partyData=file.getTeamData(actor).orElseThrow(); long partyBefore=partyData.getProgress(task);
            check(partyData.getTeamId().equals(party.getTeamId()) && !partyData.getTeamId().equals(team.getTeamId()),"machine_beneficiary_native_party_change");
            int entriesBefore=service.entries(source).size();
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.citizensnpcs.api.event.MachineResultEvent(actor,observation));
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(outcome);
            check(service.entries(source).size()==entriesBefore && partyData.getProgress(task)==partyBefore,
                    "old_machine_notification_cannot_enter_beneficiary_new_team");
            int onlineCount=observations.size();
            server.getPlayerList().remove(actor);
            process(motor,mill,new ItemStack(Items.WHEAT));
            check(observations.size()==onlineCount && team.getProgress(task)==flour,"offline_assigned_beneficiary_receives_no_automation_credit");
        } finally {
            recordMachines=false; service.close(); grant.remove();
            level.removeBlock(position,false); level.removeBlock(motorPos,false);
            server.getPlayerList().remove(actor); channel.finishAndReleaseAll();
        }
    }
    private static void process(com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity motor,
            com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity mill,ItemStack input) {
        mill.inputInv.setStackInSlot(0,input.copy());
        for(int i=0;i<400 && !mill.inputInv.getStackInSlot(0).isEmpty();i++) { motor.tick(); mill.tick(); }
        if(!mill.inputInv.getStackInSlot(0).isEmpty()) throw new AssertionError("Native millstone did not process: speed="+mill.getSpeed()+", timer="+mill.timer);
    }
    private static void clearOutput(com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity mill) {
        for(int i=0;i<mill.outputInv.getSlots();i++) mill.outputInv.extractItem(i,Integer.MAX_VALUE,false);
    }
    static io.netty.channel.embedded.EmbeddedChannel connect(net.minecraft.server.level.ServerPlayer player) {
        var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        var channel=new io.netty.channel.embedded.EmbeddedChannel(new io.netty.channel.ChannelInitializer<io.netty.channel.Channel>() {
            @Override protected void initChannel(io.netty.channel.Channel channel) {
                net.minecraft.network.Connection.configureInMemoryPipeline(channel.pipeline(),net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
                connection.configurePacketHandler(channel.pipeline());
            }
        });
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        var cookie=new net.minecraft.server.network.CommonListenerCookie(player.getGameProfile(),0,net.minecraft.server.level.ClientInformation.createDefault(),false,
                net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(net.minecraft.network.protocol.game.GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                net.minecraft.network.RegistryFriendlyByteBuf.decorator(player.registryAccess(),cookie.connectionType())));
        player.getServer().getPlayerList().placeNewPlayer(connection,player,cookie); return channel;
    }
    private static void recipes(MinecraftServer server) throws Exception {
        var wheat=CraftingRecipes.find(server,ResourceLocation.parse("create:milling/wheat")).orElseThrow();
        check(wheat.supported() && !wheat.tableEvidenceSupported() && wheat.processing().process()==CreateProcessingRecipes.Process.MILLING
                && wheat.processing().itemResults().size()==3 && wheat.processing().recipeDuration()==150,
                "real_wheat_recipe_has_duration_all_outputs_and_machine_capability");
        check(wheat.processing().itemResults().get(1).chancePerItem()==0.25f
                && wheat.processing().itemResults().get(1).stack().getCount()==2,"probability_is_per_item_not_guaranteed_batch");
        var brass=CraftingRecipes.find(server,ResourceLocation.parse("create:mixing/brass_ingot")).orElseThrow();
        check(brass.processing().heat().equals("heated") && brass.inputs().size()==2 && brass.output().getCount()==2
                && brass.workstation().equals(ResourceLocation.parse("create:mechanical_mixer")),"real_brass_recipe_preserves_heat_tags_workstation");
        check(CraftingRecipes.byOutput(server,new ItemStack(Items.WHEAT_SEEDS),true,100).stream().anyMatch(value->value.id().equals(wheat.id())),
                "output_search_finds_secondary_probabilistic_products");
        check(CraftingRecipes.matchesInput(server,wheat.id(),wheat.revision(),0,new ItemStack(Items.WHEAT))
                && !CraftingRecipes.matchesInput(server,wheat.id(),wheat.revision(),0,new ItemStack(Items.STONE)),"native_item_predicate_controls_matching");
        var copy=wheat.processing().itemResults().getFirst().stack(); copy.setCount(37);
        check(wheat.processing().itemResults().getFirst().stack().getCount()==1,"item_outputs_are_defensive");
        for(var process:CreateProcessingRecipes.Process.values()) {
            var views=CraftingRecipes.byType(server,ResourceLocation.fromNamespaceAndPath("create",process.name().toLowerCase(Locale.ROOT)),100);
            check(!views.isEmpty() && views.stream().allMatch(value->value.processing()!=null && value.processing().process()==process),
                    "native_type_"+process.name().toLowerCase(Locale.ROOT));
        }
        var sequence=CraftingRecipes.byType(server,ResourceLocation.parse("create:sequenced_assembly"),1).getFirst();
        check(!sequence.supported() && sequence.processing()==null,"assembly_is_explicitly_unsupported");
        var mechanical=CraftingRecipes.byType(server,ResourceLocation.parse("create:mechanical_crafting"),1).getFirst();
        check(!mechanical.supported(),"mechanical_grid_is_not_a_vanilla_table");
        var manager=server.getRecipeManager(); var original=List.copyOf(manager.getRecipes());
        var id=ResourceLocation.parse("citizens_audit:fluid_mix");
        var ops=server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        Recipe<?> recipe=Recipe.CODEC.parse(ops,JsonParser.parseString("""
                {"type":"create:mixing","ingredients":[{"item":"minecraft:stone"},{"type":"neoforge:single","fluid":"minecraft:water","amount":250}],
                 "results":[{"id":"minecraft:paper","count":2,"chance":0.5,"components":{"minecraft:custom_name":"\\\"Mixed\\\""}},
                 {"id":"minecraft:lava","amount":100}],"heat_requirement":"heated"}
                """)).getOrThrow();
        var recipes=new ArrayList<RecipeHolder<?>>(original); recipes.add(new RecipeHolder<>(id,recipe)); manager.replaceRecipes(recipes);
        try {
            var view=CraftingRecipes.find(server,id).orElseThrow(); var processing=view.processing();
            check(processing.fluidInputs().getFirst().amount()==250 && processing.fluidResults().getFirst().getAmount()==100,
                    "native_sized_fluid_inputs_and_outputs");
            check(processing.itemResults().getFirst().stack().getHoverName().getString().equals("Mixed"),"full_output_components_preserved");
            check(CreateProcessingRecipes.matchesFluidInput(server,id,view.revision(),0,new FluidStack(Fluids.WATER,250))
                    && !CreateProcessingRecipes.matchesFluidInput(server,id,view.revision(),0,new FluidStack(Fluids.WATER,249))
                    && !CreateProcessingRecipes.matchesFluidInput(server,id,view.revision(),0,new FluidStack(Fluids.LAVA,250)),"native_sized_fluid_predicate");
            processing.fluidInputs().getFirst().alternatives().getFirst().setAmount(1); processing.fluidResults().getFirst().setAmount(1);
            check(processing.fluidInputs().getFirst().alternatives().getFirst().getAmount()==250
                    && processing.fluidResults().getFirst().getAmount()==100,"fluid_snapshots_are_defensive");
            var named=new ItemStack(Items.PAPER); named.set(DataComponents.CUSTOM_NAME,Component.literal("Mixed"));
            check(CraftingRecipes.byOutput(server,named,true,100).stream().anyMatch(value->value.id().equals(id))
                    && CraftingRecipes.byOutput(server,new ItemStack(Items.PAPER),true,100).stream().noneMatch(value->value.id().equals(id)),
                    "machine_output_search_obeys_complete_components");
            var nativeRecipe=(com.simibubi.create.content.kinetics.mixer.MixingRecipe)recipe;
            nativeRecipe.getFluidIngredients().set(0,net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient.of(Fluids.WATER,500));
            check(!CraftingRecipes.current(server,id,view.revision()),"fluid_redefinition_invalidates_revision");
            try { CreateProcessingRecipes.matchesFluidInput(server,id,view.revision(),0,new FluidStack(Fluids.WATER,1000));
                throw new AssertionError("Stale fluid query accepted"); }
            catch(IllegalStateException expected) { check(true,"stale_native_fluid_match_rejected"); }
            try { java.util.concurrent.CompletableFuture.runAsync(()->CraftingRecipes.find(server,id)).join();
                throw new AssertionError("Worker recipe access accepted"); }
            catch(java.util.concurrent.CompletionException expected) { check(expected.getCause() instanceof IllegalStateException,"worker_recipe_access_rejected"); }
            check(server.getCommands().getDispatcher().execute("citizens recipe "+id,server.createCommandSourceStack())==1,"native_machine_recipe_command");
        } finally { manager.replaceRecipes(original); }
    }
    private static void check(boolean condition,String name) {
        if(!condition) throw new AssertionError(name);
        passed++; LoggerFactory.getLogger("interactions").info("[CREATEAUDIT] PASS {}",name);
    }
}
