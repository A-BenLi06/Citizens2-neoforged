package net.citizensnpcs.audit;

import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.task.CustomTask;
import dev.ftb.mods.ftbquests.quest.reward.CustomReward;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.data.PartyTeam;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.api.event.*;
import net.citizensnpcs.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

/** Two actual native crafting sessions, party changes and three fresh server processes. */
@EventBusSubscriber(modid="citizens")
public final class CraftPartyRuntimeAudit {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static final ResourceLocation RECIPE = ResourceLocation.parse("minecraft:oak_planks");
    private static final long MAXIMUM = 12;
    private static int checks;
    private static boolean defer, leaveDuringTake;
    private static PartyTeam activeParty;
    private static CustomTask activeTask;
    private static int initializedDefinitions;

    /** CustomTask maxima are runtime owner configuration, not fields in FTB's saved quest definition. */
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void configureTaskOwner(ServerAboutToStartEvent event) throws Exception {
        String phase = System.getenv("CITIZENS_CRAFT_RECOVERY_PHASE");
        if (!"party-recover".equals(phase) && !"party-verify".equals(phase)) return;
        var definition = read(event.getServer(), "craft-party-definition.json");
        String taskId = definition.get("taskId").getAsString(); long maximum = definition.get("maximum").getAsLong();
        dev.ftb.mods.ftbquests.events.CustomTaskEvent.EVENT.register(custom -> {
            if (custom.getTask().getCodeString().equals(taskId)) {
                custom.getTask().setMaxProgress(maximum); initializedDefinitions++;
            }
            return dev.architectury.event.EventResult.pass();
        });
    }

    static int run(MinecraftServer server, String phase) throws Exception {
        switch (phase) {
            case "party-seed" -> seed(server);
            case "party-recover" -> recover(server);
            case "party-verify" -> verify(server);
            default -> throw new IllegalArgumentException("Unknown party phase");
        }
        return checks;
    }

    private static void seed(MinecraftServer server) throws Exception {
        try (var owner = session(server, UUID.randomUUID(), "CraftOwner");
             var member = session(server, UUID.randomUUID(), "CraftMember")) {
            var file = ServerQuestFile.getInstance().orElseThrow();
            var personal = file.getTeamData(member.player()).orElseThrow();
            var party = (PartyTeam)FTBTeamsAPI.api().getManager().createPartyTeam(owner.player(), "CraftParty" + owner.player().getUUID(),
                    "fixture", dev.ftb.mods.ftblibrary.icon.Color4I.rgb(0x335577));
            party.invite(owner.player(), List.of(member.player().getGameProfile())); party.join(member.player());
            var team = file.getTeamData(owner.player()).orElseThrow();
            check(file.getTeamData(member.player()).orElseThrow() == team && team.getTeamId().equals(party.getTeamId()),
                    "two_native_connected_members_share_original_party");
            var chapter = new Chapter(file.newID(), file, file.getDefaultChapterGroup()); chapter.onCreated();
            var quest = new Quest(file.newID(), chapter); var config = new CompoundTag();
            config.putBoolean("can_repeat", true); config.putInt("repeat_cooldown", 0); quest.readData(config, server.registryAccess()); quest.onCreated();
            var task = new CustomTask(file.newID(), quest); task.setMaxProgress(MAXIMUM); task.onCreated();
            var definition = new JsonObject(); definition.addProperty("taskId", task.getCodeString()); definition.addProperty("maximum", MAXIMUM);
            Files.writeString(server.getServerDirectory().resolve("craft-party-definition.json"), JSON.toJson(definition), StandardOpenOption.CREATE_NEW);
            var reward = new CustomReward(file.newID(), quest); var shared = new CompoundTag(); shared.putBoolean("team_reward", true);
            reward.readData(shared, server.registryAccess()); reward.onCreated(); file.clearCachedData(); file.clearCachedProgress();
            var service = CraftQuestEvidence.current(server);
            var binding = service.bind(owner.player().createCommandSourceStack().withPermission(2), task.getCodeString(), RECIPE);
            check(binding.maximum() == MAXIMUM && binding.repeatable(), "one_explicit_recipe_binding_owns_shared_repeatable_task");
            for (var session : List.of(owner, member)) {
                var sentinel = new ItemStack(Items.IRON_AXE); sentinel.setDamageValue(7);
                sentinel.set(DataComponents.CUSTOM_NAME, Component.literal("Retained " + session.player().getUUID()));
                session.player().getInventory().setItem(35, sentinel);
            }
            activeParty = party; activeTask = task; defer = true;
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, CraftingResultEvent.class, event -> {
                if (defer && event.getObservation().recipe().id().equals(RECIPE)) {
                    activeTask.setMaxProgress(MAXIMUM + 1);
                    if (leaveDuringTake) {
                        try { activeParty.leave(event.getPlayer().getUUID()); }
                        catch (com.mojang.brigadier.exceptions.CommandSyntaxException rejected) { throw new AssertionError("Native party leave rejected", rejected); }
                        leaveDuringTake = false;
                    }
                }
            });
            take(owner.player()); task.setMaxProgress(MAXIMUM);
            leaveDuringTake = true; take(member.player()); task.setMaxProgress(MAXIMUM); defer = false;
            var entries = service.entries(server.createCommandSourceStack());
            check(entries.size() == 2 && entries.stream().allMatch(e -> e.state() == QuestEvidenceJournal.State.CONFIRMED
                    && e.outcome() == QuestEvidenceJournal.Outcome.RETURNED && e.admission().units() == 4),
                    "two_actual_result_takes_retain_confirmed_original_four_plank_outputs");
            check(entries.stream().map(e -> e.admission().actorId()).collect(java.util.stream.Collectors.toSet())
                    .equals(Set.of(owner.player().getUUID(), member.player().getUUID()))
                    && entries.stream().map(e -> e.admission().generationId()).distinct().count() == 1 && team.getProgress(task) == 0,
                    "both_original_actors_share_one_unprojected_generation");
            check(!party.getMembers().contains(member.player().getUUID()) && file.getTeamData(member.player()).orElseThrow().getTeamId().equals(personal.getTeamId())
                    && personal.getProgress(task) == 0, "native_leave_during_consumption_does_not_redirect_original_admission");
            var state = new JsonObject(); state.addProperty("owner", owner.player().getUUID().toString()); state.addProperty("member", member.player().getUUID().toString());
            state.addProperty("party", party.getTeamId().toString()); state.addProperty("personal", personal.getTeamId().toString());
            state.addProperty("task", task.getCodeString()); state.addProperty("quest", quest.getCodeString()); state.addProperty("reward", reward.getCodeString());
            state.add("seedEntries", JSON.toJsonTree(entries)); state.add("seedInventories", inventories(owner, member));
            save(server, file); write(server, "craft-party-state.json", state);
            check(Files.exists(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("playerdata/" + owner.player().getUUID() + ".dat")), "original_players_and_party_are_saved_before_fresh_process_recovery");
        }
    }

    private static void recover(MinecraftServer server) throws Exception {
        var state = read(server, "craft-party-state.json"); var file = ServerQuestFile.getInstance().orElseThrow();
        var service = CraftQuestEvidence.current(server); var console = server.createCommandSourceStack();
        var task = task(file, state); var quest = task.getQuest(); var team = file.getNullableTeamData(uuid(state, "party"));
        var personal = file.getNullableTeamData(uuid(state, "personal"));
        check(initializedDefinitions == 1 && task.getMaxProgress() == MAXIMUM,
                "public_native_custom_task_initialization_restores_owner_defined_maximum");
        check(server.getPlayerList().getPlayers().isEmpty() && team != null && personal != null && team.getProgress(task) == 0 && personal.getProgress(task) == 0,
                "fresh_process_loads_original_party_and_personal_scopes_without_contributors_online");
        check(JSON.toJsonTree(service.entries(console)).equals(state.get("seedEntries")), "fresh_process_preserves_both_original_confirmed_admissions");
        check(service.reconcile(console) == 1 && team.getProgress(task) == 8 && personal.getProgress(task) == 0
                && service.reconcile(console) == 0 && service.entries(console).stream().allMatch(e -> e.state() == QuestEvidenceJournal.State.APPLIED),
                "offline_reconciliation_sums_both_contributors_once_into_original_party_only");
        try (var owner = session(server, uuid(state, "owner"), "CraftOwner"); var member = session(server, uuid(state, "member"), "CraftMember")) {
            check(inventories(owner, member).equals(state.get("seedInventories")), "reconnect_preserves_both_complete_component_inventories_after_offline_recovery");
            check(file.getTeamData(owner.player()).orElseThrow() == team && file.getTeamData(member.player()).orElseThrow() == personal,
                    "new_native_sessions_reacquire_original_party_and_former_member_personal_team");
            replayAll(server, service, owner.player(), member.player());
            check(team.getProgress(task) == 8 && personal.getProgress(task) == 0 && service.entries(console).size() == 2,
                    "original_notifications_replayed_after_leave_cannot_credit_personal_scope");
            take(member.player());
            check(personal.getProgress(task) == 4 && team.getProgress(task) == 8 && service.entries(console).size() == 3,
                    "new_personal_craft_has_distinct_scope_and_keeps_party_contributions");
            var party = (PartyTeam)FTBTeamsAPI.api().getManager().getTeamByID(uuid(state, "party")).orElseThrow();
            party.invite(owner.player(), List.of(member.player().getGameProfile())); party.join(member.player());
            check(file.getTeamData(member.player()).orElseThrow() == team && team.getProgress(task) == 8,
                    "native_rejoin_uses_shared_progress_without_replaying_personal_craft");
            replayAll(server, service, owner.player(), member.player());
            check(team.getProgress(task) == 8 && service.entries(console).size() == 3,
                    "personal_and_party_notifications_keep_original_scopes_after_rejoin");
            take(member.player());
            check(team.getProgress(task) == MAXIMUM && team.isCompleted(task) && service.entries(console).size() == 4,
                    "returning_member_new_craft_completes_original_shared_total");
            var reward = (CustomReward)file.getReward(Long.parseUnsignedLong(state.get("reward").getAsString(), 16));
            team.claimReward(member.player(), reward, false);
            check(team.getCompletionCount(quest) == 1 && team.getProgress(task) == 0,
                    "native_shared_reward_claim_resets_completed_generation_once");
            replayAll(server, service, owner.player(), member.player());
            check(team.getProgress(task) == 0 && team.getCompletionCount(quest) == 1 && service.entries(console).size() == 4,
                    "old_notifications_cannot_enter_new_repeat_generation_for_either_member");
            take(owner.player()); take(member.player());
            check(team.getProgress(task) == 8 && !team.isCompleted(task) && service.entries(console).size() == 6,
                    "new_repeat_generation_accepts_each_member_actual_output_once");
            var generations = service.entries(console).stream().map(e -> e.admission().generationId()).distinct().count();
            check(generations == 3 && personal.getProgress(task) == 4, "shared_old_new_and_personal_generations_remain_distinct");
            state.add("recoveredEntries", JSON.toJsonTree(service.entries(console))); state.add("recoveredInventories", inventories(owner, member));
            state.addProperty("recoveredCycle", team.getCompletionCount(quest)); state.addProperty("recoveredProgress", team.getProgress(task));
            save(server, file); write(server, "craft-party-recovered.json", state);
        }
    }

    private static void verify(MinecraftServer server) throws Exception {
        var state = read(server, "craft-party-recovered.json"); var file = ServerQuestFile.getInstance().orElseThrow();
        var service = CraftQuestEvidence.current(server); var console = server.createCommandSourceStack(); var task = task(file, state);
        var team = file.getNullableTeamData(uuid(state, "party")); var personal = file.getNullableTeamData(uuid(state, "personal"));
        check(initializedDefinitions == 1 && task.getMaxProgress() == MAXIMUM,
                "second_process_initializes_custom_task_through_original_public_owner_event");
        check(server.getPlayerList().getPlayers().isEmpty() && team.getCompletionCount(task.getQuest()) == 1 && team.getProgress(task) == 8 && personal.getProgress(task) == 4,
                "second_fresh_process_preserves_repeat_partial_progress_and_personal_history");
        check(JSON.toJsonTree(service.entries(console)).equals(state.get("recoveredEntries")) && service.reconcile(console) == 0,
                "second_restart_reconciliation_is_noop_for_all_six_original_applied_crafts");
        try (var owner = session(server, uuid(state, "owner"), "CraftOwner"); var member = session(server, uuid(state, "member"), "CraftMember")) {
            check(inventories(owner, member).equals(state.get("recoveredInventories")), "second_reconnect_preserves_both_full_inventories_and_components");
            check(file.getTeamData(owner.player()).orElseThrow() == team && file.getTeamData(member.player()).orElseThrow() == team,
                    "second_reconnect_restores_two_native_party_members");
            replayAll(server, service, owner.player(), member.player());
            check(team.getProgress(task) == 8 && personal.getProgress(task) == 4 && service.entries(console).size() == 6,
                    "replayed_all_generations_after_restart_preserve_exact_native_totals");
            take(member.player());
            check(team.getProgress(task) == MAXIMUM && team.isCompleted(task) && service.entries(console).size() == 7,
                    "new_native_take_completes_only_retained_repeat_generation");
            var reward = (CustomReward)file.getReward(Long.parseUnsignedLong(state.get("reward").getAsString(), 16));
            team.claimReward(owner.player(), reward, false);
            check(team.getCompletionCount(task.getQuest()) == 2 && team.getProgress(task) == 0,
                    "other_member_claims_next_shared_reward_and_advances_native_cycle");
            replayAll(server, service, owner.player(), member.player());
            check(team.getProgress(task) == 0 && team.getCompletionCount(task.getQuest()) == 2 && personal.getProgress(task) == 4
                    && service.entries(console).size() == 7 && service.reconcile(console) == 0,
                    "all_original_crafts_remain_once_after_two_native_repeat_resets");
            var entries = service.entries(console);
            check(entries.stream().allMatch(e -> e.state() == QuestEvidenceJournal.State.APPLIED && e.admission().units() == 4
                    && e.outcome() == QuestEvidenceJournal.Outcome.RETURNED), "all_seven_original_native_crafts_retain_exact_outputs_and_successful_outcomes");
            state.add("finalEntries", JSON.toJsonTree(entries)); state.add("finalInventories", inventories(owner, member));
            state.addProperty("finalCycle", team.getCompletionCount(task.getQuest())); state.addProperty("finalProgress", team.getProgress(task));
            state.addProperty("personalProgress", personal.getProgress(task)); save(server, file); write(server, "craft-party-final.json", state);
        }
    }

    private static void replayAll(MinecraftServer server, CraftQuestEvidence service, ServerPlayer owner, ServerPlayer member) {
        for (var entry : service.entries(server.createCommandSourceStack())) {
            var encoded = JsonParser.parseString(entry.admission().evidence()).getAsJsonObject();
            var ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE); var inputs = new ArrayList<ItemStack>();
            for (var input : encoded.getAsJsonArray("inputs")) inputs.add(input.isJsonNull() ? ItemStack.EMPTY : ItemStack.CODEC.parse(ops, input).getOrThrow());
            var observation = new CraftingEvidence.Observation(entry.admission().observationId(), entry.admission().actorId(),
                    ResourceLocation.parse(encoded.get("dimension").getAsString()), encoded.get("serverTick").getAsLong(), encoded.get("menuId").getAsInt(),
                    CraftingEvidence.Source.valueOf(encoded.get("source").getAsString()), CraftingRecipes.find(server, RECIPE).orElseThrow(),
                    encoded.get("width").getAsInt(), encoded.get("height").getAsInt(), inputs, ItemStack.CODEC.parse(ops, encoded.get("output")).getOrThrow());
            if (!CraftingEvidence.encode(observation, server.registryAccess()).equals(entry.admission().evidence()))
                throw new AssertionError("Replay reconstruction changed original native observation");
            var actor = entry.admission().actorId().equals(owner.getUUID()) ? owner : member;
            if (!actor.getUUID().equals(entry.admission().actorId())) throw new AssertionError("Replay actor changed");
            NeoForge.EVENT_BUS.post(new CraftingResultEvent(actor, observation));
            NeoForge.EVENT_BUS.post(new CraftingOutcomeEvent(observation, CraftingOutcomeEvent.Outcome.RETURNED));
        }
    }

    private static void take(ServerPlayer player) {
        var server = player.getServer(); var position = new BlockPos(2, -60, 2);
        server.overworld().setBlockAndUpdate(position, Blocks.CRAFTING_TABLE.defaultBlockState());
        var menu = new CraftingMenu(92, player.getInventory(), ContainerLevelAccess.create(server.overworld(), position)); player.containerMenu = menu;
        var holder = (RecipeHolder<CraftingRecipe>)(RecipeHolder<?>)server.getRecipeManager().byKey(RECIPE).orElseThrow();
        menu.beginPlacingRecipe(); menu.getSlot(1).set(new ItemStack(Items.OAK_LOG)); menu.finishPlacingRecipe(holder);
        menu.clicked(0, 0, ClickType.PICKUP, player);
        check(menu.getCarried().is(Items.OAK_PLANKS) && menu.getCarried().getCount() == 4 && menu.getSlot(1).getItem().isEmpty(),
                "actual_result_take_consumes_one_log_and_returns_four_planks");
        check(player.getInventory().add(menu.getCarried().copy()), "original_four_plank_output_enters_contributor_inventory");
        menu.setCarried(ItemStack.EMPTY); player.closeContainer();
    }
    private record Session(ServerPlayer player, EmbeddedChannel channel) implements AutoCloseable {
        @Override public void close() { player.getServer().getPlayerList().remove(player); channel.finishAndReleaseAll(); }
    }
    private static Session session(MinecraftServer server, UUID id, String name) {
        var player = new ServerPlayer(server, server.overworld(), new GameProfile(id, name), ClientInformation.createDefault());
        player.setPos(1, -60, 1); return new Session(player, CraftRecoveryRuntimeAudit.connect(player));
    }
    private static JsonObject inventories(Session owner, Session member) {
        var result = new JsonObject(); for (var session : List.of(owner, member))
            result.addProperty(session.player().getUUID().toString(), CraftRecoveryRuntimeAudit.inventory(session.player())); return result;
    }
    private static CustomTask task(ServerQuestFile file, JsonObject state) { return (CustomTask)file.getTask(Long.parseUnsignedLong(state.get("task").getAsString(), 16)); }
    private static UUID uuid(JsonObject state, String key) { return UUID.fromString(state.get(key).getAsString()); }
    private static JsonObject read(MinecraftServer server, String name) throws Exception { return JsonParser.parseString(Files.readString(server.getServerDirectory().resolve(name))).getAsJsonObject(); }
    private static void write(MinecraftServer server, String name, JsonObject state) throws Exception { Files.writeString(server.getServerDirectory().resolve(name), JSON.toJson(state)); }
    private static void save(MinecraftServer server, ServerQuestFile file) {
        ((dev.ftb.mods.ftbteams.data.TeamManagerImpl)FTBTeamsAPI.api().getManager()).saveNow(); file.saveNow();
        for (var team : file.getAllTeamData()) team.saveIfChanged(); server.saveEverything(true, true, true);
    }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name); checks++;
        org.slf4j.LoggerFactory.getLogger("citizens").info("[CRAFTPARTY] PASS {}", name);
    }
}
