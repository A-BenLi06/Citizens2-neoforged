package net.citizensnpcs.util;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import net.citizensnpcs.api.event.CraftingResultEvent;
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

/** Native table/inventory result attribution; persistence and task admission belong to consumers. */
@EventBusSubscriber(modid = "citizens")
public final class CraftingEvidence {
    private static final Map<PlayerEvent.ItemCraftedEvent, Boolean> SEEN = new WeakHashMap<>();
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

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.getServer() == null
                || !player.getServer().isSameThread() || player.hasDisconnected()
                || player.getServer().getPlayerList().getPlayer(player.getUUID()) != player) return;
        synchronized (SEEN) { if (SEEN.put(event, Boolean.TRUE) != null) return; }
        try {
            Observation observation = capture(player, event);
            if (observation != null) NeoForge.EVENT_BUS.post(new CraftingResultEvent(player, observation));
        } catch (RuntimeException | LinkageError failure) {
            // The result may already have moved. A consumer failure must not abort vanilla's ingredient consumption.
            org.slf4j.LoggerFactory.getLogger(CraftingEvidence.class).error("Crafting observation failed for {}", player.getUUID(), failure);
        }
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) { synchronized (SEEN) { SEEN.clear(); } }

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
