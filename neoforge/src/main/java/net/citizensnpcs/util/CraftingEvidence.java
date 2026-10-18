package net.citizensnpcs.util;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import net.citizensnpcs.api.event.CraftingResultEvent;
import net.citizensnpcs.api.event.CraftingOutcomeEvent;
import net.citizensnpcs.api.event.CraftingOutcomeEvent.Outcome;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.RecipeCraftingHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Native table/inventory result attribution; persistence and task admission belong to consumers. */
@EventBusSubscriber(modid = "citizens")
public final class CraftingEvidence {
    private static final Map<PlayerEvent.ItemCraftedEvent, Boolean> SEEN = new WeakHashMap<>();
    private static final Map<Slot, java.util.Deque<Pending>> QUICK = new java.util.IdentityHashMap<>();
    private static final ThreadLocal<java.util.Deque<Frame>> ACTIVE = ThreadLocal.withInitial(java.util.ArrayDeque::new);
    private CraftingEvidence() { }

    public enum Source { CRAFTING_TABLE, PLAYER_GRID }

    /** Copies preserve pre-consumption inputs and the complete native output batch. */
    public record Observation(UUID id, UUID actorId, ResourceLocation dimension, long serverTick, int menuId,
            Source source, CraftingRecipes.Snapshot recipe, int width, int height, List<ItemStack> inputs, ItemStack output) {
        public Observation {
            inputs = copies(inputs);
            output = output.copy();
        }
        @Override public List<ItemStack> inputs() { return copies(inputs); }
        @Override public ItemStack output() { return output.copy(); }
    }

    /** Registry-aware immutable payload for durable admission; empty grid slots remain explicit. */
    public static String encode(Observation observation, net.minecraft.core.HolderLookup.Provider registries) {
        var ops = registries.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
        var root = new com.google.gson.JsonObject();
        root.addProperty("id", observation.id().toString()); root.addProperty("actorId", observation.actorId().toString());
        root.addProperty("dimension", observation.dimension().toString()); root.addProperty("serverTick", observation.serverTick());
        root.addProperty("menuId", observation.menuId()); root.addProperty("source", observation.source().name());
        root.addProperty("width", observation.width()); root.addProperty("height", observation.height());
        var grid = new com.google.gson.JsonArray();
        for (ItemStack stack : observation.inputs()) grid.add(stack.isEmpty() ? com.google.gson.JsonNull.INSTANCE : ItemStack.CODEC.encodeStart(ops, stack).getOrThrow());
        root.add("inputs", grid); root.add("output", ItemStack.CODEC.encodeStart(ops, observation.output()).getOrThrow());
        var recipe = new com.google.gson.JsonObject(); var snapshot = observation.recipe();
        recipe.addProperty("id", snapshot.id().toString()); recipe.addProperty("type", snapshot.type().toString());
        recipe.addProperty("serializer", snapshot.serializer().toString()); recipe.addProperty("kind", snapshot.kind().name());
        recipe.addProperty("workstation", snapshot.workstation() == null ? null : snapshot.workstation().toString());
        recipe.addProperty("width", snapshot.width()); recipe.addProperty("height", snapshot.height());
        recipe.addProperty("revision", snapshot.revision()); recipe.add("definition", com.google.gson.JsonParser.parseString(snapshot.definition()));
        var inputs = new com.google.gson.JsonArray();
        for (var input : snapshot.inputs()) {
            var ingredient = new com.google.gson.JsonObject(); ingredient.add("definition", com.google.gson.JsonParser.parseString(input.definition()));
            ingredient.addProperty("custom", input.custom()); var alternatives = new com.google.gson.JsonArray();
            for (ItemStack stack : input.alternatives()) alternatives.add(stack.isEmpty() ? com.google.gson.JsonNull.INSTANCE : ItemStack.CODEC.encodeStart(ops, stack).getOrThrow());
            ingredient.add("alternatives", alternatives); inputs.add(ingredient);
        }
        recipe.add("inputs", inputs); root.add("recipe", recipe); return root.toString();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.getServer() == null
                || !player.getServer().isSameThread() || player.hasDisconnected()
                || player.getServer().getPlayerList().getPlayer(player.getUUID()) != player) return;
        synchronized (SEEN) { if (SEEN.put(event, Boolean.TRUE) != null) return; }
        try {
            Observation observation = capture(player, event);
            if (observation != null) {
                var pending = new Pending(player.getServer(), observation);
                Slot slot = player.containerMenu.getSlot(0);
                Frame frame = ACTIVE.get().stream().filter(candidate -> candidate.slot == slot).findFirst().orElse(null);
                if (frame != null) frame.observations.add(pending);
                try { NeoForge.EVENT_BUS.post(new CraftingResultEvent(player, observation)); }
                finally { if (frame == null) publish(pending, Outcome.UNCONFIRMED); }
            }
        } catch (RuntimeException | LinkageError failure) {
            // The result may already have moved. A consumer failure must not abort vanilla's ingredient consumption.
            org.slf4j.LoggerFactory.getLogger(CraftingEvidence.class).error("Crafting observation failed for {}", player.getUUID(), failure);
        }
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        synchronized (SEEN) { SEEN.clear(); }
        expire(event.getServer());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTick(ServerTickEvent.Post event) { expire(event.getServer()); }

    public static void duringQuickCraft(Slot slot, Runnable original) {
        Frame frame = new Frame(slot);
        ACTIVE.get().push(frame);
        boolean returned = false;
        try { original.run(); returned = true; }
        finally {
            pop(frame);
            if (returned && frame.observations.size() == 1) {
                synchronized (QUICK) { QUICK.computeIfAbsent(slot, ignored -> new java.util.ArrayDeque<>()).addLast(frame.observations.getFirst()); }
            } else for (Pending pending : frame.observations) publish(pending, returned ? Outcome.UNCONFIRMED : Outcome.INTERRUPTED);
        }
    }

    public static void duringTake(Slot slot, Player player, Runnable original) {
        if (!(player instanceof ServerPlayer)) { original.run(); return; }
        Frame frame = new Frame(slot);
        Pending unmatched = null;
        synchronized (QUICK) {
            var ready = QUICK.get(slot);
            if (ready != null) {
                Pending pending = ready.removeLast();
                if (pending.observation.actorId().equals(player.getUUID())) frame.observations.add(pending);
                else unmatched = pending;
                if (ready.isEmpty()) QUICK.remove(slot);
            }
        }
        if (unmatched != null) publish(unmatched, Outcome.UNCONFIRMED);
        ACTIVE.get().push(frame);
        boolean returned = false;
        try { original.run(); returned = true; }
        finally {
            pop(frame);
            Outcome outcome = !returned ? Outcome.INTERRUPTED : frame.observations.size() == 1 ? Outcome.RETURNED : Outcome.UNCONFIRMED;
            for (Pending pending : frame.observations) publish(pending, outcome);
        }
    }

    private static void pop(Frame frame) {
        var frames = ACTIVE.get();
        if (frames.pop() != frame) throw new IllegalStateException("Crafting call scope changed");
        if (frames.isEmpty()) ACTIVE.remove();
    }

    private static void publish(Pending pending, Outcome outcome) {
        try { NeoForge.EVENT_BUS.post(new CraftingOutcomeEvent(pending.observation, outcome)); }
        catch (RuntimeException | LinkageError failure) {
            org.slf4j.LoggerFactory.getLogger(CraftingEvidence.class).error("Crafting outcome listener failed for observation {}", pending.observation.id(), failure);
        }
    }

    private static void expire(MinecraftServer server) {
        var expired = new java.util.ArrayList<Pending>();
        synchronized (QUICK) {
            var slots = QUICK.values().iterator();
            while (slots.hasNext()) {
                var ready = slots.next();
                ready.removeIf(pending -> {
                    if (pending.server != server) return false;
                    expired.add(pending); return true;
                });
                if (ready.isEmpty()) slots.remove();
            }
        }
        for (Pending pending : expired) publish(pending, Outcome.UNCONFIRMED);
    }

    private record Pending(MinecraftServer server, Observation observation) { }
    private static final class Frame {
        final Slot slot;
        final java.util.List<Pending> observations = new java.util.ArrayList<>();
        Frame(Slot slot) { this.slot = slot; }
    }

    private static Observation capture(ServerPlayer player, PlayerEvent.ItemCraftedEvent event) {
        var menu = player.containerMenu;
        Source source;
        int result;
        if (menu.getClass() == CraftingMenu.class) {
            source = Source.CRAFTING_TABLE;
            result = ((CraftingMenu) menu).getResultSlotIndex();
        } else if (menu.getClass() == InventoryMenu.class) {
            source = Source.PLAYER_GRID;
            result = ((InventoryMenu) menu).getResultSlotIndex();
        } else return null;
        if (!(event.getInventory() instanceof CraftingContainer grid) || menu.getSlot(result + 1).container != grid
                || !(menu.getSlot(result).container instanceof RecipeCraftingHolder used)) return null;
        var holder = used.getRecipeUsed();
        var server = player.getServer();
        if (holder == null || server.getRecipeManager().byKey(holder.id()).orElse(null) != holder
                || !(holder.value() instanceof CraftingRecipe recipe)) return null;
        var input = grid.asCraftInput();
        if (!recipe.matches(input, player.serverLevel())) return null;
        var snapshot = CraftingRecipes.find(server, holder.id()).orElseThrow();
        if (!snapshot.supported()) return null;
        ItemStack output = event.getCrafting();
        if (output.isEmpty() || !ItemStack.matches(output, recipe.assemble(input, player.registryAccess()))
                || !ItemStack.matches(output, snapshot.output())) return null;
        return new Observation(UUID.randomUUID(), player.getUUID(), player.level().dimension().location(),
                server.getTickCount(), menu.containerId, source, snapshot, grid.getWidth(), grid.getHeight(), grid.getItems(), output);
    }

    private static List<ItemStack> copies(List<ItemStack> stacks) { return stacks.stream().map(ItemStack::copy).toList(); }
}
