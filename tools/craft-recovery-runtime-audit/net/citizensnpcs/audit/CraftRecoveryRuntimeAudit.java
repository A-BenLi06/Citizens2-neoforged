package net.citizensnpcs.audit;

import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.task.CustomTask;
import net.citizensnpcs.api.event.CraftingResultEvent;
import net.citizensnpcs.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Native result takes followed by offline recovery across independent target failures. */
@EventBusSubscriber(modid="citizens")
public final class CraftRecoveryRuntimeAudit {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static boolean ran;
    private static int checks;
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (ran) return;
        var server = event.getServer(); var level = server.overworld(); level.setChunkForced(0, 0, true);
        if (server.getTickCount() < 20 || !level.isPositionEntityTicking(new BlockPos(1, -60, 1))) return;
        ran = true;
        try {
            switch (System.getenv("CITIZENS_CRAFT_RECOVERY_PHASE")) {
                case "seed" -> seed(server);
                case "recover" -> recover(server);
                case "bindings" -> checks = CraftBindingRuntimeAudit.run(server);
                default -> throw new IllegalArgumentException("Unknown craft recovery phase");
            }
            log("COMPLETE " + checks + " phase=" + System.getenv("CITIZENS_CRAFT_RECOVERY_PHASE"));
        } catch (Throwable failure) { org.slf4j.LoggerFactory.getLogger("citizens").error("[CRAFTRECOVERY] FAILED", failure); }
        finally { server.halt(false); }
    }
    private static void seed(MinecraftServer server) throws Exception {
        var actor = new ServerPlayer(server, server.overworld(), new com.mojang.authlib.GameProfile(UUID.randomUUID(), "CraftRecovery"), ClientInformation.createDefault());
        actor.setPos(1, -60, 1); var channel = connect(actor);
        try {
            var source = actor.createCommandSourceStack().withPermission(2);
            var service = CraftQuestEvidence.current(server); var file = ServerQuestFile.getInstance().orElseThrow();
            var team = file.getTeamData(actor).orElseThrow();
            var chapter = new Chapter(file.newID(), file, file.getDefaultChapterGroup()); chapter.onCreated();
            var targets = new LinkedHashMap<ResourceLocation, CustomTask>();
            for (String wood : List.of("oak", "birch", "spruce")) {
                var quest = new Quest(file.newID(), chapter); quest.onCreated();
                var task = new CustomTask(file.newID(), quest); task.onCreated();
                targets.put(ResourceLocation.parse("minecraft:" + wood + "_planks"), task);
            }
            file.clearCachedData(); file.clearCachedProgress();
            for (var target : targets.entrySet()) service.bind(source, target.getValue().getCodeString(), target.getKey());
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, CraftingResultEvent.class, event -> {
                if (event.getPlayer() == actor) {
                    var target = targets.get(event.getObservation().recipe().id());
                    if (target != null) target.setMaxProgress(2); // Real callback mutation after durable admission.
                }
            });
            var station = new BlockPos(2, -60, 2); server.overworld().setBlockAndUpdate(station, Blocks.CRAFTING_TABLE.defaultBlockState());
            var menu = new CraftingMenu(91, actor.getInventory(), ContainerLevelAccess.create(server.overworld(), station)); actor.containerMenu = menu;
            var inputs = List.of(Items.OAK_LOG, Items.BIRCH_LOG, Items.SPRUCE_LOG); int index = 0;
            for (var target : targets.entrySet()) {
                var holder = (RecipeHolder<CraftingRecipe>)(RecipeHolder<?>)server.getRecipeManager().byKey(target.getKey()).orElseThrow();
                menu.beginPlacingRecipe(); menu.clearCraftingContent(); menu.getSlot(1).set(new ItemStack(inputs.get(index++))); menu.finishPlacingRecipe(holder);
                menu.clicked(0, 0, ClickType.PICKUP, actor);
                check(menu.getCarried().getCount() == 4 && menu.getSlot(1).getItem().isEmpty(), "native_result_take_consumes_one_log_and_produces_four_planks");
                actor.getInventory().add(menu.getCarried().copy()); menu.setCarried(ItemStack.EMPTY);
                var entries = service.entries(source).stream().filter(e -> e.admission().taskId().equals(target.getValue().getCodeString())).toList();
                check(entries.size() == 1 && entries.getFirst().state() == QuestEvidenceJournal.State.CONFIRMED
                        && entries.getFirst().outcome() == QuestEvidenceJournal.Outcome.RETURNED && entries.getFirst().admission().units() == 4
                        && team.getProgress(target.getValue()) == 0, "actual_callback_mutation_retains_original_confirmed_craft_without_projecting");
            }
            actor.closeContainer();
            var state = new JsonObject(); state.addProperty("actor", actor.getUUID().toString()); state.addProperty("team", team.getTeamId().toString());
            state.add("tasks", JSON.toJsonTree(targets.values().stream().map(task -> task.getCodeString()).toList()));
            state.add("entries", JSON.toJsonTree(service.entries(source)));
            state.addProperty("inventory", inventory(actor));
            Files.writeString(server.getServerDirectory().resolve("craft-recovery-state.json"), JSON.toJson(state));
            ((dev.ftb.mods.ftbteams.data.TeamManagerImpl)dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().getManager()).saveNow();
            team.saveIfChanged(); file.saveNow(); server.saveEverything(true, true, true);
            check(service.entries(source).size() == 3, "three_original_independent_craft_outcomes_are_durable_before_restart");
        } finally { server.getPlayerList().remove(actor); channel.finishAndReleaseAll(); }
    }
    private static void recover(MinecraftServer server) throws Exception {
        var state = JsonParser.parseString(Files.readString(server.getServerDirectory().resolve("craft-recovery-state.json"))).getAsJsonObject();
        var service = CraftQuestEvidence.current(server); var console = server.createCommandSourceStack();
        var file = ServerQuestFile.getInstance().orElseThrow(); var teamId = UUID.fromString(state.get("team").getAsString());
        var team = file.getNullableTeamData(teamId);
        var tasks = new ArrayList<CustomTask>();
        for (var code : state.getAsJsonArray("tasks")) tasks.add((CustomTask)file.getTask(Long.parseUnsignedLong(code.getAsString(), 16)));
        check(server.getPlayerList().getPlayers().isEmpty() && team != null, "recovery_uses_original_persisted_team_with_no_contributor_online");
        var original = service.entries(console);
        check(JSON.toJsonTree(original).equals(state.get("entries")) && original.stream().allMatch(e -> e.state() == QuestEvidenceJournal.State.CONFIRMED), "restart_preserves_original_admissions_and_confirmed_outcomes");
        tasks.get(0).setMaxProgress(2); tasks.get(1).setMaxProgress(2);
        IllegalStateException failure = null;
        try { service.reconcile(console); } catch (IllegalStateException expected) { failure = expected; }
        var observation = new JsonObject(); observation.addProperty("reported", failure != null);
        observation.add("progress", JSON.toJsonTree(tasks.stream().map(team::getProgress).toList()));
        observation.add("entries", JSON.toJsonTree(service.entries(console)));
        observation.addProperty("message", failure == null ? null : failure.getMessage());
        Files.writeString(server.getServerDirectory().resolve("craft-recovery-observation.json"), JSON.toJson(observation));
        check(failure != null && team.getProgress(tasks.get(2)) == 1 && applied(service, console, tasks.get(2)), "invalid_earlier_targets_do_not_starve_valid_original_craft_recovery");
        check(team.getProgress(tasks.get(0)) == 0 && team.getProgress(tasks.get(1)) == 0 && failure.getSuppressed().length == 1,
                "both_invalid_scopes_remain_pending_and_are_reported_after_valid_recovery");
        check(failure.getMessage().contains(tasks.get(0).getCodeString()) && failure.getMessage().contains(teamId.toString())
                        && failure.getSuppressed()[0].getMessage().contains(tasks.get(1).getCodeString()), "remaining_failures_identify_original_task_team_and_cause");
        tasks.get(0).setMaxProgress(1);
        try { service.reconcile(console); throw new AssertionError("Remaining invalid target was hidden"); }
        catch (IllegalStateException expected) { check(applied(service, console, tasks.get(0)) && !applied(service, console, tasks.get(1))
                && applied(service, console, tasks.get(2)), "repairing_one_scope_recovers_it_while_preserving_the_other_failure"); }
        tasks.get(1).setMaxProgress(1);
        check(service.reconcile(console) == 1 && service.reconcile(console) == 0 && service.entries(console).stream().allMatch(e -> e.state() == QuestEvidenceJournal.State.APPLIED),
                "final_repair_projects_only_remaining_scope_and_retries_are_noops");
        var recovered = service.entries(console);
        for (int i = 0; i < original.size(); i++) check(recovered.get(i).admission().equals(original.get(i).admission())
                && recovered.get(i).id().equals(original.get(i).id()) && recovered.get(i).outcome() == original.get(i).outcome(), "recovery_preserves_original_beneficiary_recipe_units_and_observation_identity");
        var actor = new ServerPlayer(server, server.overworld(), new com.mojang.authlib.GameProfile(UUID.fromString(state.get("actor").getAsString()), "CraftRecovery"), ClientInformation.createDefault());
        var channel = connect(actor);
        try { check(inventory(actor).equals(state.get("inventory").getAsString()), "offline_projection_does_not_repeat_native_input_consumption_or_output"); }
        finally { server.getPlayerList().remove(actor); channel.finishAndReleaseAll(); }
        observation.addProperty("recovered", true); observation.add("finalEntries", JSON.toJsonTree(service.entries(console)));
        Files.writeString(server.getServerDirectory().resolve("craft-recovery-observation.json"), JSON.toJson(observation));
    }
    private static boolean applied(CraftQuestEvidence service, net.minecraft.commands.CommandSourceStack source, CustomTask task) {
        return service.entries(source).stream().anyMatch(e -> e.admission().taskId().equals(task.getCodeString()) && e.state() == QuestEvidenceJournal.State.APPLIED);
    }
    private static String inventory(ServerPlayer player) {
        var values = new JsonArray(); var ops = player.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
            values.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops, player.getInventory().getItem(slot)).getOrThrow());
        return values.toString();
    }
    private static io.netty.channel.embedded.EmbeddedChannel connect(ServerPlayer player) {
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        var channel = new io.netty.channel.embedded.EmbeddedChannel(new io.netty.channel.ChannelInitializer<io.netty.channel.Channel>() {
            @Override protected void initChannel(io.netty.channel.Channel channel) {
                net.minecraft.network.Connection.configureInMemoryPipeline(channel.pipeline(), net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
                connection.configurePacketHandler(channel.pipeline());
            }
        });
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        var cookie = new net.minecraft.server.network.CommonListenerCookie(player.getGameProfile(), 0, ClientInformation.createDefault(), false, net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(net.minecraft.network.protocol.game.GameProtocols.CLIENTBOUND_TEMPLATE.bind(net.minecraft.network.RegistryFriendlyByteBuf.decorator(player.registryAccess(), cookie.connectionType())));
        player.getServer().getPlayerList().placeNewPlayer(connection, player, cookie); return channel;
    }
    private static void check(boolean condition, String name) { if (!condition) throw new AssertionError(name); checks++; log("PASS " + name); }
    private static void log(String value) { org.slf4j.LoggerFactory.getLogger("citizens").info("[CRAFTRECOVERY] {}", value); }
}
