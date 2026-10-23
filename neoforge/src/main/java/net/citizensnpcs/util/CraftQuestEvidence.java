package net.citizensnpcs.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import net.citizensnpcs.api.event.CraftingOutcomeEvent;
import net.citizensnpcs.api.event.CraftingResultEvent;
import net.citizensnpcs.api.util.PermissionUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** Explicit recipe-owned CustomTask bindings. FTB owns quest state, the journal owns accepted evidence. */
public final class CraftQuestEvidence implements AutoCloseable {
    public static final String ADMIN_PERMISSION = "citizens.admin";
    private static final Gson JSON = new Gson();
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(CraftQuestEvidence.class);
    private static CraftQuestEvidence current;
    private final MinecraftServer server;
    private final Path directory;
    private final QuestEvidenceJournal journal;
    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private boolean closed;
    private boolean failed;

    public record Binding(int schema, UUID epoch, String taskId, String questId, String recipeId,
            String recipeRevision, long maximum, boolean repeatable, UUID operatorId, long createdAt) {
        public Binding {
            if (schema != 1 || epoch == null || operatorId == null || recipeId == null || maximum <= 0 || createdAt < 0
                    || recipeRevision == null || !recipeRevision.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid craft binding");
            taskId = id(taskId); questId = id(questId);
            if (!ResourceLocation.parse(recipeId).toString().equals(recipeId)) throw new IllegalArgumentException("Recipe ID must include its namespace");
        }
    }

    public static CraftQuestEvidence current(MinecraftServer server) {
        if (current == null || current.server != server) throw new IllegalStateException("Craft evidence is unavailable");
        current.active(); return current;
    }

    public static CraftQuestEvidence open(MinecraftServer server, Path directory) throws IOException {
        return new CraftQuestEvidence(server, directory);
    }

    private CraftQuestEvidence(MinecraftServer server, Path directory) throws IOException {
        if (!server.isSameThread()) throw new IllegalStateException("Craft evidence requires the server thread");
        this.server = server; this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory.resolve("bindings"));
        journal = QuestEvidenceJournal.open(this.directory.resolve("journal"), this::loadBindings);
        try {
            // Register only after every persistent record is validated. Opening never changes native progress.
            NeoForge.EVENT_BUS.register(this);
        } catch (RuntimeException | Error failure) {
            try { journal.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private void loadBindings() throws IOException {
        try (var files = Files.list(directory.resolve("bindings"))) {
            for (Path path : files.sorted().toList()) {
                String name = path.getFileName().toString();
                if (name.contains(".json.tmp-")) throw new IOException("Incomplete craft binding requires review: " + name);
                if (!name.endsWith(".json")) continue;
                try {
                    Binding binding = JournalJson.decode(JSON, JournalJson.parse(Files.readString(path)), Binding.class);
                    if (!name.equals(binding.taskId() + ".json") || bindings.putIfAbsent(binding.taskId(), binding) != null)
                        throw new IllegalArgumentException("Binding identity mismatch");
                } catch (RuntimeException invalid) { throw new IOException("Invalid craft binding: " + name, invalid); }
            }
        }
    }

    /** Binds a current recipe definition once. Rebinding requires an explicit content migration. */
    public Binding bind(CommandSourceStack source, String taskId, ResourceLocation recipeId) throws IOException {
        authorize(source);
        var player = source.getPlayer();
        if (player == null) throw new IllegalArgumentException("Binding needs an online operator's native team context");
        var target = FtbQuestTasks.evidenceTarget(player, taskId);
        if (CommerceQuestTasks.owns(server, target.state().taskId())) throw new IllegalStateException("Task is already owned by commerce receipts");
        if (!target.visible() || target.state().maximum() <= 0) throw new IllegalArgumentException("Task is unavailable");
        var recipe = CraftingRecipes.find(server, recipeId).orElseThrow(() -> new IllegalArgumentException("Recipe is unavailable"));
        if (!recipe.tableEvidenceSupported() && !(recipe.assembly() != null && recipe.assembly().evidenceSupported()) && (recipe.processing() == null
                || !CreateMachineEvidence.supports(recipe.processing().process())
                || (recipe.output().isEmpty() && recipe.processing().fluidResults().isEmpty())))
            throw new IllegalArgumentException("Recipe evidence is unsupported");
        Binding previous = bindings.get(target.state().taskId());
        if (previous != null) {
            if (!previous.recipeId().equals(recipeId.toString()) || !previous.recipeRevision().equals(recipe.revision())
                    || !previous.questId().equals(target.questId()) || previous.maximum() != target.state().maximum()
                    || previous.repeatable() != target.repeatable()) throw new IllegalStateException("Existing binding requires explicit migration");
            return previous;
        }
        if (target.state().progress() != 0 || target.state().completed()) throw new IllegalStateException("Task already has unowned progress");
        var binding = new Binding(1, UUID.randomUUID(), target.state().taskId(), target.questId(), recipeId.toString(),
                recipe.revision(), target.state().maximum(), target.repeatable(), player.getUUID(), System.currentTimeMillis());
        Path path = directory.resolve("bindings").resolve(binding.taskId() + ".json");
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            if (Files.exists(path)) throw new IOException("Immutable binding already exists");
            try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(JSON.toJson(binding) + "\n");
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException failure) {
            failed = true;
            throw failure;
        }
        bindings.put(binding.taskId(), binding); return binding;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void observed(CraftingResultEvent event) {
        if (closed || event.getPlayer().getServer() != server) return;
        var observation = event.getObservation();
        admit(event.getPlayer(), observation.id(), observation.recipe(), observation.output().getCount(),
                CraftingEvidence.encode(observation, event.getPlayer().registryAccess()));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void machineObserved(net.citizensnpcs.api.event.MachineResultEvent event) {
        if (closed || event.getBeneficiary().getServer() != server) return;
        var observation = event.getObservation();
        long units = 0;
        for (var output : observation.assembly() == null ? observation.rolledOutputs() : observation.assembly().finalOutputs())
            if (net.minecraft.world.item.ItemStack.isSameItemSameComponents(output, observation.recipe().output()))
                units = Math.addExact(units, output.getCount());
        if (observation.recipe().output().isEmpty() && (observation.basin() != null || observation.fluid() != null)
                && !observation.recipe().processing().fluidResults().isEmpty()) {
            var primary = observation.recipe().processing().fluidResults().getFirst();
            for (var output : observation.basin() != null ? observation.basin().fluidOutputs() : observation.fluid().fluidOutputs())
                if (net.neoforged.neoforge.fluids.FluidStack.isSameFluidSameComponents(output, primary))
                    units = Math.addExact(units, output.getAmount());
        }
        if (units == 0) return;
        admit(event.getBeneficiary(), observation.id(), observation.recipe(), units,
                CreateMachineEvidence.encode(observation, event.getBeneficiary().registryAccess()));
    }

    private void admit(net.minecraft.server.level.ServerPlayer actor, UUID observationId, CraftingRecipes.Snapshot recipe,
            long units, String evidence) {
        for (Binding binding : List.copyOf(bindings.values())) {
            if (!binding.recipeId().equals(recipe.id().toString()) || !binding.recipeRevision().equals(recipe.revision())) continue;
            try {
                // Observation identity survives team and native repeat-generation changes. Replaying an old
                // provider notification must never admit the same output to the beneficiary's new scope.
                var previous = journal.previous(observationId, binding.taskId()).orElse(null);
                if (previous != null) {
                    var admitted = previous.admission();
                    if (!admitted.actorId().equals(actor.getUUID()) || admitted.units() != units
                            || !admitted.recipeRevision().equals(recipe.revision()) || !admitted.evidence().equals(evidence))
                        throw new IllegalStateException("Observation identity changed its admitted payload");
                    continue;
                }
                active(); var target = FtbQuestTasks.evidenceTarget(actor, binding.taskId());
                validate(binding, target);
                if (!target.visible() || !target.state().available() || target.state().completed()) continue;
                var generation = journal.generation(new QuestEvidenceJournal.Scope(binding.epoch(), target.state().teamId(), binding.questId()), target.cycle());
                long applied = Math.min(binding.maximum(), journal.appliedTotal(generation.id(), binding.taskId()));
                if (target.state().progress() != applied) throw new IllegalStateException("Native progress needs evidence reconciliation");
                if (journal.hasReview(generation.id(), binding.taskId()))
                    throw new IllegalStateException("Uncertain craft evidence must be reviewed first");
                journal.admit(new QuestEvidenceJournal.Admission(observationId, generation.id(), binding.taskId(), actor.getUUID(),
                        binding.recipeId(), binding.recipeRevision(), units, evidence));
            } catch (IOException | RuntimeException failure) {
                LOGGER.error("Craft evidence admission failed for task {} and observation {}", binding.taskId(), observationId, failure);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void returned(CraftingOutcomeEvent event) {
        returned(event.getObservation().id(), QuestEvidenceJournal.Outcome.valueOf(event.getOutcome().name()));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void machineReturned(net.citizensnpcs.api.event.MachineOutcomeEvent event) {
        returned(event.getObservation().id(), QuestEvidenceJournal.Outcome.valueOf(event.getOutcome().name()));
    }

    private void returned(UUID observationId, QuestEvidenceJournal.Outcome outcome) {
        if (closed) return;
        List<QuestEvidenceJournal.Entry> candidates;
        try {
            active(); candidates = journal.observation(observationId);
        } catch (RuntimeException failure) {
            LOGGER.error("Craft evidence journal is unavailable for {}", observationId, failure); return;
        }
        for (var entry : candidates) {
            if (!entry.admission().observationId().equals(observationId) || entry.state() != QuestEvidenceJournal.State.PREPARED) continue;
            try {
                var updated = journal.outcome(entry.id(), outcome);
                if (updated.state() == QuestEvidenceJournal.State.CONFIRMED) project(updated.admission().generationId(), updated.admission().taskId());
            } catch (IOException | RuntimeException failure) {
                LOGGER.error("Craft evidence outcome/projection needs review for task {} and observation {}",
                        entry.admission().taskId(), observationId, failure);
            }
        }
    }

    /** Explicit recovery retries absolute totals, including when FTB saved progress before journal APPLIED. */
    public int reconcile(CommandSourceStack source) throws IOException {
        authorize(source); int count = 0;
        var generations = new java.util.HashMap<UUID, QuestEvidenceJournal.Generation>();
        for (var generation : journal.generations()) generations.put(generation.id(), generation);
        var pending = new LinkedHashMap<Projection, Exception>();
        for (var entry : journal.entries()) if (entry.state() == QuestEvidenceJournal.State.CONFIRMED) {
            var generation = java.util.Objects.requireNonNull(generations.get(entry.admission().generationId()), "Missing evidence generation");
            pending.put(new Projection(generation.id(), entry.admission().taskId(), generation.scope().teamId(), generation.nativeCycle()), null);
        }
        boolean advanced;
        do {
            advanced = false;
            for (var scope : List.copyOf(pending.keySet())) {
                try {
                    project(scope.generationId(), scope.taskId());
                    pending.remove(scope); advanced = true; count++;
                } catch (IOException | RuntimeException failure) { pending.put(scope, failure); }
            }
            // Native completion can unlock another scope. Successful scopes leave the batch,
            // and an iteration without progress stops instead of retrying invalid targets forever.
        } while (advanced && !pending.isEmpty());
        if (!pending.isEmpty()) {
            IllegalStateException reported = null;
            for (var failure : pending.entrySet()) {
                var scope = failure.getKey();
                var contextual = new IllegalStateException("Craft projection remains pending for task " + scope.taskId()
                        + ", team " + scope.teamId() + ", cycle " + scope.cycle() + ", generation " + scope.generationId(), failure.getValue());
                if (reported == null) reported = contextual; else reported.addSuppressed(contextual);
            }
            throw reported;
        }
        return count;
    }

    private record Projection(UUID generationId, String taskId, UUID teamId, int cycle) { }

    public QuestEvidenceJournal.Entry resolve(CommandSourceStack source, String entryId, boolean accept, String explanation) throws IOException {
        authorize(source);
        if (source.getPlayer() == null) throw new IllegalArgumentException("Evidence review requires an authenticated operator UUID");
        var entry = journal.resolve(entryId, source.getPlayer().getUUID(), explanation, accept);
        if (accept) project(entry.admission().generationId(), entry.admission().taskId());
        return entry;
    }

    public List<Binding> bindings(CommandSourceStack source) { authorize(source); return List.copyOf(bindings.values()); }
    public List<QuestEvidenceJournal.Entry> entries(CommandSourceStack source) { authorize(source); return journal.entries(); }

    private void project(UUID generationId, String taskId) throws IOException {
        Binding binding = bindings.get(taskId);
        if (binding == null) throw new IllegalStateException("Admitted binding is unavailable");
        var generation = journal.generation(generationId).orElseThrow();
        if (!generation.scope().epoch().equals(binding.epoch()) || !generation.scope().questId().equals(binding.questId()))
            throw new IllegalStateException("Evidence generation belongs to a different binding");
        var target = FtbQuestTasks.evidenceTarget(server, generation.scope().teamId(), taskId);
        validate(binding, target);
        if (target.cycle() != generation.nativeCycle()) throw new IllegalStateException("Native quest cycle changed; historical evidence requires review");
        long absolute = Math.min(binding.maximum(), journal.confirmedTotal(generationId, taskId));
        long applied = Math.min(binding.maximum(), journal.appliedTotal(generationId, taskId));
        // Native callbacks can complete partially before throwing. Only the old or exact desired total is safe to replay.
        if (target.state().progress() != applied && target.state().progress() != absolute)
            throw new IllegalStateException("Native progress differs from admitted totals; reconciliation is required");
        FtbQuestTasks.projectEvidence(server, generation.scope().teamId(), taskId, binding.questId(), generation.nativeCycle(),
                binding.maximum(), target.state().progress(), absolute);
        for (var entry : journal.confirmed(generationId, taskId)) journal.applied(entry.id());
    }

    private static void validate(Binding binding, FtbQuestTasks.EvidenceTarget target) {
        if (!binding.questId().equals(target.questId()) || binding.maximum() != target.state().maximum()
                || binding.repeatable() != target.repeatable()) throw new IllegalStateException("Native binding definition changed");
    }

    private void authorize(CommandSourceStack source) {
        active();
        if (source == null || source.getServer() != server || !PermissionUtil.hasPermission(source, ADMIN_PERMISSION))
            throw new IllegalStateException("Craft evidence administration is not permitted");
        if (source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                && (player.hasDisconnected() || server.getPlayerList().getPlayer(player.getUUID()) != player))
            throw new IllegalStateException("Operator session has retired");
    }
    private void active() {
        if (closed || failed || !server.isSameThread()) throw new IllegalStateException("Craft evidence requires its active server thread");
    }
    private static String id(String value) { return String.format(java.util.Locale.ROOT, "%016X", FtbQuestTasks.parseId(value)); }

    @Override public void close() throws IOException {
        if (!server.isSameThread()) throw new IllegalStateException("Craft evidence requires the server thread");
        if (closed) return;
        closed = true; NeoForge.EVENT_BUS.unregister(this); journal.close();
    }

    @EventBusSubscriber(modid = "citizens")
    public static final class Lifecycle {
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void start(ServerStartedEvent event) {
            try { current = open(event.getServer(), event.getServer().getWorldPath(LevelResource.ROOT).resolve("citizens/quest-evidence")); }
            catch (IOException | RuntimeException failure) { current = null; LOGGER.error("Craft evidence is unavailable; retained records need review", failure); }
        }
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void stop(ServerStoppingEvent event) {
            var retired = current; current = null;
            if (retired != null) try { retired.close(); }
            catch (IOException failure) { LOGGER.error("Craft evidence close failed", failure); }
        }
    }
}
