package net.citizensnpcs.network;

import java.net.SocketAddress;

import net.citizensnpcs.util.EmptyChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * A {@link Connection} that swallows everything sent to it.
 * <p>
 * A player NPC is a real {@link net.minecraft.server.level.ServerPlayer}, and vanilla assumes every ServerPlayer has a
 * live connection — it sends inventory updates, position corrections and keep-alives without asking. There is no client
 * at the other end, so the connection has to exist and accept packets while doing nothing with them.
 * <p>
 * The channel is a dummy rather than null: {@code Connection} dereferences it in several places (flush handling,
 * autoread) and a null there surfaces as an NPE deep inside vanilla rather than anywhere useful.
 */
public class EmptyConnection extends Connection {
    public EmptyConnection(PacketFlow flow) {
        super(flow);
        this.channel = new EmptyChannel(null);
        this.address = new SocketAddress() {
            private static final long serialVersionUID = 8207338859896320185L;
        };
    }

    @Override
    public void flushChannel() {
    }

    @Override
    public boolean isConnected() {
        // vanilla skips work for disconnected players; reporting true keeps the NPC ticking normally
        return true;
    }

    @Override
    public void send(Packet<?> packet) {
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener listener) {
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
    }
}
