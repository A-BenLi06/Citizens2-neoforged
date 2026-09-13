package net.citizensnpcs.editor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.ChatPrompts;
import net.citizensnpcs.util.Messages;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * A mode a player is put into to configure an NPC by interacting with the world: clicking the NPC to equip it, or
 * clicking a block to place a copy. Entering the same editor twice leaves it, which is what makes {@code /npc equip} a
 * toggle.
 * <p>
 * Upstream makes every editor a Bukkit {@code Listener} and registers each instance as it begins. Here one static
 * subscriber routes the two events an editor can care about to whichever editor that player is currently in, so
 * subclasses override a method rather than annotating one and nothing has to be registered or unregistered per instance.
 */
public abstract class Editor {
    public abstract void begin();

    public abstract void end();

    /**
     * The player right-clicked a block.
     *
     * @return whether to cancel the interaction
     */
    protected boolean onRightClickBlock(ServerPlayer player, ServerLevel level, BlockPos pos) {
        return false;
    }

    /**
     * The player right-clicked an NPC.
     *
     * @return whether to cancel the interaction
     */
    protected boolean onRightClickNPC(ServerPlayer player, NPC npc) {
        return false;
    }

    /**
     * The player left-clicked a block.
     *
     * @return whether to cancel the interaction
     */
    protected boolean onLeftClickBlock(ServerPlayer player, ServerLevel level, BlockPos pos) {
        return false;
    }

    /**
     * The player right-clicked any entity, NPC or not — which is how the waypoint editors let a marker be clicked away.
     *
     * @return whether to cancel the interaction
     */
    protected boolean onRightClickEntity(ServerPlayer player, Entity entity) {
        return false;
    }

    /**
     * The player said something while in this editor. Upstream's editors read their commands straight out of chat, so
     * the hook is offered here rather than through the prompt framework.
     *
     * @return whether the message was consumed and should not reach public chat
     */
    protected boolean onChat(ServerPlayer player, String message) {
        return false;
    }

    public static boolean hasEditor(ServerPlayer player) {
        return EDITING.containsKey(player.getUUID());
    }

    public static Editor getEditor(ServerPlayer player) {
        return EDITING.get(player.getUUID());
    }

    public static void enterOrLeave(ServerPlayer player, Editor editor) {
        if (editor == null)
            return;
        Editor existing = EDITING.get(player.getUUID());
        if (existing == null) {
            EDITING.put(player.getUUID(), editor);
            try { editor.begin(); }
            catch (RuntimeException | Error failure) {
                leave(player, editor);
                throw failure;
            }
        } else if (existing.getClass() == editor.getClass()) {
            leave(player);
        } else {
            Messaging.sendErrorTr(player.createCommandSourceStack(), Messages.ALREADY_IN_EDITOR);
        }
    }

    public static void leave(ServerPlayer player) {
        Editor editor = EDITING.remove(player.getUUID());
        if (editor != null) {
            editor.end();
        }
    }

    /** An abandonment callback must not close a different editor opened since it was registered. */
    public static void leave(ServerPlayer player, Editor expected) {
        if (EDITING.remove(player.getUUID(), expected)) expected.end();
    }

    public static void leaveAll() {
        for (Map.Entry<UUID, Editor> entry : new java.util.ArrayList<>(EDITING.entrySet())) {
            if (EDITING.remove(entry.getKey(), entry.getValue())) entry.getValue().end();
        }
    }

    /** Registered once at startup; there is no per-editor registration to leak. */
    public static void registerListeners() {
        NeoForge.EVENT_BUS.register(new Dispatcher());
    }

    private static class Dispatcher {
        @SubscribeEvent
        public void onChat(ServerChatEvent event) {
            ServerPlayer player = event.getPlayer();
            Editor editor = EDITING.get(player.getUUID());
            if (editor == null || ChatPrompts.isActive(player))
                return;
            String message = event.getRawText();
            // chat arrives off the server thread; anything the editor does with it must happen on-thread
            if (editor.onChat(player, message)) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public void onQuit(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) leave(player);
        }

        @SubscribeEvent
        public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
            if (!(event.getEntity() instanceof ServerPlayer player))
                return;
            Editor editor = EDITING.get(player.getUUID());
            if (editor == null || !(player.level() instanceof ServerLevel level))
                return;
            if (editor.onLeftClickBlock(player, level, event.getPos())) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public void onRightClickEntity(PlayerInteractEvent.EntityInteract event) {
            if (!(event.getEntity() instanceof ServerPlayer player))
                return;
            Editor editor = EDITING.get(player.getUUID());
            if (editor == null)
                return;
            if (editor.onRightClickEntity(player, event.getTarget())) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
            if (!(event.getEntity() instanceof ServerPlayer player))
                return;
            Editor editor = EDITING.get(player.getUUID());
            if (editor == null || !(player.level() instanceof ServerLevel level))
                return;
            if (editor.onRightClickBlock(player, level, event.getPos())) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public void onRightClickNPC(NPCRightClickEvent event) {
            ServerPlayer player = event.getClicker();
            Editor editor = EDITING.get(player.getUUID());
            if (editor == null)
                return;
            if (editor.onRightClickNPC(player, event.getNPC())) {
                // the port defers this so the remaining listeners still see the click; see NPCRightClickEvent
                event.setDelayedCancellation(true);
            }
        }
    }

    private static final Map<UUID, Editor> EDITING = new ConcurrentHashMap<>();
}
