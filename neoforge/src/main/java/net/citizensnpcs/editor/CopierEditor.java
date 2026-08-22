package net.citizensnpcs.editor;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.CurrentLocation;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.TeleportCause;
import net.citizensnpcs.util.Messages;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /npc copier}. While active, clicking a block drops a copy of the NPC there.
 */
public class CopierEditor extends Editor {
    private final String name;
    private final NPC npc;
    private final ServerPlayer player;

    public CopierEditor(ServerPlayer player, NPC npc) {
        this.player = player;
        this.npc = npc;
        this.name = npc.getRawName();
    }

    @Override
    public void begin() {
        Messaging.sendTr(player.createCommandSourceStack(), Messages.COPIER_EDITOR_BEGIN);
    }

    @Override
    public void end() {
        Messaging.sendTr(player.createCommandSourceStack(), Messages.COPIER_EDITOR_END);
    }

    @Override
    protected boolean onRightClickBlock(ServerPlayer clicker, ServerLevel level, BlockPos pos) {
        if (clicker != player)
            return false;
        NPC copy = npc.clone();
        if (!copy.getRawName().equals(name)) {
            copy.setName(name);
        }
        if (copy.isSpawned()) {
            Location at = new Location(level, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5,
                    player.getYRot(), player.getXRot());
            copy.teleport(at, TeleportCause.PLUGIN);
            copy.getOrAddTrait(CurrentLocation.class).setLocation(at);
        }
        Messaging.sendTr(player.createCommandSourceStack(), Messages.NPC_COPIED, npc.getName());
        return true;
    }
}
