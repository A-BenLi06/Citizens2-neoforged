package net.citizensnpcs.api.integration;

import java.util.List;
import java.util.Optional;

import net.citizensnpcs.util.CraftingRecipes;
import net.citizensnpcs.util.EconomyShopMenus;
import net.citizensnpcs.util.FtbQuestTasks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Public server-script queries and explicit provider-owned menu/delivery operations. */
public final class CitizensScripts {
    private CitizensScripts() { }

    public static int apiVersion() { return 1; }

    /** Current registry snapshot; a query never supplies craft evidence. */
    public static Optional<CraftingRecipes.Snapshot> recipe(ServerPlayer player, String recipeId) {
        active(player);
        return CraftingRecipes.find(player.getServer(), ResourceLocation.parse(recipeId));
    }

    public static FtbQuestTasks.State task(ServerPlayer player, String taskId) {
        active(player);
        return FtbQuestTasks.describe(player, taskId)
                .orElseThrow(() -> new IllegalStateException("Task is not visible to the script player")).state();
    }

    public static FtbQuestTasks.Page tasks(ServerPlayer player, String afterTaskId, int limit) {
        active(player);
        return FtbQuestTasks.discover(player, afterTaskId, limit);
    }

    /** Consumes only items required by an available native consuming delivery task. */
    public static FtbQuestTasks.State deliver(ServerPlayer player, String taskId) {
        task(player, taskId);
        return FtbQuestTasks.submit(player, taskId, false);
    }

    public static void validateEconomyMenu(ServerPlayer player, List<String> targetFields) {
        active(player);
        EconomyShopMenus.present(player, EconomyShopMenus.parse(targetFields), true);
    }

    /** Economy validates and opens its menu; opening does not execute a trade. */
    public static void openEconomyMenu(ServerPlayer player, List<String> targetFields) {
        active(player);
        EconomyShopMenus.present(player, EconomyShopMenus.parse(targetFields), false);
    }

    private static void active(ServerPlayer player) {
        if (player == null || player.getServer() == null || !player.getServer().isSameThread())
            throw new IllegalStateException("Script operations require an active server player on the server thread");
        if (player.hasDisconnected() || player.getServer().getPlayerList().getPlayer(player.getUUID()) != player)
            throw new IllegalStateException("Script player session is no longer active");
    }
}
