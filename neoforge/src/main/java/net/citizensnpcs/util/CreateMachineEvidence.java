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
import net.neoforged.neoforge.fluids.FluidStack;
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
        CREATE_MIXER("com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity"),
        CREATE_SPOUT("com.simibubi.create.content.fluids.spout.SpoutBlockEntity"),
        CREATE_ITEM_DRAIN("com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity"),
        CREATE_CRUSHING_WHEELS("com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity");
        private final String implementation;
        Source(String implementation) { this.implementation = implementation; }
        public boolean supports(CreateProcessingRecipes.Process process) {
            return switch (this) {
                case CREATE_MILLSTONE -> process == CreateProcessingRecipes.Process.MILLING;
                case CREATE_SAW -> process == CreateProcessingRecipes.Process.CUTTING;
                case CREATE_PRESS -> process == CreateProcessingRecipes.Process.PRESSING || process == CreateProcessingRecipes.Process.COMPACTING;
                case CREATE_MIXER -> process == CreateProcessingRecipes.Process.MIXING;
                case CREATE_SPOUT -> process == CreateProcessingRecipes.Process.FILLING;
                case CREATE_ITEM_DRAIN -> process == CreateProcessingRecipes.Process.EMPTYING;
                case CREATE_CRUSHING_WHEELS -> process == CreateProcessingRecipes.Process.CRUSHING || process == CreateProcessingRecipes.Process.MILLING;
            };
        }
    }
    public static boolean supports(CreateProcessingRecipes.Process process) {
        return java.util.Arrays.stream(Source.values()).anyMatch(source -> source.supports(process));
    }
    public record Observation(UUID id, Attribution attribution, ResourceLocation dimension, BlockPos position, long serverTick,
            Source source, CraftingRecipes.Snapshot recipe, ItemStack consumedInput, ItemStack remainingInput, ItemStack nativeRemainder,
            List<ItemStack> beforeOutput, List<ItemStack> rolledOutputs, List<ItemStack> additionalRemainders, UUID inputEntityId, BasinData basin, FluidData fluid) {
        public Observation(UUID id, Attribution attribution, ResourceLocation dimension, BlockPos position, long serverTick,
                Source source, CraftingRecipes.Snapshot recipe, ItemStack consumedInput, ItemStack remainingInput, ItemStack nativeRemainder,
                List<ItemStack> beforeOutput, List<ItemStack> rolledOutputs, List<ItemStack> additionalRemainders, UUID inputEntityId, BasinData basin) {
            this(id, attribution, dimension, position, serverTick, source, recipe, consumedInput, remainingInput, nativeRemainder,
                    beforeOutput, rolledOutputs, additionalRemainders, inputEntityId, basin, null);
        }
        public Observation(UUID id, Attribution attribution, ResourceLocation dimension, BlockPos position, long serverTick,
                Source source, CraftingRecipes.Snapshot recipe, ItemStack consumedInput, ItemStack remainingInput, ItemStack nativeRemainder,
                List<ItemStack> beforeOutput, List<ItemStack> rolledOutputs, List<ItemStack> additionalRemainders, UUID inputEntityId) {
            this(id, attribution, dimension, position, serverTick, source, recipe, consumedInput, remainingInput, nativeRemainder,
                    beforeOutput, rolledOutputs, additionalRemainders, inputEntityId, null);
        }
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

    /** Native tank plan for spout filling and item-drain emptying; quantities use millibuckets. */
    public record FluidData(BlockPos holder, List<FluidStack> beforeFluids, List<FluidStack> consumedFluids, List<FluidStack> fluidOutputs) {
        public FluidData {
            holder = holder.immutable(); beforeFluids = fluidCopies(beforeFluids);
            consumedFluids = fluidCopies(consumedFluids); fluidOutputs = fluidCopies(fluidOutputs);
        }
        @Override public List<FluidStack> beforeFluids() { return fluidCopies(beforeFluids); }
        @Override public List<FluidStack> consumedFluids() { return fluidCopies(consumedFluids); }
        @Override public List<FluidStack> fluidOutputs() { return fluidCopies(fluidOutputs); }
    }

    /** Complete multi-input item/fluid plan and actual outputs for native basin operations. */
    public record BasinData(BlockPos holder, List<ItemStack> consumedItems, List<FluidStack> consumedFluids,
            List<FluidStack> beforeFluids, List<FluidStack> fluidOutputs) {
        public BasinData {
            holder = holder.immutable(); consumedItems = copies(consumedItems); consumedFluids = fluidCopies(consumedFluids);
            beforeFluids = fluidCopies(beforeFluids); fluidOutputs = fluidCopies(fluidOutputs);
        }
        @Override public List<ItemStack> consumedItems() { return copies(consumedItems); }
        @Override public List<FluidStack> consumedFluids() { return fluidCopies(consumedFluids); }
        @Override public List<FluidStack> beforeFluids() { return fluidCopies(beforeFluids); }
        @Override public List<FluidStack> fluidOutputs() { return fluidCopies(fluidOutputs); }
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
                if (scope.basin != null && returned) {
                    try { verifyBasin(scope); }
                    catch (RuntimeException | LinkageError failure) { LOGGER.error("Basin output verification failed", failure); }
                }
                if (scope.worldInput != null && returned) verifyWorld(scope);
                if (scope.transportInput != null && returned) {
                    try { verifyTransport(scope); }
                    catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { LOGGER.error("Transport output verification failed", failure); }
                }
                if (scope.observation.fluid() != null && returned) {
                    try { verifyFluid(scope); }
                    catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                        scope.verified = false; LOGGER.error("Fluid machine output verification failed", failure);
                    }
                }
                var outcome = !returned ? MachineOutcomeEvent.Outcome.INTERRUPTED
                        : scope.verified ? MachineOutcomeEvent.Outcome.RETURNED : MachineOutcomeEvent.Outcome.UNCONFIRMED;
                try { NeoForge.EVENT_BUS.post(new MachineOutcomeEvent(scope.observation, outcome)); }
                catch (RuntimeException | LinkageError failure) { LOGGER.error("Machine outcome listener failed for {}", scope.observation.id(), failure); }
            }
        }
    }

    public static void beforeBasinRecipe(BlockEntity basin, Object recipe, boolean simulate) {
        var scope = SCOPES.get().peek();
        if (scope == null || simulate || scope.basin != null || !live(basin) || basin.getLevel() != scope.machine.getLevel()
                || source(scope.machine) == null || !basin.getBlockPos().equals(scope.machine.getBlockPos().below(2))) return;
        var selected = currentRecipe(scope.machine, recipe);
        if (selected == null || (selected.processing().process() != CreateProcessingRecipes.Process.MIXING
                && selected.processing().process() != CreateProcessingRecipes.Process.COMPACTING)) return;
        scope.basin = basin; scope.batchRecipe = recipe; scope.basinProcessing = true;
        try { scope.basinBefore = ((CreateBasinStorage) basin).citizens$basinSnapshot(); }
        catch (RuntimeException | LinkageError failure) { scope.invalidBatch = true; LOGGER.error("Basin input storage observation failed", failure); }
    }
    public static void afterBasinRecipe(BlockEntity basin, boolean simulate, boolean accepted) {
        var scope = SCOPES.get().peek();
        if (scope == null || simulate || scope.basin != basin || !scope.basinProcessing) return;
        scope.basinReturned = accepted; scope.basinProcessing = false;
    }
    public static void basinRolled(Object recipe, List<ItemStack> outputs) {
        var scope = SCOPES.get().peek();
        if (scope == null || !scope.basinProcessing || scope.batchRecipe != recipe) return;
        if (++scope.rolls != 1) scope.invalidBatch = true;
        scope.batchOutputs.addAll(copies(outputs));
    }
    public static void basinRemainders(List<ItemStack> outputs) {
        var scope = SCOPES.get().peek();
        if (scope != null && scope.basinProcessing) scope.batchRemainders.addAll(copies(outputs.stream().filter(s -> !s.isEmpty()).toList()));
    }
    public static void basinFluidPlan(FluidStack fluid, int amount) {
        var scope = SCOPES.get().peek();
        if (scope != null && scope.basinProcessing && scope.observation == null) scope.basinPlannedFluids.add(fluid.copyWithAmount(amount));
    }
    public static void basinItemConsumed(ItemStack item) {
        var scope = SCOPES.get().peek();
        if (scope != null && scope.basinProcessing) scope.basinConsumedItems.add(item.copy());
    }
    public static void basinFluidConsumed(FluidStack fluid, int amount) {
        var scope = SCOPES.get().peek();
        if (scope != null && scope.basinProcessing) scope.basinConsumedFluids.add(fluid.copyWithAmount(amount));
    }
    public static void basinAccepted(BlockEntity basin, List<ItemStack> items, List<FluidStack> fluids, boolean simulate,
            boolean accepted, int[] itemCounts) {
        var scope = SCOPES.get().peek();
        if (scope == null || scope.basin != basin || !scope.basinProcessing || !accepted) return;
        if (!simulate) { scope.basinStored = true; return; }
        if (scope.observation != null || scope.invalidBatch || scope.rolls != 1) return;
        try {
            var expected = new java.util.ArrayList<>(scope.batchOutputs); expected.addAll(scope.batchRemainders);
            if (!equalMultiset(expected, items) || !equalMultiset(scope.basinBefore.items(), ((CreateBasinStorage) basin).citizens$basinSnapshot().items())
                    || !equalFluids(scope.basinBefore.fluids(), ((CreateBasinStorage) basin).citizens$basinSnapshot().fluids())) return;
            var handler = basin.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, basin.getBlockPos(), null);
            if (handler == null || handler.getSlots() != itemCounts.length) return;
            var consumed = new java.util.ArrayList<ItemStack>();
            for (int slot = 0; slot < itemCounts.length; slot++) if (itemCounts[slot] > 0) {
                var stored = handler.getStackInSlot(slot); if (stored.getCount() < itemCounts[slot]) return;
                consumed.add(stored.copyWithCount(itemCounts[slot]));
            }
            var selected = currentRecipe(scope.machine, scope.batchRecipe); if (selected == null) return;
            var assignment = attribution(scope.machine); var actor = beneficiary(scope.machine, assignment); if (actor == null) return;
            var level = (ServerLevel) basin.getLevel();
            var data = new BasinData(basin.getBlockPos(), consumed, scope.basinPlannedFluids, scope.basinBefore.fluids(), fluids);
            scope.observation = new Observation(UUID.randomUUID(), assignment, level.dimension().location(), scope.machine.getBlockPos(),
                    level.getServer().getTickCount(), source(scope.machine), selected, consumed.isEmpty() ? ItemStack.EMPTY : consumed.getFirst(),
                    ItemStack.EMPTY, scope.batchRemainders.isEmpty() ? ItemStack.EMPTY : scope.batchRemainders.getFirst(), scope.basinBefore.items(),
                    scope.batchOutputs, scope.batchRemainders.size() < 2 ? List.of() : scope.batchRemainders.subList(1, scope.batchRemainders.size()), null, data);
            NeoForge.EVENT_BUS.post(new MachineResultEvent(actor, scope.observation));
            scope.invalidBatch |= !equalMultiset(scope.basinBefore.items(), ((CreateBasinStorage) basin).citizens$basinSnapshot().items())
                    || !equalFluids(scope.basinBefore.fluids(), ((CreateBasinStorage) basin).citizens$basinSnapshot().fluids());
        } catch (RuntimeException | LinkageError failure) { scope.invalidBatch = true; LOGGER.error("Basin preparation observation failed", failure); }
    }
    private static void verifyBasin(Scope scope) {
        if (scope.invalidBatch || !scope.basinStored || !scope.basinReturned || !live(scope.machine) || !live(scope.basin)) return;
        var data = scope.observation.basin();
        if (!equalMultiset(data.consumedItems(), scope.basinConsumedItems) || !equalFluids(data.consumedFluids(), scope.basinConsumedFluids)) return;
        var items = subtractItems(scope.basinBefore.items(), data.consumedItems());
        var fluids = subtractFluids(scope.basinBefore.fluids(), data.consumedFluids());
        if (items == null || fluids == null) return;
        items.addAll(scope.batchOutputs); items.addAll(scope.batchRemainders); fluids.addAll(data.fluidOutputs());
        var after = ((CreateBasinStorage) scope.basin).citizens$basinSnapshot();
        scope.verified = equalMultiset(items, after.items()) && equalFluids(fluids, after.fluids());
    }

    /** Item callbacks are scoped through both native holder storage and the final tank update. */
    public static <T> T duringFluidProcess(BlockEntity machine, Object input, Object handler, java.util.function.Supplier<T> original) {
        if (!live(machine)) return original.get();
        var result = new java.util.concurrent.atomic.AtomicReference<T>();
        duringProcess(machine, () -> {
            var scope = SCOPES.get().peek();
            if (source(machine) == Source.CREATE_SPOUT) {
                scope.transportInput = input; scope.transportExpectedHolder = transportBlockEntity(handler);
                scope.worldBefore = transportedStack(input).copy();
            } else if (source(machine) == Source.CREATE_ITEM_DRAIN) {
                scope.drainInput = drainStack(machine); scope.worldBefore = scope.drainInput.copy();
            }
            result.set(original.get());
        });
        return result.get();
    }

    public static void beforeFilling(ItemStack input, FluidStack fluid, int amount) {
        var scope = SCOPES.get().peek();
        if (scope == null || source(scope.machine) != Source.CREATE_SPOUT || scope.transportInput == null
                || transportedStack(scope.transportInput) != input || !ItemStack.matches(input, scope.worldBefore)
                || amount <= 0 || fluid.getAmount() < amount) return;
        try {
            scope.input = input.copyWithCount(1); scope.remainingInput = input.copyWithCount(input.getCount() - 1);
            scope.transportInitial = transportSnapshot(scope.transportExpectedHolder);
            scope.fluidBefore = tankSnapshot(scope.machine); scope.fluidConsumed = List.of(fluid.copyWithAmount(amount));
            scope.fluidCall = true;
        } catch (ReflectiveOperationException | RuntimeException failure) { scope.invalidBatch = true; LOGGER.error("Spout preparation failed", failure); }
    }
    public static void fluidRolled(Object recipe, List<ItemStack> outputs) {
        var scope = SCOPES.get().peek();
        if (scope == null || !scope.fluidCall || scope.fluidSimulate) return;
        try {
            if (++scope.rolls != 1) scope.invalidBatch = true;
            scope.batchRecipe = recipe; scope.batchOutputs.addAll(copies(outputs));
            if (source(scope.machine) == Source.CREATE_SPOUT) prepareFluid(scope, List.of());
        } catch (RuntimeException | LinkageError failure) { scope.invalidBatch = true; LOGGER.error("Fluid roll observation failed", failure); }
    }
    public static void beforeEmptying(ItemStack input, boolean simulate) {
        var scope = SCOPES.get().peek();
        if (scope == null || source(scope.machine) != Source.CREATE_ITEM_DRAIN) return;
        scope.fluidCall = true; scope.fluidSimulate = simulate;
        try {
            if (!simulate && scope.worldBefore.getCount() == 1 && ItemStack.matches(input, scope.worldBefore)) {
                scope.input = scope.worldBefore.copy(); scope.remainingInput = ItemStack.EMPTY;
                scope.fluidBefore = tankSnapshot(scope.machine); scope.fluidConsumed = List.of();
            }
        } catch (RuntimeException | LinkageError failure) { scope.invalidBatch = true; LOGGER.error("Drain preparation failed", failure); }
    }
    public static void afterEmptying(ItemStack input, boolean simulate, Object pair) {
        var scope = SCOPES.get().peek();
        if (scope == null || source(scope.machine) != Source.CREATE_ITEM_DRAIN || !scope.fluidCall) return;
        if (!simulate && scope.input != null && scope.batchRecipe != null) {
            try {
                var fluid = (FluidStack) pair.getClass().getMethod("getFirst").invoke(pair);
                var output = (ItemStack) pair.getClass().getMethod("getSecond").invoke(pair);
                scope.invalidBatch |= !input.isEmpty() || !equalMultiset(scope.batchOutputs, List.of(output));
                prepareFluid(scope, List.of(fluid));
            } catch (ReflectiveOperationException | RuntimeException failure) { scope.invalidBatch = true; LOGGER.error("Drain result observation failed", failure); }
        }
        scope.fluidCall = false;
    }
    private static void prepareFluid(Scope scope, List<FluidStack> outputs) {
        if (scope.observation != null || scope.invalidBatch || scope.input == null || scope.rolls != 1) return;
        var selected = currentRecipe(scope.machine, scope.batchRecipe); if (selected == null) return;
        var assignment = attribution(scope.machine); var actor = beneficiary(scope.machine, assignment); if (actor == null) return;
        if (source(scope.machine) == Source.CREATE_ITEM_DRAIN && (drainStack(scope.machine) != scope.drainInput
                || !ItemStack.matches(scope.drainInput, scope.worldBefore))) return;
        var level = (ServerLevel) scope.machine.getLevel();
        var data = new FluidData(scope.transportExpectedHolder == null ? scope.machine.getBlockPos() : scope.transportExpectedHolder.getBlockPos(),
                scope.fluidBefore, scope.fluidConsumed, outputs);
        scope.observation = new Observation(UUID.randomUUID(), assignment, level.dimension().location(), scope.machine.getBlockPos(),
                level.getServer().getTickCount(), source(scope.machine), selected, scope.input, scope.remainingInput, ItemStack.EMPTY,
                scope.transportInitial == null ? List.of(scope.worldBefore) : scope.transportInitial, scope.batchOutputs, List.of(), null, null, data);
        NeoForge.EVENT_BUS.post(new MachineResultEvent(actor, scope.observation));
        scope.invalidBatch |= !equalFluids(scope.fluidBefore, tankSnapshot(scope.machine));
        if (source(scope.machine) == Source.CREATE_SPOUT) scope.invalidBatch |= !ItemStack.matches(transportedStack(scope.transportInput), scope.worldBefore);
        else scope.invalidBatch |= drainStack(scope.machine) != scope.drainInput || !ItemStack.matches(scope.drainInput, scope.worldBefore);
    }
    private static ItemStack drainStack(BlockEntity machine) {
        try { return (ItemStack) machine.getClass().getMethod("getHeldItemStack").invoke(machine); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Unsupported Create item drain", failure); }
    }
    private static List<FluidStack> tankSnapshot(BlockEntity machine) {
        var handler = machine.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, machine.getBlockPos(), null);
        if (handler == null) throw new IllegalStateException("Native machine tank unavailable");
        var fluids = new java.util.ArrayList<FluidStack>();
        for (int tank = 0; tank < handler.getTanks(); tank++) fluids.add(handler.getFluidInTank(tank).copy());
        return fluids;
    }
    private static void verifyFluid(Scope scope) throws ReflectiveOperationException {
        boolean itemsVerified = scope.verified;
        scope.verified = false;
        if (scope.invalidBatch || !live(scope.machine)) return;
        if (source(scope.machine) == Source.CREATE_ITEM_DRAIN)
            itemsVerified = equalMultiset(scope.batchOutputs, List.of(drainStack(scope.machine)));
        var data = scope.observation.fluid();
        var expected = subtractFluids(data.beforeFluids(), data.consumedFluids()); if (expected == null) return;
        expected.addAll(data.fluidOutputs());
        scope.verified = itemsVerified && equalFluids(expected, tankSnapshot(scope.machine));
    }

    /** Keep the press scope open until the caller's native transport storage has returned. */
    public static <T> T duringTransportPress(Object input, Object handler, Object behaviour, java.util.function.Supplier<T> original) {
        BlockEntity machine;
        try { machine = (BlockEntity) behaviour.getClass().getField("blockEntity").get(behaviour); }
        catch (ReflectiveOperationException | RuntimeException failure) { return original.get(); }
        if (!live(machine) || source(machine) != Source.CREATE_PRESS) return original.get();
        var result = new java.util.concurrent.atomic.AtomicReference<T>();
        duringProcess(machine, () -> {
            var scope = SCOPES.get().peek(); scope.transportInput = input;
            scope.transportExpectedHolder = transportBlockEntity(handler);
            scope.worldBefore = transportedStack(input).copy(); result.set(original.get());
        });
        return result.get();
    }

    /** Called by the exact native holder; snapshots never flush Create's pending queues. */
    public static void duringTransportStorage(BlockEntity holder, java.util.function.Supplier<List<ItemStack>> snapshot,
            Runnable original) {
        var scope = SCOPES.get().peek();
        if (scope == null || scope.transportInput == null || scope.observation == null) { original.run(); return; }
        if (holder != (scope.transportStorageOwner == null ? scope.transportExpectedHolder : scope.transportStorageOwner)) {
            scope.invalidBatch = true; original.run(); return;
        }
        if (scope.transportHolder != null) { scope.invalidBatch = true; original.run(); return; }
        scope.transportHolder = holder;
        try { scope.transportBefore = copies(snapshot.get()); }
        catch (RuntimeException | LinkageError failure) {
            scope.invalidBatch = true; LOGGER.error("Transport input storage observation failed", failure); original.run(); return;
        }
        scope.storageActive = true;
        try {
            original.run(); scope.storageReturned = true;
            try { scope.transportAfter = copies(snapshot.get()); }
            catch (RuntimeException | LinkageError failure) { scope.invalidBatch = true; LOGGER.error("Transport output storage observation failed", failure); }
        }
        finally { scope.storageActive = false; }
    }

    public static void duringBeltStorage(Object inventory, java.util.function.Supplier<List<ItemStack>> snapshot, Runnable original) {
        var scope = SCOPES.get().peek();
        if (scope == null || scope.transportExpectedHolder == null) { original.run(); return; }
        try {
            var holder = scope.transportExpectedHolder;
            if (!holder.getClass().getName().equals("com.simibubi.create.content.kinetics.belt.BeltBlockEntity")) { original.run(); return; }
            var controller = (BlockEntity) holder.getClass().getMethod("getControllerBE").invoke(holder);
            if (controller == null || controller.getClass().getMethod("getInventory").invoke(controller) != inventory) { original.run(); return; }
            scope.transportStorageOwner = controller;
            duringTransportStorage(controller, snapshot, original);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Unsupported Create belt inventory", failure); }
    }

    public static void transportEntityAdded(net.minecraft.world.entity.Entity entity, boolean accepted) {
        var scope = SCOPES.get().peek();
        if (scope != null && scope.transportInput != null && scope.storageActive) worldEntityAdded(entity, accepted);
    }

    public static void transportMatched(Object input, ItemStack before, Object result) {
        var scope = SCOPES.get().peek();
        if (scope == null || scope.transportInput != input || scope.transportHolder == null || result == null) return;
        var expectedInput = source(scope.machine) == Source.CREATE_SPOUT ? scope.remainingInput : scope.worldBefore;
        if (!ItemStack.matches(before, expectedInput)) { scope.invalidBatch = true; return; }
        try {
            // The native holders intentionally skip unchanged results. Such a return did not store a craft.
            scope.transportMatched = !(boolean) result.getClass().getMethod("didntChangeFrom", ItemStack.class).invoke(result, before);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            scope.invalidBatch = true; LOGGER.error("Transport result acceptance observation failed", failure);
        }
    }

    /** Public pinned TransportedItemStack.stack; no provider class is linked when Create is absent. */
    public static ItemStack transportedStack(Object input) {
        try { return (ItemStack) input.getClass().getField("stack").get(input); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Unsupported Create transported stack", failure); }
    }

    public static BlockEntity transportBlockEntity(Object behaviour) {
        try { return (BlockEntity) behaviour.getClass().getField("blockEntity").get(behaviour); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Unsupported Create transport holder", failure); }
    }

    public static List<ItemStack> depotInventory(Object depot) {
        try { return inventory(transportBlockEntity(depot), (IItemHandler) depot.getClass().getField("itemHandler").get(depot)); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Unsupported Create depot inventory", failure); }
    }

    private static List<ItemStack> transportSnapshot(BlockEntity holder) throws ReflectiveOperationException {
        if (holder.getClass().getName().equals("com.simibubi.create.content.logistics.depot.DepotBlockEntity")) {
            var loader = holder.getClass().getClassLoader();
            var depotClass = Class.forName("com.simibubi.create.content.logistics.depot.DepotBehaviour", false, loader);
            var typeClass = Class.forName("com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType", false, loader);
            return depotInventory(holder.getClass().getMethod("getBehaviour", typeClass).invoke(holder, depotClass.getField("TYPE").get(null)));
        }
        if (holder.getClass().getName().equals("com.simibubi.create.content.kinetics.belt.BeltBlockEntity")) {
            var controller = holder.getClass().getMethod("getControllerBE").invoke(holder);
            var inventory = controller.getClass().getMethod("getInventory").invoke(controller);
            return ((CreateTransportInventory) inventory).citizens$transportSnapshot();
        }
        throw new IllegalStateException("Unsupported Create transport storage");
    }

    private static void verifyTransport(Scope scope) throws ReflectiveOperationException {
        if (!scope.storageReturned || !scope.transportMatched || scope.invalidBatch || !live(scope.machine)
                || !live(scope.transportHolder) || !live(scope.transportExpectedHolder)
                || scope.transportHolder.getLevel() != scope.machine.getLevel()) return;
        var initialAtStorage = source(scope.machine) == Source.CREATE_SPOUT
                ? subtractItems(scope.transportInitial, List.of(scope.input)) : scope.transportInitial;
        if (initialAtStorage == null || !equalMultiset(initialAtStorage, scope.transportBefore)) return;
        if (!equalMultiset(scope.transportAfter, transportSnapshot(scope.transportExpectedHolder))) return;
        var expected = new java.util.ArrayList<>(scope.transportBefore);
        // Remove this operation's exact original quantity; retain every unrelated stored stack.
        int needed = source(scope.machine) == Source.CREATE_SPOUT ? scope.remainingInput.getCount() : scope.worldBefore.getCount();
        for (var stack : expected) if (ItemStack.isSameItemSameComponents(stack, scope.worldBefore)) {
            int take = Math.min(needed, stack.getCount()); stack.shrink(take); needed -= take;
        }
        if (needed != 0) return;
        expected.add(scope.remainingInput); expected.addAll(scope.batchOutputs); expected.addAll(scope.batchRemainders);
        var actual = new java.util.ArrayList<>(scope.transportAfter);
        var level = (ServerLevel) scope.machine.getLevel();
        for (var output : scope.worldOutputs) {
            if (!output.isAlive() || output.level() != level || level.getEntity(output.getUUID()) != output) return;
            actual.add(output.getItem().copy());
        }
        scope.verified = equalMultiset(expected, actual);
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
        if (scope == null || (scope.worldInput == null && scope.transportInput == null) || scope.input != null || input.isEmpty()) return;
        if (scope.transportInput != null) {
            // Snapshot only an actual selected recipe invocation, not every held-item animation tick.
            try { scope.transportInitial = transportSnapshot(scope.transportExpectedHolder); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                scope.invalidBatch = true; LOGGER.error("Transport operation input observation failed", failure);
            }
        }
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
        if (scope == null || (scope.worldInput == null && scope.transportInput == null) || scope.input == null || scope.invalidBatch
                || scope.observation != null || scope.rolls != scope.input.getCount()) return;
        try {
            var expected = new java.util.ArrayList<>(scope.batchOutputs); expected.addAll(scope.batchRemainders);
            if (!equalMultiset(expected, results)) return;
            var selected = currentRecipe(scope.machine, scope.batchRecipe); if (selected == null) return;
            var assignment = attribution(scope.machine); var actor = beneficiary(scope.machine, assignment); if (actor == null) return;
            var level = (ServerLevel) scope.machine.getLevel();
            scope.observation = new Observation(UUID.randomUUID(), assignment, level.dimension().location(), scope.machine.getBlockPos(),
                    level.getServer().getTickCount(), Source.CREATE_PRESS, selected, scope.input, scope.remainingInput,
                    scope.batchRemainders.isEmpty() ? ItemStack.EMPTY : scope.batchRemainders.getFirst(),
                    scope.transportInitial == null ? List.of() : scope.transportInitial, scope.batchOutputs,
                    scope.batchRemainders.size() < 2 ? List.of() : scope.batchRemainders.subList(1, scope.batchRemainders.size()),
                    scope.worldInput == null ? null : scope.worldInput.getUUID());
            NeoForge.EVENT_BUS.post(new MachineResultEvent(actor, scope.observation));
        } catch (RuntimeException | LinkageError failure) { LOGGER.error("World press result observation failed", failure); }
    }

    /** Capture only the actual native output entities and insertion results, without searching nearby items. */
    public static void worldEntityAdded(net.minecraft.world.entity.Entity entity, boolean accepted) {
        Scope scope = SCOPES.get().peek();
        if (scope == null || (scope.worldInput == null && scope.transportInput == null) || scope.observation == null) return;
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
        if (observation.fluid() != null) {
            var data = observation.fluid(); var value = new com.google.gson.JsonObject();
            value.addProperty("x", data.holder().getX()); value.addProperty("y", data.holder().getY()); value.addProperty("z", data.holder().getZ());
            for (var entry : java.util.Map.of("beforeFluids", data.beforeFluids(), "consumedFluids", data.consumedFluids(), "fluidOutputs", data.fluidOutputs()).entrySet()) {
                var encoded = new com.google.gson.JsonArray();
                for (var stack : entry.getValue()) encoded.add(FluidStack.OPTIONAL_CODEC.encodeStart(ops, stack).getOrThrow());
                value.add(entry.getKey(), encoded);
            }
            root.add("fluid", value);
        }
        if (observation.basin() != null) {
            var data = observation.basin(); var value = new com.google.gson.JsonObject();
            value.addProperty("x", data.holder().getX()); value.addProperty("y", data.holder().getY()); value.addProperty("z", data.holder().getZ());
            var items = new com.google.gson.JsonArray();
            for (var stack : data.consumedItems()) items.add(ItemStack.CODEC.encodeStart(ops, stack).getOrThrow());
            value.add("consumedItems", items);
            for (var entry : java.util.Map.of("consumedFluids", data.consumedFluids(), "beforeFluids", data.beforeFluids(), "fluidOutputs", data.fluidOutputs()).entrySet()) {
                var encoded = new com.google.gson.JsonArray();
                for (var stack : entry.getValue()) encoded.add(FluidStack.OPTIONAL_CODEC.encodeStart(ops, stack).getOrThrow());
                value.add(entry.getKey(), encoded);
            }
            root.add("basin", value);
        }
        root.add("recipeDefinition", com.google.gson.JsonParser.parseString(observation.recipe().definition()));
        root.add("processing", CreateProcessingRecipes.encode(observation.recipe().processing(), registries));
        root.add("consumedInput", ItemStack.OPTIONAL_CODEC.encodeStart(ops, observation.consumedInput()).getOrThrow());
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
        return inventory(machine, (IItemHandler) machine.getClass().getField(field).get(machine));
    }
    private static List<ItemStack> inventory(BlockEntity machine, IItemHandler handler) {
        var items = new java.util.ArrayList<ItemStack>();
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
    private static java.util.ArrayList<ItemStack> subtractItems(List<ItemStack> stored, List<ItemStack> consumed) {
        var remaining = new java.util.ArrayList<>(copies(stored));
        for (var item : consumed) {
            int needed = item.getCount();
            for (var stack : remaining) if (ItemStack.isSameItemSameComponents(item, stack)) {
                int take = Math.min(needed, stack.getCount()); stack.shrink(take); needed -= take;
            }
            if (needed != 0) return null;
        }
        return remaining;
    }
    private static java.util.ArrayList<FluidStack> subtractFluids(List<FluidStack> stored, List<FluidStack> consumed) {
        var remaining = new java.util.ArrayList<>(fluidCopies(stored));
        for (var fluid : consumed) {
            int needed = fluid.getAmount();
            for (var stack : remaining) if (FluidStack.isSameFluidSameComponents(fluid, stack)) {
                int take = Math.min(needed, stack.getAmount()); stack.shrink(take); needed -= take;
            }
            if (needed != 0) return null;
        }
        return remaining;
    }
    private static boolean equalFluids(List<FluidStack> expected, List<FluidStack> actual) {
        var remaining = subtractFluids(actual, expected);
        return remaining != null && remaining.stream().allMatch(FluidStack::isEmpty);
    }
    private static List<FluidStack> fluidCopies(List<FluidStack> stacks) { return stacks.stream().map(FluidStack::copy).toList(); }
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
        if (!live(machine) || source(machine) == null) return null;
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
        Object transportInput; BlockEntity transportHolder, transportExpectedHolder, transportStorageOwner; List<ItemStack> transportInitial, transportBefore, transportAfter;
        boolean transportMatched, storageReturned, storageActive;
        BlockEntity basin; CreateBasinStorage.Snapshot basinBefore; boolean basinProcessing, basinReturned, basinStored;
        ItemStack drainInput; boolean fluidCall, fluidSimulate; List<FluidStack> fluidBefore, fluidConsumed;
        final List<ItemStack> basinConsumedItems = new java.util.ArrayList<>();
        final List<FluidStack> basinPlannedFluids = new java.util.ArrayList<>(), basinConsumedFluids = new java.util.ArrayList<>();
        final List<net.minecraft.world.entity.item.ItemEntity> worldOutputs = new java.util.ArrayList<>();
        Scope(BlockEntity machine) { this.machine = machine; }
    }
}
