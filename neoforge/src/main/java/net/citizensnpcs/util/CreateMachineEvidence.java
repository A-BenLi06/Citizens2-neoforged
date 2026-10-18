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

/** Create millstone completion observations with explicitly assigned online beneficiaries. */
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

    public enum Source { CREATE_MILLSTONE }
    public record Observation(UUID id, Attribution attribution, ResourceLocation dimension, BlockPos position, long serverTick,
            Source source, CraftingRecipes.Snapshot recipe, ItemStack consumedInput, ItemStack remainingInput, ItemStack nativeRemainder,
            List<ItemStack> beforeOutput, List<ItemStack> rolledOutputs) {
        public Observation {
            position = position.immutable(); consumedInput = consumedInput.copy(); remainingInput = remainingInput.copy();
            nativeRemainder = nativeRemainder.copy(); beforeOutput = copies(beforeOutput); rolledOutputs = copies(rolledOutputs);
        }
        @Override public ItemStack consumedInput() { return consumedInput.copy(); }
        @Override public ItemStack remainingInput() { return remainingInput.copy(); }
        @Override public ItemStack nativeRemainder() { return nativeRemainder.copy(); }
        @Override public List<ItemStack> beforeOutput() { return copies(beforeOutput); }
        @Override public List<ItemStack> rolledOutputs() { return copies(rolledOutputs); }
    }

    /** Authenticated administrators assign an existing loaded millstone; existing assignments require an explicit clear. */
    public static Attribution assign(CommandSourceStack source, UUID beneficiaryId, BlockPos position) {
        authorize(source); var level = source.getLevel();
        if (!level.hasChunkAt(position)) throw new IllegalArgumentException("Machine chunk is not loaded");
        var machine = level.getBlockEntity(position);
        if (machine == null || !millstone(machine)) throw new IllegalArgumentException("Supported machine is unavailable");
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
        if (machine == null || !millstone(machine)) throw new IllegalArgumentException("Supported machine is unavailable");
        machine.removeData(ATTRIBUTION); machine.setChanged();
    }

    public static Attribution attribution(BlockEntity machine) {
        if (!(machine.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread())
            throw new IllegalStateException("Machine attribution requires its server thread");
        return machine.getExistingData(ATTRIBUTION).flatMap(value -> value).orElse(null);
    }

    /** Called only by the pinned millstone process wrapper; native matching/consumption/RNG are unchanged. */
    public static void duringProcess(BlockEntity machine, Runnable original) {
        Scope scope = new Scope(machine);
        SCOPES.get().push(scope); boolean returned = false;
        try { original.run(); returned = true; }
        finally {
            var scopes = SCOPES.get(); if (scopes.pop() != scope) throw new IllegalStateException("Machine observation scope changed");
            if (scopes.isEmpty()) SCOPES.remove();
            if (scope.observation != null) {
                var outcome = !returned ? MachineOutcomeEvent.Outcome.INTERRUPTED
                        : scope.verified ? MachineOutcomeEvent.Outcome.RETURNED : MachineOutcomeEvent.Outcome.UNCONFIRMED;
                try { NeoForge.EVENT_BUS.post(new MachineOutcomeEvent(scope.observation, outcome)); }
                catch (RuntimeException | LinkageError failure) { LOGGER.error("Machine outcome listener failed for {}", scope.observation.id(), failure); }
            }
        }
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
            var holders = server.getRecipeManager().getRecipes().stream().filter(holder -> holder.value() == recipe).toList();
            if (holders.size() != 1) return;
            var snapshot = CraftingRecipes.find(server, holders.getFirst().id()).orElseThrow();
            if (snapshot.processing() == null || snapshot.processing().process() != CreateProcessingRecipes.Process.MILLING) return;
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
        root.add("recipeDefinition", com.google.gson.JsonParser.parseString(observation.recipe().definition()));
        root.add("processing", CreateProcessingRecipes.encode(observation.recipe().processing(), registries));
        root.add("consumedInput", ItemStack.CODEC.encodeStart(ops, observation.consumedInput()).getOrThrow());
        root.add("remainingInput", ItemStack.OPTIONAL_CODEC.encodeStart(ops, observation.remainingInput()).getOrThrow());
        root.add("nativeRemainder", ItemStack.OPTIONAL_CODEC.encodeStart(ops, observation.nativeRemainder()).getOrThrow());
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
    private static boolean millstone(BlockEntity machine) { return machine.getClass().getName().equals("com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity"); }
    private static List<ItemStack> copies(List<ItemStack> stacks) { return stacks.stream().map(ItemStack::copy).toList(); }
    private static void authorize(CommandSourceStack source) {
        if (source == null || !source.getServer().isSameThread() || !PermissionUtil.hasPermission(source, "citizens.admin")
                || source.getPlayer() == null || source.getPlayer().hasDisconnected()
                || source.getServer().getPlayerList().getPlayer(source.getPlayer().getUUID()) != source.getPlayer())
            throw new IllegalStateException("Machine assignment requires an authenticated administrator");
    }
    private static final class Scope {
        final BlockEntity machine; ItemStack input; ItemStack remainingInput; ItemStack remainder; List<ItemStack> beforeOutput; Observation observation; boolean verified;
        Scope(BlockEntity machine) { this.machine = machine; }
    }
}
