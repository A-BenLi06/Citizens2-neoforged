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
        Object task = base.getMethod("getTask", long.class).invoke(file, id);
        if (task == null || !(boolean) Class.forName(QUEST + "QuestObjectBase").getMethod("isValid").invoke(task))
            throw new IllegalArgumentException("Unknown FTB task");
        Object team = ((Optional<?>) base.getMethod("getTeamData", Player.class).invoke(file, player))
                .orElseThrow(() -> new IllegalStateException("FTB team data is unavailable"));
        if (!(boolean) base.getMethod("isPlayerOnTeam", Player.class, teamType).invoke(file, player, team))
            throw new IllegalStateException("Quest actor no longer belongs to this team");
        Object quest = taskType.getMethod("getQuest").invoke(task);
        return new Prepared(file, task, team, quest, taskType, teamType, object);
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
