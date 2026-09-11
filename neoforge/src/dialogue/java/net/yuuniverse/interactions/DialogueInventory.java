package net.yuuniverse.interactions;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Session-owned restrictions for player inventory input and the legacy main-hand interaction events. */
@EventBusSubscriber(modid = "interactions")
public final class DialogueInventory {
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private DialogueInventory() {}

    static void begin(Session session) { SESSIONS.put(session.player().getUUID(), session); }
    static void end(Session session) { SESSIONS.remove(session.player().getUUID(), session); }

    public static boolean isBlocked(UUID player) {
        Session session = SESSIONS.get(player);
        return session != null && !session.isFinished() && !session.permitsInventoryInteract();
    }

    private static boolean blocks(PlayerInteractEvent event) {
        return !event.getLevel().isClientSide() && event.getHand() == InteractionHand.MAIN_HAND
                && isBlocked(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void rightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (blocks(event)) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void rightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (blocks(event)) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void leftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (blocks(event)) event.setCanceled(true);
    }
}
