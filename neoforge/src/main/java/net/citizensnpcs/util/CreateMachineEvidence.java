package net.citizensnpcs.util;

import java.util.List;
import java.util.UUID;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.citizensnpcs.api.event.MachineResultEvent;
import net.citizensnpcs.api.event.MachineOutcomeEvent;
import net.citizensnpcs.api.util.PermissionUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Pinned native Create machine completion observations with explicitly assigned online beneficiaries. */
public final class CreateMachineEvidence {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(CreateMachineEvidence.class);
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, "citizens");
    private static final java.util.function.Supplier<AttachmentType<java.util.Optional<Attribution>>> ATTRIBUTION = ATTACHMENTS.register(
            "machine_evidence_attribution", () -> AttachmentType.builder(() -> java.util.Optional.<Attribution>empty())
                    .serialize(Attribution.CODEC.optionalFieldOf("assignment").codec()).build());
    private static final ThreadLocal<java.util.Deque<Scope>> SCOPES = ThreadLocal.withInitial(java.util.ArrayDeque::new);
    private CreateMachineEvidence() { }
    public static void register(IEventBus bus) { ATTACHMENTS.register(bus); }

    /** Assignment is a pack attribution rule, not Create's temporary advancement owner or block access authority. */
    public record Attribution(UUID machineId, UUID beneficiaryId, UUID operatorId, long assignedAt) {
        public static final Codec<Attribution> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                net.minecraft.core.UUIDUtil.STRING_CODEC.fieldOf("machineId").forGetter(Attribution::machineId),
                net.minecraft.core.UUIDUtil.STRING_CODEC.fieldOf("beneficiaryId").forGetter(Attribution::beneficiaryId),
                net.minecraft.core.UUIDUtil.STRING_CODEC.fieldOf("operatorId").forGetter(Attribution::operatorId),
                Codec.LONG.fieldOf("assignedAt").forGetter(Attribution::assignedAt)).apply(instance, Attribution::new));
        public Attribution {
            java.util.Objects.requireNonNull(machineId); java.util.Objects.requireNonNull(beneficiaryId); java.util.Objects.requireNonNull(operatorId);
            if (assignedAt < 0) throw new IllegalArgumentException("Invalid attribution timestamp");
        }
    }

    public enum Source {
        CREATE_MILLSTONE("com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity"),
        CREATE_SAW("com.simibubi.create.content.kinetics.saw.SawBlockEntity"),
        CREATE_PRESS("com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity"),
        CREATE_CRUSHING_WHEELS("com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity");
        private final String implementation;
        Source(String implementation) { this.implementation = implementation; }
        public boolean supports(CreateProcessingRecipes.Process process) {
            return switch (this) {
                case CREATE_MILLSTONE -> process == CreateProcessingRecipes.Process.MILLING;
                case CREATE_SAW -> process == CreateProcessingRecipes.Process.CUTTING;
                case CREATE_PRESS -> process == CreateProcessingRecipes.Process.PRESSING;
                case CREATE_CRUSHING_WHEELS -> process == CreateProcessingRecipes.Process.CRUSHING || process == CreateProcessingRecipes.Process.MILLING;
            };
        }
    }
    public static boolean supports(CreateProcessingRecipes.Process process) {
        return java.util.Arrays.stream(Source.values()).anyMatch(source -> source.supports(process));
    }
    public record Observation(UUID id, Attribution attribution, ResourceLocation dimension, BlockPos position, long serverTick,
            Source source, CraftingRecipes.Snapshot recipe, ItemStack consumedInput, ItemStack remainingInput, ItemStack nativeRemainder,
            List<ItemStack> beforeOutput, List<ItemStack> rolledOutputs, List<ItemStack> additionalRemainders, UUID inputEntityId) {
        public Observation(UUID id, Attribution attribution, ResourceLocation dimension, BlockPos position, long serverTick,
                Source source, CraftingRecipes.Snapshot recipe, ItemStack consumedInput, ItemStack remainingInput, ItemStack nativeRemainder,
                List<ItemStack> beforeOutput, List<ItemStack> rolledOutputs, List<ItemStack> additionalRemainders) {
            this(id, attribution, dimension, position, serverTick, source, recipe, consumedInput, remainingInput, nativeRemainder,
                    beforeOutput, rolledOutputs, additionalRemainders, null);
        }
        public Observation(UUID id, Attribution attribution, ResourceLocation dimension, BlockPos position, long serverTick,
                Source source, CraftingRecipes.Snapshot recipe, ItemStack consumedInput, ItemStack remainingInput, ItemStack nativeRemainder,
                List<ItemStack> beforeOutput, List<ItemStack> rolledOutputs) {
            this(id, attribution, dimension, position, serverTick, source, recipe, consumedInput, remainingInput, nativeRemainder, beforeOutput, rolledOutputs, List.of(), null);
        }
        public Observation {
            position = position.immutable(); consumedInput = consumedInput.copy(); remainingInput = remainingInput.copy();
            nativeRemainder = nativeRemainder.copy(); beforeOutput = copies(beforeOutput); rolledOutputs = copies(rolledOutputs); additionalRemainders = copies(additionalRemainders);
        }
        @Override public ItemStack consumedInput() { return consumedInput.copy(); }
        @Override public ItemStack remainingInput() { return remainingInput.copy(); }
        @Override public ItemStack nativeRemainder() { return nativeRemainder.copy(); }
        @Override public List<ItemStack> beforeOutput() { return copies(beforeOutput); }
        @Override public List<ItemStack> rolledOutputs() { return copies(rolledOutputs); }
        @Override public List<ItemStack> additionalRemainders() { return copies(additionalRemainders); }
    }

    /** Authenticated administrators assign an existing supported machine; replacement requires an explicit clear. */
    public static Attribution assign(CommandSourceStack source, UUID beneficiaryId, BlockPos position) {
        authorize(source); var level = source.getLevel();
        if (!level.hasChunkAt(position)) throw new IllegalArgumentException("Machine chunk is not loaded");
        var machine = level.getBlockEntity(position);
        if (machine == null || source(machine) == null) throw new IllegalArgumentException("Supported machine is unavailable");
        if (attribution(machine) != null) throw new IllegalStateException("Machine already has an assignment");
        var beneficiary = source.getServer().getPlayerList().getPlayer(beneficiaryId);
        if (beneficiary == null || beneficiary.hasDisconnected() || beneficiary instanceof net.neoforged.neoforge.common.util.FakePlayer)
            throw new IllegalArgumentException("Beneficiary must be an online player");
        var assignment = new Attribution(UUID.randomUUID(), beneficiaryId, source.getPlayer().getUUID(), System.currentTimeMillis());
        machine.setData(ATTRIBUTION, java.util.Optional.of(assignment)); machine.setChanged(); return assignment;
    }

    public static void clear(CommandSourceStack source, BlockPos position) {
        authorize(source);
        if (!source.getLevel().hasChunkAt(position)) throw new IllegalArgumentException("Machine chunk is not loaded");
        var machine = source.getLevel().getBlockEntity(position);
        if (machine == null || source(machine) == null) throw new IllegalArgumentException("Supported machine is unavailable");
        machine.removeData(ATTRIBUTION); machine.setChanged();
    }

    public static Attribution attribution(BlockEntity machine) {
        if (!(machine.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread())
            throw new IllegalStateException("Machine attribution requires its server thread");
        return machine.getExistingData(ATTRIBUTION).flatMap(value -> value).orElse(null);
    }

    /** Called only by pinned process wrappers; native matching/consumption/RNG are unchanged. */
    public static void duringProcess(BlockEntity machine, Runnable original) {
        Scope scope = new Scope(machine);
        SCOPES.get().push(scope); boolean returned = false;
        try { original.run(); returned = true; }
        finally {
            var scopes = SCOPES.get(); if (scopes.pop() != scope) throw new IllegalStateException("Machine observation scope changed");
            if (scopes.isEmpty()) SCOPES.remove();
            if (scope.observation != null) {
                if (scope.worldInput != null && returned) verifyWorld(scope);
                var outcome = !returned ? MachineOutcomeEvent.Outcome.INTERRUPTED
                        : scope.verified ? MachineOutcomeEvent.Outcome.RETURNED : MachineOutcomeEvent.Outcome.UNCONFIRMED;
                try { NeoForge.EVENT_BUS.post(new MachineOutcomeEvent(scope.observation, outcome)); }
                catch (RuntimeException | LinkageError failure) { LOGGER.error("Machine outcome listener failed for {}", scope.observation.id(), failure); }
            }
        }
    }

    /** The press owns the operation; RecipeApplier also serves unrelated automation, which remains unobserved. */
    public static boolean duringWorldPress(BlockEntity machine, net.minecraft.world.entity.item.ItemEntity input,
            boolean simulate, java.util.function.BooleanSupplier original) {
        if (simulate || !live(machine) || source(machine) != Source.CREATE_PRESS) return original.getAsBoolean();
        final boolean[] result = { false };
        duringProcess(machine, () -> {
            Scope scope = SCOPES.get().peek();
            if (input.level() == machine.getLevel() && input.isAlive()
                    && ((ServerLevel) input.level()).getEntity(input.getUUID()) == input) {
                scope.worldInput = input; scope.worldBefore = input.getItem().copy();
            }
            result[0] = original.getAsBoolean();
        });
        return result[0];
    }

    /** Observe the native input to RecipeApplier before its results or callbacks can mutate it. */
    public static void beforeWorldRecipe(ItemStack input, Object recipe) {
        Scope scope = SCOPES.get().peek();
        if (scope == null || scope.worldInput == null || scope.input != null || input.isEmpty()) return;
        scope.input = input.copy(); scope.batchRecipe = recipe;
        if (!ItemStack.isSameItemSameComponents(input, scope.worldBefore) || input.getCount() > scope.worldBefore.getCount()) {
            scope.invalidBatch = true; return;
        }
        scope.remainingInput = scope.worldBefore.copyWithCount(scope.worldBefore.getCount() - input.getCount());
        scope.beforeOutput = List.of();
    }

    /** RecipeApplier has completed all actual rolls and remainder callbacks, before native world storage. */
    public static void worldRecipeResults(List<ItemStack> results) {
        Scope scope = SCOPES.get().peek();
        if (scope == null || scope.worldInput == null || scope.input == null || scope.invalidBatch
                || scope.observation != null || scope.rolls != scope.input.getCount()) return;
        try {
            var expected = new java.util.ArrayList<>(scope.batchOutputs); expected.addAll(scope.batchRemainders);
            if (!equalMultiset(expected, results)) return;
            var selected = currentRecipe(scope.machine, scope.batchRecipe); if (selected == null) return;
            var assignment = attribution(scope.machine); var actor = beneficiary(scope.machine, assignment); if (actor == null) return;
            var level = (ServerLevel) scope.machine.getLevel();
            scope.observation = new Observation(UUID.randomUUID(), assignment, level.dimension().location(), scope.machine.getBlockPos(),
                    level.getServer().getTickCount(), Source.CREATE_PRESS, selected, scope.input, scope.remainingInput,
                    scope.batchRemainders.isEmpty() ? ItemStack.EMPTY : scope.batchRemainders.getFirst(), List.of(), scope.batchOutputs,
                    scope.batchRemainders.size() < 2 ? List.of() : scope.batchRemainders.subList(1, scope.batchRemainders.size()), scope.worldInput.getUUID());
            NeoForge.EVENT_BUS.post(new MachineResultEvent(actor, scope.observation));
        } catch (RuntimeException | LinkageError failure) { LOGGER.error("World press result observation failed", failure); }
    }

    /** Capture only the actual native output entities and insertion results, without searching nearby items. */
    public static void worldEntityAdded(net.minecraft.world.entity.Entity entity, boolean accepted) {
        Scope scope = SCOPES.get().peek();
        if (scope == null || scope.worldInput == null || scope.observation == null) return;
        if (!accepted || !(entity instanceof net.minecraft.world.entity.item.ItemEntity item)) { scope.invalidBatch = true; return; }
        scope.worldOutputs.add(item);
    }

    private static void verifyWorld(Scope scope) {
        try {
            var expected = new java.util.ArrayList<>(scope.observation.rolledOutputs()); expected.add(scope.observation.nativeRemainder());
            expected.addAll(scope.observation.additionalRemainders()); expected.add(scope.remainingInput);
            var level = (ServerLevel) scope.machine.getLevel(); var actual = new java.util.ArrayList<ItemStack>();
            if (scope.worldInput.isAlive() && level.getEntity(scope.worldInput.getUUID()) == scope.worldInput) actual.add(scope.worldInput.getItem().copy());
            else if (!scope.remainingInput.isEmpty()) return;
            for (var output : scope.worldOutputs) {
                if (!output.isAlive() || output.level() != level || level.getEntity(output.getUUID()) != output) return;
                actual.add(output.getItem().copy());
            }
            scope.verified = live(scope.machine) && !scope.invalidBatch && equalMultiset(expected, actual);
        } catch (RuntimeException | LinkageError failure) { LOGGER.error("World press output verification failed", failure); }
    }

    /** Save the whole native batch before ProcessingInventory.clear; no alternative recipe is selected. */
    public static void beforeBatchClear() {
        Scope scope = SCOPES.get().peek();
        if (scope == null || scope.input != null || source(scope.machine) == Source.CREATE_MILLSTONE) return;
        try {
            var before = inventory(scope.machine, "inventory");
            if (before.isEmpty() || before.getFirst().isEmpty()) return;
            scope.input = before.getFirst().copy(); scope.remainingInput = ItemStack.EMPTY;
            scope.beforeOutput = copies(before.subList(1, before.size())); scope.remainder = ItemStack.EMPTY;
        } catch (ReflectiveOperationException | RuntimeException failure) { LOGGER.error("Batch input observation failed", failure); }
    }

    /** Retain every actual roll before the provider merges or mutates its result stacks. */
    public static void batchRolled(Object recipe, List<ItemStack> outputs) {
        Scope scope = SCOPES.get().peek(); if (scope == null || scope.input == null) return;
        if (scope.batchRecipe != null && scope.batchRecipe != recipe) { scope.invalidBatch = true; return; }
        try {
            scope.batchRecipe = recipe; scope.rolls++; scope.batchOutputs.addAll(copies(outputs));
        } catch (RuntimeException | LinkageError failure) {
            scope.invalidBatch = true; LOGGER.error("Batch roll observation failed", failure);
        }
    }
    public static void batchRemainder(ItemStack remainder) {
        Scope scope = SCOPES.get().peek(); if (scope == null) return;
        try { if (!remainder.isEmpty()) scope.batchRemainders.add(remainder.copy()); }
        catch (RuntimeException | LinkageError failure) {
            scope.invalidBatch = true; LOGGER.error("Batch remainder observation failed", failure);
        }
    }
    /** All native rolls/remainders are complete at the first output-slot write. Persist evidence before storage. */
    public static void beforeBatchStorage() {
        Scope scope = SCOPES.get().peek();
        if (scope == null || scope.input == null || scope.observation != null || scope.batchRecipe == null || scope.invalidBatch
                || scope.rolls != scope.input.getCount() || !(scope.machine.getLevel() instanceof ServerLevel level)) return;
        try {
            var selected = currentRecipe(scope.machine, scope.batchRecipe); if (selected == null) return;
            var assignment = attribution(scope.machine); ServerPlayer actor = beneficiary(scope.machine, assignment); if (actor == null) return;
            var remainders = scope.batchRemainders;
            scope.observation = new Observation(UUID.randomUUID(), assignment, level.dimension().location(), scope.machine.getBlockPos(),
                    level.getServer().getTickCount(), source(scope.machine), selected, scope.input, ItemStack.EMPTY,
                    remainders.isEmpty() ? ItemStack.EMPTY : remainders.getFirst(), scope.beforeOutput, scope.batchOutputs,
                    remainders.size() < 2 ? List.of() : remainders.subList(1, remainders.size()));
            NeoForge.EVENT_BUS.post(new MachineResultEvent(actor, scope.observation));
        } catch (RuntimeException | LinkageError failure) { LOGGER.error("Batch result observation failed", failure); }
    }
    public static void afterBatchStorage() {
        Scope scope = SCOPES.get().peek(); if (scope == null || scope.observation == null) return;
        try {
            var expected = new java.util.ArrayList<>(scope.observation.rolledOutputs());
            if (!scope.observation.nativeRemainder().isEmpty()) expected.add(scope.observation.nativeRemainder());
            expected.addAll(scope.observation.additionalRemainders());
            var stored = inventory(scope.machine, "inventory");
            scope.verified = live(scope.machine) && scope.beforeOutput.stream().allMatch(ItemStack::isEmpty)
                    && stored.getFirst().isEmpty() && equalMultiset(expected, stored);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { LOGGER.error("Batch inventory verification failed", failure); }
    }

    /** Saves the exact pre-consumption input, without re-running matching or selecting a recipe. */
    public static void beforeConsume(ItemStack input, int amount) {
        Scope scope = SCOPES.get().peek();
        if (scope == null || amount != 1 || input.isEmpty()) return;
        scope.input = input.copyWithCount(1); scope.remainingInput = input.copyWithCount(input.getCount() - 1);
    }

    /** Observe the already computed native remainder; never invoke the item's callback again. */
    public static void remainder(ItemStack remainder) {
        Scope scope = SCOPES.get().peek();
        if (scope != null) scope.remainder = remainder.copy();
    }

    /** Observes the actual native roll once, after consumption and before insertion. */
    public static void rolled(Object recipe, List<ItemStack> outputs) {
        Scope scope = SCOPES.get().peek();
        if (scope == null || scope.input == null || scope.remainder == null || scope.observation != null
                || !(scope.machine.getLevel() instanceof ServerLevel level)) return;
        try {
            var server = level.getServer(); if (!server.isSameThread() || scope.machine.isRemoved()
                    || level.getBlockEntity(scope.machine.getBlockPos()) != scope.machine) return;
            var attribution = attribution(scope.machine); if (attribution == null) return;
            ServerPlayer beneficiary = server.getPlayerList().getPlayer(attribution.beneficiaryId());
            if (beneficiary == null || beneficiary.hasDisconnected() || beneficiary instanceof net.neoforged.neoforge.common.util.FakePlayer) return;
            // Identity must be the actual current holder. Create can retain a recipe across resource reload.
            var snapshot = currentRecipe(scope.machine, recipe); if (snapshot == null) return;
            scope.beforeOutput = inventory(scope.machine, "outputInv");
            scope.observation = new Observation(UUID.randomUUID(), attribution, level.dimension().location(), scope.machine.getBlockPos(),
                    server.getTickCount(), Source.CREATE_MILLSTONE, snapshot, scope.input, scope.remainingInput, scope.remainder, scope.beforeOutput, outputs);
            // Native output must survive full/partial capacity handling before an outcome can confirm it.
            NeoForge.EVENT_BUS.post(new MachineResultEvent(beneficiary, scope.observation));
        } catch (RuntimeException | ReflectiveOperationException | LinkageError failure) {
            LOGGER.error("Machine observation failed at {}", scope.machine.getBlockPos(), failure);
        }
    }

    /** After native insertion, check the exact component-sensitive inventory delta; remainders are separate. */
    public static void afterInsertion() {
        Scope scope = SCOPES.get().peek();
        if (scope == null || scope.observation == null) return;
        try {
            var expected = new java.util.ArrayList<>(scope.beforeOutput.stream().filter(stack -> !stack.isEmpty()).map(ItemStack::copy).toList());
            expected.addAll(scope.observation.rolledOutputs());
            if (scope.remainder == null) return;
            if (!scope.remainder.isEmpty()) expected.add(scope.remainder);
            scope.verified = !scope.machine.isRemoved() && scope.machine.getLevel().getBlockEntity(scope.machine.getBlockPos()) == scope.machine
                    && equalMultiset(expected, inventory(scope.machine, "outputInv"))
                    && equalMultiset(List.of(scope.remainingInput), inventory(scope.machine, "inputInv"));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { LOGGER.error("Machine inventory verification failed", failure); }
    }

    public static String encode(Observation observation, net.minecraft.core.HolderLookup.Provider registries) {
        var ops = registries.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
        var root = new com.google.gson.JsonObject();
        root.addProperty("id", observation.id().toString()); root.addProperty("source", observation.source().name());
        root.add("attribution", Attribution.CODEC.encodeStart(ops, observation.attribution()).getOrThrow());
        root.addProperty("dimension", observation.dimension().toString()); root.addProperty("x", observation.position().getX());
        root.addProperty("y", observation.position().getY()); root.addProperty("z", observation.position().getZ()); root.addProperty("serverTick", observation.serverTick());
        root.addProperty("recipeId", observation.recipe().id().toString()); root.addProperty("recipeRevision", observation.recipe().revision());
        if (observation.inputEntityId() != null) root.addProperty("inputEntityId", observation.inputEntityId().toString());
        root.add("recipeDefinition", com.google.gson.JsonParser.parseString(observation.recipe().definition()));
        root.add("processing", CreateProcessingRecipes.encode(observation.recipe().processing(), registries));
        root.add("consumedInput", ItemStack.CODEC.encodeStart(ops, observation.consumedInput()).getOrThrow());
        root.add("remainingInput", ItemStack.OPTIONAL_CODEC.encodeStart(ops, observation.remainingInput()).getOrThrow());
        root.add("nativeRemainder", ItemStack.OPTIONAL_CODEC.encodeStart(ops, observation.nativeRemainder()).getOrThrow());
        var remainders = new com.google.gson.JsonArray();
        for (var stack : observation.additionalRemainders()) remainders.add(ItemStack.CODEC.encodeStart(ops, stack).getOrThrow());
        // Keep the original single-remainder encoding stable for retained observations.
        if (!remainders.isEmpty()) root.add("additionalRemainders", remainders);
        var before = new com.google.gson.JsonArray();
        for (var stack : observation.beforeOutput()) before.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).getOrThrow());
        root.add("beforeOutput", before);
        var outputs = new com.google.gson.JsonArray();
        for (var stack : observation.rolledOutputs()) outputs.add(ItemStack.CODEC.encodeStart(ops, stack).getOrThrow());
        root.add("rolledOutputs", outputs); return root.toString();
    }

    private static List<ItemStack> inventory(BlockEntity machine, String field) throws ReflectiveOperationException {
        var handler = (IItemHandler) machine.getClass().getField(field).get(machine); var items = new java.util.ArrayList<ItemStack>();
        for (int i = 0; i < handler.getSlots(); i++) items.add(handler.getStackInSlot(i).copy()); return items;
    }
    private static boolean equalMultiset(List<ItemStack> expected, List<ItemStack> actual) {
        var remaining = new java.util.ArrayList<>(actual.stream().filter(stack -> !stack.isEmpty()).map(ItemStack::copy).toList());
        for (var stack : expected) {
            if (stack.isEmpty()) continue;
            int needed = stack.getCount();
            for (var stored : remaining) if (ItemStack.isSameItemSameComponents(stack, stored)) {
                int take = Math.min(needed, stored.getCount()); needed -= take; stored.shrink(take);
            }
            if (needed != 0) return false;
        }
        return remaining.stream().allMatch(ItemStack::isEmpty);
    }
    private static Source source(BlockEntity machine) {
        return java.util.Arrays.stream(Source.values()).filter(value -> value.implementation.equals(machine.getClass().getName())).findFirst().orElse(null);
    }
    private static boolean live(BlockEntity machine) {
        return machine.getLevel() instanceof ServerLevel level && level.getServer().isSameThread() && !machine.isRemoved()
                && level.getBlockEntity(machine.getBlockPos()) == machine;
    }
    private static ServerPlayer beneficiary(BlockEntity machine, Attribution assignment) {
        if (!live(machine) || assignment == null) return null;
        var actor = ((ServerLevel) machine.getLevel()).getServer().getPlayerList().getPlayer(assignment.beneficiaryId());
        return actor == null || actor.hasDisconnected() || actor instanceof net.neoforged.neoforge.common.util.FakePlayer ? null : actor;
    }
    private static CraftingRecipes.Snapshot currentRecipe(BlockEntity machine, Object recipe) {
        if (!live(machine)) return null;
        var server = ((ServerLevel) machine.getLevel()).getServer();
        var holders = server.getRecipeManager().getRecipes().stream().filter(holder -> holder.value() == recipe).toList();
        if (holders.size() != 1) return null;
        var snapshot = CraftingRecipes.find(server, holders.getFirst().id()).orElseThrow();
        return snapshot.processing() != null && source(machine).supports(snapshot.processing().process()) ? snapshot : null;
    }
    private static List<ItemStack> copies(List<ItemStack> stacks) { return stacks.stream().map(ItemStack::copy).toList(); }
    private static void authorize(CommandSourceStack source) {
        if (source == null || !source.getServer().isSameThread() || !PermissionUtil.hasPermission(source, "citizens.admin")
                || source.getPlayer() == null || source.getPlayer().hasDisconnected()
                || source.getServer().getPlayerList().getPlayer(source.getPlayer().getUUID()) != source.getPlayer())
            throw new IllegalStateException("Machine assignment requires an authenticated administrator");
    }
    private static final class Scope {
        final BlockEntity machine; ItemStack input; ItemStack remainingInput; ItemStack remainder; List<ItemStack> beforeOutput; Observation observation; boolean verified;
        Object batchRecipe; int rolls; boolean invalidBatch;
        final List<ItemStack> batchOutputs = new java.util.ArrayList<>(), batchRemainders = new java.util.ArrayList<>();
        net.minecraft.world.entity.item.ItemEntity worldInput; ItemStack worldBefore;
        final List<net.minecraft.world.entity.item.ItemEntity> worldOutputs = new java.util.ArrayList<>();
        Scope(BlockEntity machine) { this.machine = machine; }
    }
}
