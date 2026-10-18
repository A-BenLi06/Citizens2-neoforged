package net.citizensnpcs.util;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;

import net.citizensnpcs.api.util.PermissionUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;

/** Explicit CustomReward bindings at the native pre-reset claim boundary. No script callback issues money. */
public final class FtbMonetaryRewards implements AutoCloseable {
    private static final String QUEST = "dev.ftb.mods.ftbquests.quest.", ECONOMY = "net.yuuniverse.economy.api.";
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(FtbMonetaryRewards.class);
    private static FtbMonetaryRewards current;
    private final MinecraftServer server;
    private final QuestRewardJournal journal;
    private final Map<String, QuestRewardJournal.Binding> owned = new LinkedHashMap<>();
    private final Set<String> admitting = new HashSet<>();
    private boolean closed;

    public static FtbMonetaryRewards current(MinecraftServer server) {
        if (current == null || current.server != server) throw new IllegalStateException("Monetary reward journal is unavailable");
        current.active(); return current;
    }
    private FtbMonetaryRewards(MinecraftServer server, Path path) throws IOException {
        if (!server.isSameThread()) throw new IllegalStateException("Reward journal requires the server thread");
        this.server = server; journal = QuestRewardJournal.open(path);
        for (var binding : journal.bindings()) owned.put(binding.rewardId(), binding);
    }
    public QuestRewardJournal.Binding bind(CommandSourceStack source, String rewardId, String currencyId, BigDecimal amount) throws IOException {
        authorize(source, true);
        return reflected(() -> {
            Object reward = reward(rewardId); Object team = team(source.getPlayer());
            if (!visible(reward, team)) throw new IllegalArgumentException("Reward is not visible");
            Object quest = quest(reward);
            var currency = currency(currencyId);
            long minor = amount.movePointRight(currency.scale()).longValueExact();
            if (minor <= 0) throw new IllegalArgumentException("Reward amount must be positive");
            var previous = journal.bindings().stream().filter(value -> value.rewardId().equals(code(reward))).findFirst().orElse(null);
            if (previous != null) {
                validate(previous, reward);
                if (!previous.currencyId().equals(currency.id()) || previous.currencyScale() != currency.scale() || previous.amount() != minor)
                    throw new IllegalStateException("Reward rebinding requires explicit migration");
                return previous;
            }
            var binding = new QuestRewardJournal.Binding(UUID.randomUUID(), code(reward), code(quest), teamReward(reward), repeatable(quest),
                    definition(reward), currency.id(), currency.scale(), minor, source.getPlayer().getUUID(), System.currentTimeMillis());
            journal.bind(binding); owned.put(binding.rewardId(), binding); return binding;
        });
    }

    /** Called only around TeamData.markRewardAsClaimed inside its native player claim method. */
    public static boolean nativeClaim(Object nativeTeam, Object nativeReward, ServerPlayer player, BooleanSupplier original) {
        // Unowned rewards retain their entire native behavior, including server-side automatic rewards.
        FtbMonetaryRewards service = current;
        if (service == null) {
            if (nativeReward.getClass().getName().equals(QUEST + "reward.CustomReward"))
                throw new IllegalStateException("Custom reward claims require the reward journal lifecycle to be ready");
            return original.getAsBoolean();
        }
        return service.claim(nativeTeam, nativeReward, player, original);
    }
    private boolean claim(Object nativeTeam, Object nativeReward, ServerPlayer player, BooleanSupplier original) {
        return reflected(() -> {
            String rewardId = code(nativeReward);
            var binding = owned.get(rewardId);
            if (binding == null) return original.getAsBoolean();
            active(); online(player); validate(binding, nativeReward);
            if (reward(rewardId) != nativeReward) throw new IllegalStateException("Native reward definition instance has retired");
            if (team(player) != nativeTeam) throw new IllegalStateException("Claim does not belong to the actor's current native team");
            var teamType = Class.forName(QUEST + "TeamData"); var rewardType = Class.forName(QUEST + "reward.Reward");
            if ((boolean) teamType.getMethod("isLocked").invoke(nativeTeam)
                    || (boolean) teamType.getMethod("isRewardBlocked", rewardType).invoke(nativeTeam, nativeReward)) return false;
            Enum<?> status = (Enum<?>) teamType.getMethod("getClaimType", UUID.class, rewardType).invoke(nativeTeam, player.getUUID(), nativeReward);
            if (!status.name().equals("CAN_CLAIM") || !visible(nativeReward, nativeTeam)) return false;
            UUID teamId = (UUID) teamType.getMethod("getTeamId").invoke(nativeTeam);
            String admissionScope = teamId + ":" + binding.questId();
            if (!admitting.add(admissionScope)) throw new IllegalStateException("A native reward claim is already in progress for this team quest");
            QuestRewardJournal.Claim prepared = null;
            boolean accepted = false;
            try {
                int cycle = cycle(nativeTeam, quest(nativeReward), binding.repeatable());
                prepared = journal.prepare(new QuestRewardJournal.Intent(binding, teamId, binding.teamReward() ? teamId : player.getUUID(), player.getUUID(), cycle));
                accepted = original.getAsBoolean();
                if (!accepted) { journal.review(prepared.id()); return false; }
                // The actual boolean acceptance is recorded before any CustomReward/XMod callback.
                journal.accepted(prepared.id(), cycle(nativeTeam, quest(nativeReward), binding.repeatable()));
                settle(prepared.id());
                return true;
            } catch (IOException | RuntimeException | Error failure) {
                if (prepared == null) throw failure;
                if (!accepted) {
                    try { journal.review(prepared.id()); } catch (IOException | RuntimeException reviewFailure) { failure.addSuppressed(reviewFailure); }
                }
                // FTB may already have accepted/reset. Never turn a payment failure into another native claim.
                LOGGER.error("Native monetary reward {} needs reconciliation; accepted={}", prepared.id(), accepted, failure);
                if (accepted) return true;
                throw failure;
            } finally { admitting.remove(admissionScope); }
        });
    }
    public int reconcile(CommandSourceStack source) throws IOException {
        authorize(source, false); int count = 0;
        for (var claim : journal.claims()) if (claim.state() == QuestRewardJournal.State.ACCEPTED) { settle(claim.id()); count++; }
        return count;
    }
    public QuestRewardJournal.Claim resolve(CommandSourceStack source, String id, boolean accept, String reason) throws IOException {
        authorize(source, true); var claim = journal.resolve(id, source.getPlayer().getUUID(), reason, accept);
        if (accept) settle(id); return journal.get(id);
    }
    public List<QuestRewardJournal.Binding> bindings(CommandSourceStack source) { authorize(source, false); return journal.bindings(); }
    public List<QuestRewardJournal.Claim> claims(CommandSourceStack source) { authorize(source, false); return journal.claims(); }

    private void settle(String id) throws IOException {
        active(); var claim = journal.get(id);
        if (claim.state() == QuestRewardJournal.State.PAID) return;
        if (claim.state() != QuestRewardJournal.State.ACCEPTED) throw new IllegalStateException("Reward intent is not confirmed");
        reflected(() -> {
            var intent = claim.intent(); Object api = economy(); var apiType = Class.forName(ECONOMY + "EconomyApi");
            // Beneficiary is immutable; reconnects and later party changes never redirect settlement.
            var currency = currency(api, intent.binding().currencyId());
            if (!currency.id().equals(intent.binding().currencyId()) || currency.scale() != intent.binding().currencyScale())
                throw new IllegalStateException("Bound currency definition changed");
            Object receipt = apiType.getMethod("credit", UUID.class, String.class, long.class, String.class, String.class)
                    .invoke(api, intent.beneficiaryId(), intent.binding().currencyId(), intent.binding().amount(), intent.operationKey(), intent.sourceReference());
            UUID transaction = (UUID) receipt.getClass().getMethod("transactionId").invoke(receipt);
            journal.paid(id, transaction); return null;
        });
    }
    private record Currency(String id, int scale) { }
    private static Object economy() throws ReflectiveOperationException {
        return ((Optional<?>) Class.forName(ECONOMY + "YuuniverseEconomyApi").getMethod("current").invoke(null))
                .orElseThrow(() -> new IllegalStateException("Yuuniverse Economy is unavailable"));
    }
    private static Currency currency(String id) throws ReflectiveOperationException {
        return currency(economy(), id);
    }
    private static Currency currency(Object api, String id) throws ReflectiveOperationException {
        Object definition = ((Optional<?>) Class.forName(ECONOMY + "EconomyApi").getMethod("resolveCurrency", String.class).invoke(api, id))
                .orElseThrow(() -> new IllegalArgumentException("Currency is unavailable"));
        return new Currency((String) definition.getClass().getMethod("id").invoke(definition), (int) definition.getClass().getMethod("scale").invoke(definition));
    }
    private Object reward(String id) throws ReflectiveOperationException {
        Object reward = Class.forName(QUEST + "BaseQuestFile").getMethod("getReward", long.class).invoke(file(), FtbQuestTasks.parseId(id));
        if (reward == null || !Class.forName(QUEST + "reward.CustomReward").isInstance(reward)) throw new IllegalArgumentException("Only an existing native CustomReward can be bound");
        return reward;
    }
    private static Object file() throws ReflectiveOperationException {
        return ((Optional<?>) Class.forName(QUEST + "ServerQuestFile").getMethod("getInstance").invoke(null))
                .orElseThrow(() -> new IllegalStateException("FTB Quests is unavailable"));
    }
    private static Object team(ServerPlayer player) throws ReflectiveOperationException {
        Object file = file(); var base = Class.forName(QUEST + "BaseQuestFile"); var teamType = Class.forName(QUEST + "TeamData");
        Object team = ((Optional<?>) base.getMethod("getTeamData", Player.class).invoke(file, player)).orElseThrow(() -> new IllegalStateException("Native team is unavailable"));
        if (!(boolean) base.getMethod("isPlayerOnTeam", Player.class, teamType).invoke(file, player, team)) throw new IllegalStateException("Player is no longer on the native team");
        return team;
    }
    private static Object quest(Object reward) throws ReflectiveOperationException { return Class.forName(QUEST + "reward.Reward").getMethod("getQuest").invoke(reward); }
    private static String code(Object object) { return reflected(() -> (String) Class.forName(QUEST + "QuestObjectBase").getMethod("getCodeString").invoke(object)); }
    private static boolean teamReward(Object reward) throws ReflectiveOperationException { return (boolean) Class.forName(QUEST + "reward.Reward").getMethod("isTeamReward").invoke(reward); }
    private static boolean repeatable(Object quest) throws ReflectiveOperationException { return (boolean) Class.forName(QUEST + "Quest").getMethod("canBeRepeated").invoke(quest); }
    private static int cycle(Object team, Object quest, boolean repeatable) throws ReflectiveOperationException {
        return repeatable ? (int) Class.forName(QUEST + "TeamData").getMethod("getCompletionCount", Class.forName(QUEST + "Quest")).invoke(team, quest) : 0;
    }
    private static boolean visible(Object reward, Object team) throws ReflectiveOperationException {
        var object = Class.forName(QUEST + "QuestObject"); var teamType = Class.forName(QUEST + "TeamData"); Object quest = quest(reward);
        return (boolean) Class.forName(QUEST + "QuestObjectBase").getMethod("isValid").invoke(reward)
                && (boolean) object.getMethod("isVisible", teamType).invoke(quest, team)
                && (boolean) object.getMethod("isVisible", teamType).invoke(Class.forName(QUEST + "Quest").getMethod("getChapter").invoke(quest), team);
    }
    /** Native serialized definitions, including reward order/tasks, fence reinterpretation after reload. */
    private String definition(Object reward) throws ReflectiveOperationException {
        Object quest = quest(reward); var questType = Class.forName(QUEST + "Quest"); var object = Class.forName(QUEST + "QuestObjectBase");
        var data = new TreeMap<String, String>(); var objects = new ArrayList<Object>(); objects.add(quest);
        objects.addAll((Collection<?>) questType.getMethod("getTasksAsList").invoke(quest));
        objects.addAll((Collection<?>) questType.getMethod("getRewards").invoke(quest));
        var taskType = Class.forName(QUEST + "task.Task");
        for (Object value : objects) {
            var tag = new CompoundTag(); object.getMethod("writeData", CompoundTag.class, net.minecraft.core.HolderLookup.Provider.class).invoke(value, tag, server.registryAccess());
            String extra = taskType.isInstance(value) ? ":maximum=" + taskType.getMethod("getMaxProgress").invoke(value) : "";
            data.put(code(value), value.getClass().getName() + extra + ":" + canonical(tag));
        }
        var definition = new com.google.gson.JsonObject();
        definition.add("objects", new com.google.gson.Gson().toJsonTree(data));
        definition.add("taskOrder", new com.google.gson.Gson().toJsonTree(((Collection<?>) questType.getMethod("getTasksAsList").invoke(quest)).stream().map(FtbMonetaryRewards::code).toList()));
        definition.add("rewardOrder", new com.google.gson.Gson().toJsonTree(((Collection<?>) questType.getMethod("getRewards").invoke(quest)).stream().map(FtbMonetaryRewards::code).toList()));
        return QuestRewardJournal.digest(definition.toString());
    }
    private static String canonical(net.minecraft.nbt.Tag tag) {
        if (tag instanceof CompoundTag compound) {
            var fields = new TreeMap<String, String>(); for (String key : compound.getAllKeys()) fields.put(key, canonical(compound.get(key)));
            return new com.google.gson.Gson().toJson(fields);
        }
        if (tag instanceof net.minecraft.nbt.ListTag list) return new com.google.gson.Gson().toJson(list.stream().map(FtbMonetaryRewards::canonical).toList());
        return tag.getId() + ":" + tag;
    }
    private void validate(QuestRewardJournal.Binding binding, Object reward) throws ReflectiveOperationException {
        if (!Class.forName(QUEST + "reward.CustomReward").isInstance(reward) || !binding.questId().equals(code(quest(reward)))
                || binding.teamReward() != teamReward(reward) || binding.repeatable() != repeatable(quest(reward))
                || !binding.definitionRevision().equals(definition(reward))) throw new IllegalStateException("Native monetary reward definition changed; explicit migration is required");
    }
    private void active() { if (closed || !server.isSameThread()) throw new IllegalStateException("Monetary rewards require their active server thread"); }
    private void online(ServerPlayer player) {
        if (player == null || player.getServer() != server || player.hasDisconnected() || server.getPlayerList().getPlayer(player.getUUID()) != player)
            throw new IllegalStateException("Reward actor session has retired");
    }
    private void authorize(CommandSourceStack source, boolean playerRequired) {
        active(); if (source == null || source.getServer() != server || !PermissionUtil.hasPermission(source, "citizens.admin")) throw new IllegalStateException("Reward administration is not permitted");
        if (source.getPlayer() != null) online(source.getPlayer()); else if (playerRequired) throw new IllegalStateException("Binding/review requires an authenticated operator UUID");
    }
    private interface Request<T> { T run() throws ReflectiveOperationException, IOException; }
    private static <T> T reflected(Request<T> request) {
        try { return request.run(); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw new IllegalStateException("Native reward provider request failed", failure.getCause());
        } catch (ReflectiveOperationException | IOException | LinkageError failure) { throw new IllegalStateException("Native reward provider/journal is unavailable", failure); }
    }
    @Override public void close() throws IOException { active(); closed = true; journal.close(); }
    @EventBusSubscriber(modid = "citizens")
    public static final class Lifecycle {
        @SubscribeEvent(priority = EventPriority.HIGHEST) public static void start(ServerAboutToStartEvent event) {
            try { current = new FtbMonetaryRewards(event.getServer(), event.getServer().getWorldPath(LevelResource.ROOT).resolve("citizens/quest-rewards")); }
            catch (IOException | RuntimeException failure) { current = null; LOGGER.error("Monetary reward journal is unavailable; retained records need review", failure); }
        }
        @SubscribeEvent(priority = EventPriority.LOWEST) public static void recover(ServerStartedEvent event) {
            if (current == null) return;
            for (var claim : current.journal.claims()) if (claim.state() == QuestRewardJournal.State.ACCEPTED) try { current.settle(claim.id()); }
            catch (IOException | RuntimeException failure) { LOGGER.error("Monetary reward {} remains pending reconciliation", claim.id(), failure); }
        }
        @SubscribeEvent(priority = EventPriority.HIGHEST) public static void stop(ServerStoppingEvent event) {
            var service = current; current = null; if (service != null) try { service.close(); }
            catch (IOException | RuntimeException failure) { LOGGER.error("Monetary reward journal close failed", failure); }
        }
    }
}
