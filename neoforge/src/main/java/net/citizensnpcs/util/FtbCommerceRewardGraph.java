package net.citizensnpcs.util;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import net.citizensnpcs.util.CommerceTaskJournal.Binding;

/** Read-only commerce admission using FTB's native dependency and optional-child evaluators. */
public final class FtbCommerceRewardGraph {
    private static final String FTB = "dev.ftb.mods.ftbquests.quest.";
    private static final ThreadLocal<FtbCommerceRewardGraph> ACTIVE = new ThreadLocal<>();
    private final Object team;
    private final Class<?> questType, taskType, objectType, teamType;
    private final Map<String, List<Binding>> quests = new HashMap<>();
    private final Map<String, Binding> tasks = new HashMap<>();
    private final Map<Check, Boolean> verified = new HashMap<>();
    private final Set<Check> checking = new HashSet<>();
    private final List<RuntimeException> failures = new ArrayList<>();
    private Consumer<List<Binding>> validator;
    private boolean filtering;
    private record Check(Object object, boolean started) { }

    FtbCommerceRewardGraph(Object team, List<Binding> bindings) throws ClassNotFoundException {
        this.team = team;
        questType = Class.forName(FTB + "Quest"); taskType = Class.forName(FTB + "task.Task");
        objectType = Class.forName(FTB + "QuestObject"); teamType = Class.forName(FTB + "TeamData");
        for (Binding binding : bindings) {
            tasks.put(binding.taskId(), binding);
            quests.computeIfAbsent(binding.questId(), ignored -> new ArrayList<>()).add(binding);
        }
    }

    boolean affects(Object object) { return affects(object, new HashSet<>()); }

    private boolean affects(Object object, Set<Object> visited) {
        if (!visited.add(object)) return false;
        if (questType.isInstance(object)) {
            if (quests.containsKey(code(object))) return true;
            for (Object dependency : dependencies(object)) if (affects(dependency, visited)) return true;
        } else if (taskType.isInstance(object)) {
            if (tasks.containsKey(code(object))) return true;
            Object quest = get(object, "getQuest");
            for (Object dependency : dependencies(quest)) if (affects(dependency, visited)) return true;
            for (Object previous : preceding(object, quest)) if (affects(previous, visited)) return true;
        } else {
            for (Object child : children(object)) if (affects(child, visited)) return true;
        }
        return false;
    }

    void validate(Object quest, Consumer<List<Binding>> validator) {
        this.validator = validator;
        var previous = ACTIVE.get(); ACTIVE.set(this);
        try {
            if (!verify(quest, false)) {
                String detail = failures.isEmpty() ? "" : ": " + failures.getFirst().getMessage();
                var failure = new IllegalStateException("Commerce-backed reward prerequisites lack validated evidence: " + code(quest) + detail);
                failures.forEach(failure::addSuppressed);
                throw failure;
            }
        } finally {
            if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
        }
    }

    /** Native getters are filtered only during an explicit evaluation for the same team. */
    public static boolean filter(Object team, Object object, boolean started, boolean nativeValue) {
        var scope = ACTIVE.get();
        return nativeValue && (scope == null || !scope.filtering || scope.team != team || scope.verify(object, started));
    }

    /** Admission must evaluate filtered predicates without consulting or populating FTB's dependency cache. */
    public static boolean dependenciesComplete(Object team, Object quest, BooleanSupplier original) {
        var scope = ACTIVE.get();
        return scope == null || !scope.filtering || scope.team != team ? original.getAsBoolean()
                : scope.criteria(quest, "areDependenciesComplete");
    }

    private boolean verify(Object object, boolean started) {
        if (!affects(object)) return true;
        var key = new Check(object, started);
        Boolean cached = verified.get(key); if (cached != null) return cached;
        if (!checking.add(key)) {
            failures.add(new IllegalStateException("Cyclic commerce reward prerequisite: " + code(object)));
            return false;
        }
        boolean result;
        try {
            if (questType.isInstance(object)) {
                if (started) {
                    // A quest can start through any task. Do not require unused alternatives to be backed.
                    result = children(object).stream().anyMatch(child -> nativeState(child, true));
                } else {
                    result = criteria(object, "isCompletedRaw");
                }
                if (started) result = result && canStart(object);
            } else if (taskType.isInstance(object)) {
                Binding binding = tasks.get(code(object));
                if (binding != null) validateBindings(List.of(binding));
                Object quest = get(object, "getQuest");
                result = canStart(quest);
                for (Object previous : preceding(object, quest)) result = result && nativeState(previous, false);
            } else if (started) {
                result = children(object).stream().anyMatch(child -> nativeState(child, true));
            } else {
                // FTB retains its own optional/excluded-child completion policy.
                result = criteria(object, "isCompletedRaw");
            }
        } catch (RuntimeException failure) {
            failures.add(failure); result = false;
        } finally { checking.remove(key); }
        // A failed branch may have met a visiting ancestor which a later alternative can prove.
        // Reuse successes only; a cycle-truncated false must not poison another native path.
        if (result) verified.put(key, true);
        return result;
    }

    private void validateBindings(List<Binding> bindings) {
        if (bindings.isEmpty()) return;
        boolean previous = filtering; filtering = false;
        try { validator.accept(bindings); } finally { filtering = previous; }
    }

    private boolean criteria(Object object, String method) {
        boolean previous = filtering; filtering = true;
        try { return (boolean) call(object, method, teamType, team); } finally { filtering = previous; }
    }

    private boolean canStart(Object quest) {
        boolean previous = filtering; filtering = true;
        try { return (boolean) call(team, "canStartTasks", questType, quest); } finally { filtering = previous; }
    }

    private boolean nativeState(Object object, boolean started) {
        return (boolean) call(team, started ? "isStarted" : "isCompleted", objectType, object);
    }

    private List<?> preceding(Object task, Object quest) {
        if (!(boolean) get(quest, "getRequireSequentialTasks")) return List.of();
        var ordered = (List<?>) get(quest, "getTasksAsList");
        int index = ordered.indexOf(task);
        if (index < 0) throw new IllegalStateException("Native task is absent from its quest");
        return ordered.subList(0, index);
    }

    private static List<?> dependencies(Object quest) {
        try (var stream = (Stream<?>) get(quest, "streamDependencies")) { return stream.toList(); }
    }

    private List<?> children(Object object) {
        return ((Collection<?>) get(object, "getChildren")).stream().filter(objectType::isInstance).toList();
    }

    private static String code(Object object) { return (String) get(object, "getCodeString"); }
    private static Object get(Object object, String method) { return call(object, method, null, null); }
    private static Object call(Object object, String method, Class<?> parameter, Object value) {
        try {
            return parameter == null ? object.getClass().getMethod(method).invoke(object)
                    : object.getClass().getMethod(method, parameter).invoke(object, value);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw new IllegalStateException("Native reward evidence evaluation failed", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Native reward evidence contract is unavailable", failure);
        }
    }
}
