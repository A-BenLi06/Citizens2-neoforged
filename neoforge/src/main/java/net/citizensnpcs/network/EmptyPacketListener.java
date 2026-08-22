package net.citizensnpcs.network;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * The packet listener attached to a player NPC.
 * <p>
 * {@code ServerPlayer.connection} is dereferenced all over vanilla, so it cannot be left null. This subclass exists to
 * discard outbound packets and to skip the flush bookkeeping, both of which assume a real remote client.
 */
public class EmptyPacketListener extends ServerGamePacketListenerImpl {
    public EmptyPacketListener(MinecraftServer server, Connection connection, ServerPlayer player,
            CommonListenerCookie cookie) {
        super(server, connection, player, cookie);
    }

    @Override
    public void resumeFlushing() {
    }

    @Override
    public void send(Packet<?> packet) {
    }
}
