package net.citizensnpcs.util;

import java.lang.reflect.InvocationTargetException;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/** Optional adapter to FTB Quests' public task/team contracts; no quest state is mirrored here. */
public final class FtbQuestTasks {
    private static final String QUEST = "dev.ftb.mods.ftbquests.quest.";
    private static final java.util.Set<UUID> DELIVERING = new java.util.HashSet<>();
    private FtbQuestTasks() { }

    public record State(String taskId, UUID teamId, long progress, long maximum, boolean started,
            boolean completed, boolean available, boolean consumingDelivery) { }

    public enum Kind { CRAFT, POSSESSION, DELIVERY, CUSTOM, OTHER }
    public enum ClaimScope { PLAYER, TEAM }
    public record Dependency(String id, String titleJson, boolean completed) { }
    public record Prerequisites(int minimumRequired, int hiddenCount, boolean complete, java.util.List<Dependency> visible) {
        public Prerequisites { visible = java.util.List.copyOf(visible); }
    }
    /** Native cycle observations are not a durable monetary claim-generation token. */
    public record Cycle(boolean repeatable, int completedCycles, java.util.OptionalLong startedAt,
            java.util.OptionalLong completedAt) { }
    public record RewardClaim(String rewardId, ClaimScope scope, UUID ownerId, String status,
            java.util.OptionalLong claimedAt) { }
    public record TaskOrder(int index, int size, boolean sequential, Optional<Dependency> previous, boolean previousHidden) { }
    public record TaskView(String taskId, String questId, String chapterId, net.minecraft.resources.ResourceLocation type,
            Kind kind, UUID actorId, String titleJson, String questTitleJson, State state, Prerequisites prerequisites, TaskOrder order,
            Cycle cycle, java.util.List<RewardClaim> rewards) {
        public TaskView { rewards = java.util.List.copyOf(rewards); }
    }
    public record Page(java.util.List<TaskView> tasks, String nextAfterId) {
        public Page { tasks = java.util.List.copyOf(tasks); }
    }

    public static Optional<TaskView> describe(ServerPlayer player, String taskId) {
        return invoke(() -> {
            Prepared prepared = prepare(player, parseId(taskId));
            return visible(prepared.task(), prepared) ? Optional.of(describe(player, prepared)) : Optional.empty();
        });
    }

    /** Stable unsigned-ID pagination survives a removed cursor; hidden task details are never exported. */
    public static Page discover(ServerPlayer player, String afterTaskId, int limit) {
        return invoke(() -> {
            if (limit < 1 || limit > 100) throw new IllegalArgumentException("Task query limit must be 1..100");
            Long after = afterTaskId == null ? null : parseId(afterTaskId);
            Context context = context(player);
            record Identified(long id, Object task) { }
            java.util.List<Identified> candidates = new java.util.ArrayList<>();
            Class<?> baseObject = Class.forName(QUEST + "QuestObjectBase");
            for (Object task : (java.util.Collection<?>) Class.forName(QUEST + "BaseQuestFile").getMethod("getAllObjects").invoke(context.file())) {
                if (!context.taskType().isInstance(task)) continue;
                if (!(boolean) baseObject.getMethod("isValid").invoke(task)) continue;
                long id = (long) baseObject.getMethod("getId").invoke(task);
                if (after == null || Long.compareUnsigned(id, after) > 0) candidates.add(new Identified(id, task));
            }
            candidates.sort((left, right) -> Long.compareUnsigned(left.id(), right.id()));
            java.util.List<TaskView> tasks = new java.util.ArrayList<>();
            for (Identified candidate : candidates) {
                Prepared prepared = task(context, candidate.task());
                if (!visible(candidate.task(), prepared)) continue;
                if (tasks.size() == limit) return new Page(tasks, tasks.getLast().taskId());
                tasks.add(describe(player, prepared));
            }
            return new Page(tasks, null);
        });
    }

    public static long parseId(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{1,16}"))
            throw new IllegalArgumentException("Expected an FTB hexadecimal task ID");
        long id = Long.parseUnsignedLong(value, 16);
        if (id == 0) throw new IllegalArgumentException("Task ID cannot be zero");
        return id;
    }

    public static State query(ServerPlayer player, String taskId) {
        return invoke(() -> snapshot(prepare(player, parseId(taskId))));
    }

    /** Preflight and execution resolve the current file/team independently. FTB owns item matching and consumption. */
    public static State submit(ServerPlayer player, String taskId, boolean validate) {
        return invoke(() -> {
            Prepared prepared = prepare(player, parseId(taskId));
            State before = snapshot(prepared);
            if (!before.consumingDelivery()) throw new IllegalArgumentException("Task is not a supported consuming delivery");
            if (!before.available() && !before.completed()) throw new IllegalStateException("Task cannot currently start");
            if (before.completed()) return before;
            Class<?> itemType = Class.forName(QUEST + "task.ItemTask");
            boolean matching = false;
            for (var stack : player.getInventory().items) {
                if (!stack.isEmpty() && (boolean) itemType.getMethod("test", net.minecraft.world.item.ItemStack.class)
                        .invoke(prepared.task(), stack)) { matching = true; break; }
            }
            if (!matching) throw new IllegalStateException("No matching delivery items are available");
            if (validate) return before;
            if (!DELIVERING.add(player.getUUID())) throw new IllegalStateException("A quest delivery is already in progress");
            try {
                prepared.taskType().getMethod("submitTask", prepared.teamType(), ServerPlayer.class)
                        .invoke(prepared.task(), prepared.team(), player);
                State after = snapshot(prepared);
                if (after.progress() <= before.progress() && !after.completed())
                    throw new IllegalStateException("FTB did not accept the delivery");
                return after;
            } finally { DELIVERING.remove(player.getUUID()); }
        });
    }

    private static Prepared prepare(ServerPlayer player, long id) throws ReflectiveOperationException {
        Context context = context(player);
        Object task = Class.forName(QUEST + "BaseQuestFile").getMethod("getTask", long.class).invoke(context.file(), id);
        if (task == null || !(boolean) Class.forName(QUEST + "QuestObjectBase").getMethod("isValid").invoke(task))
            throw new IllegalArgumentException("Unknown FTB task");
        return task(context, task);
    }

    private static Context context(ServerPlayer player) throws ReflectiveOperationException {
        if (player == null || player.getServer() == null || !player.getServer().isSameThread())
            throw new IllegalStateException("Quest access requires the server thread and online actor");
        if (player.hasDisconnected() || player.getServer().getPlayerList().getPlayer(player.getUUID()) != player)
            throw new IllegalStateException("Quest actor session has retired");
        Class<?> base = Class.forName(QUEST + "BaseQuestFile");
        Class<?> object = Class.forName(QUEST + "QuestObject");
        Class<?> taskType = Class.forName(QUEST + "task.Task");
        Class<?> teamType = Class.forName(QUEST + "TeamData");
        Object file = ((Optional<?>) Class.forName(QUEST + "ServerQuestFile").getMethod("getInstance")
                .invoke(null)).orElseThrow(() -> new IllegalStateException("FTB Quests is unavailable"));
        Object team = ((Optional<?>) base.getMethod("getTeamData", Player.class).invoke(file, player))
                .orElseThrow(() -> new IllegalStateException("FTB team data is unavailable"));
        if (!(boolean) base.getMethod("isPlayerOnTeam", Player.class, teamType).invoke(file, player, team))
            throw new IllegalStateException("Quest actor no longer belongs to this team");
        return new Context(file, team, taskType, teamType, object);
    }

    private static Prepared task(Context context, Object task) throws ReflectiveOperationException {
        return new Prepared(context.file(), task, context.team(), context.taskType().getMethod("getQuest").invoke(task),
                context.taskType(), context.teamType(), context.objectType());
    }

    private static boolean visible(Object object, Prepared context) throws ReflectiveOperationException {
        if (!(boolean) context.objectType().getMethod("isVisible", context.teamType()).invoke(object, context.team())) return false;
        Object quest = context.objectType().getMethod("getRelatedQuest").invoke(object);
        if (quest == null) return true;
        Class<?> questType = Class.forName(QUEST + "Quest");
        if (!(boolean) questType.getMethod("isVisible", context.teamType()).invoke(quest, context.team())) return false;
        Object chapter = questType.getMethod("getChapter").invoke(quest);
        if (!(boolean) context.objectType().getMethod("isVisible", context.teamType()).invoke(chapter, context.team())) return false;
        return !context.taskType().isInstance(object)
                || !(boolean) questType.getMethod("hideDetailsUntilStartable").invoke(quest)
                || (boolean) context.teamType().getMethod("canStartTasks", questType).invoke(context.team(), quest);
    }

    private static TaskView describe(ServerPlayer player, Prepared value) throws ReflectiveOperationException {
        Class<?> questType = Class.forName(QUEST + "Quest");
        Class<?> baseObject = Class.forName(QUEST + "QuestObjectBase");
        State state = snapshot(value);
        Class<?> itemType = Class.forName(QUEST + "task.ItemTask");
        Kind kind = Class.forName(QUEST + "task.CustomTask").isInstance(value.task()) ? Kind.CUSTOM : Kind.OTHER;
        if (itemType.isInstance(value.task())) {
            kind = (boolean) itemType.getMethod("isOnlyFromCrafting").invoke(value.task()) ? Kind.CRAFT
                    : (boolean) value.taskType().getMethod("consumesResources").invoke(value.task()) ? Kind.DELIVERY : Kind.POSSESSION;
        }
        java.util.List<Dependency> dependencies = new java.util.ArrayList<>();
        int hidden = 0;
        try (var stream = (java.util.stream.Stream<?>) questType.getMethod("streamDependencies").invoke(value.quest())) {
            for (Object dependency : stream.toList()) {
                if (!visible(dependency, value)) { hidden++; continue; }
                dependencies.add(new Dependency(code(dependency), title(dependency, player),
                        (boolean) value.teamType().getMethod("isCompleted", value.objectType()).invoke(value.team(), dependency)));
            }
        }
        dependencies.sort(java.util.Comparator.comparing(Dependency::id));
        var prerequisites = new Prerequisites((int) questType.getMethod("getMinRequiredDependencies").invoke(value.quest()), hidden,
                (boolean) questType.getMethod("areDependenciesComplete", value.teamType()).invoke(value.quest(), value.team()), dependencies);
        var tasks = (java.util.List<?>) questType.getMethod("getTasksAsList").invoke(value.quest());
        int index = tasks.indexOf(value.task());
        boolean sequential = (boolean) questType.getMethod("getRequireSequentialTasks").invoke(value.quest());
        Optional<Dependency> previous = Optional.empty();
        boolean previousHidden = false;
        if (sequential && index > 0) {
            Object preceding = tasks.get(index - 1);
            if (visible(preceding, value)) previous = Optional.of(new Dependency(code(preceding), title(preceding, player),
                    (boolean) value.teamType().getMethod("isCompleted", value.objectType()).invoke(value.team(), preceding)));
            else previousHidden = true;
        }
        var order = new TaskOrder(index, tasks.size(), sequential, previous, previousHidden);
        long questId = (long) baseObject.getMethod("getId").invoke(value.quest());
        Cycle cycle = new Cycle((boolean) questType.getMethod("canBeRepeated").invoke(value.quest()),
                (int) value.teamType().getMethod("getCompletionCount", questType).invoke(value.team(), value.quest()),
                time(value.teamType().getMethod("getStartedTime", long.class).invoke(value.team(), questId)),
                time(value.teamType().getMethod("getCompletedTime", long.class).invoke(value.team(), questId)));
        java.util.List<RewardClaim> claims = new java.util.ArrayList<>();
        Class<?> rewardType = Class.forName(QUEST + "reward.Reward");
        for (Object reward : (java.util.Collection<?>) questType.getMethod("getRewards").invoke(value.quest())) {
            boolean teamReward = (boolean) rewardType.getMethod("isTeamReward").invoke(reward);
            Enum<?> status = (Enum<?>) value.teamType().getMethod("getClaimType", UUID.class, rewardType).invoke(value.team(), player.getUUID(), reward);
            claims.add(new RewardClaim(code(reward), teamReward ? ClaimScope.TEAM : ClaimScope.PLAYER,
                    teamReward ? state.teamId() : player.getUUID(), status.name(),
                    time(value.teamType().getMethod("getRewardClaimTime", UUID.class, rewardType).invoke(value.team(), player.getUUID(), reward))));
        }
        claims.sort(java.util.Comparator.comparing(RewardClaim::rewardId));
        Object taskType = value.taskType().getMethod("getType").invoke(value.task());
        return new TaskView(state.taskId(), code(value.quest()), code(questType.getMethod("getChapter").invoke(value.quest())),
                (net.minecraft.resources.ResourceLocation) Class.forName(QUEST + "task.TaskType").getMethod("getTypeId").invoke(taskType),
                kind, player.getUUID(), title(value.task(), player), title(value.quest(), player), state, prerequisites, order, cycle, claims);
    }

    private static String code(Object object) throws ReflectiveOperationException {
        return (String) Class.forName(QUEST + "QuestObjectBase").getMethod("getCodeString").invoke(object);
    }
    private static String title(Object object, ServerPlayer player) throws ReflectiveOperationException {
        var component = (net.minecraft.network.chat.Component) Class.forName(QUEST + "QuestObjectBase").getMethod("getTitle").invoke(object);
        return net.minecraft.network.chat.Component.Serializer.toJson(component, player.registryAccess());
    }
    private static java.util.OptionalLong time(Object value) {
        var date = (Optional<?>) value;
        return date.isPresent() ? java.util.OptionalLong.of(((java.util.Date) date.get()).getTime()) : java.util.OptionalLong.empty();
    }

    private static State snapshot(Prepared value) throws ReflectiveOperationException {
        Class<?> questType = Class.forName(QUEST + "Quest");
        boolean completed = (boolean) value.teamType().getMethod("isCompleted", value.objectType())
                .invoke(value.team(), value.task());
        boolean available = (boolean) value.teamType().getMethod("canStartTasks", questType)
                .invoke(value.team(), value.quest())
                && (boolean) questType.getMethod("isVisible", value.teamType()).invoke(value.quest(), value.team());
        if (available && (boolean) questType.getMethod("getRequireSequentialTasks").invoke(value.quest())) {
            var tasks = (java.util.List<?>) questType.getMethod("getTasksAsList").invoke(value.quest());
            int index = tasks.indexOf(value.task());
            available = index >= 0 && (index == 0 || (boolean) value.teamType().getMethod("isCompleted", value.objectType())
                    .invoke(value.team(), tasks.get(index - 1)));
        }
        Class<?> itemType = Class.forName(QUEST + "task.ItemTask");
        boolean delivery = itemType.isInstance(value.task())
                && (boolean) value.taskType().getMethod("consumesResources").invoke(value.task())
                && !(boolean) itemType.getMethod("isTaskScreenOnly").invoke(value.task())
                && !(boolean) itemType.getMethod("isOnlyFromCrafting").invoke(value.task());
        return new State((String) Class.forName(QUEST + "QuestObjectBase").getMethod("getCodeString").invoke(value.task()),
                (UUID) value.teamType().getMethod("getTeamId").invoke(value.team()),
                (long) value.teamType().getMethod("getProgress", value.taskType()).invoke(value.team(), value.task()),
                (long) value.taskType().getMethod("getMaxProgress").invoke(value.task()),
                (boolean) value.teamType().getMethod("isStarted", value.objectType()).invoke(value.team(), value.task()),
                completed, available, delivery);
    }

    private record Prepared(Object file, Object task, Object team, Object quest, Class<?> taskType,
            Class<?> teamType, Class<?> objectType) { }
    private record Context(Object file, Object team, Class<?> taskType, Class<?> teamType, Class<?> objectType) { }
    private interface Query<T> { T run() throws ReflectiveOperationException; }
    private static <T> T invoke(Query<T> query) {
        try { return query.run(); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException("FTB task request failed", failure.getCause());
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            throw new IllegalStateException("The supported FTB Quests task API is unavailable", unavailable);
        }
    }
}
