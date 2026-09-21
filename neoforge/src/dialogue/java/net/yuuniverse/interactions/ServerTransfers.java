package net.yuuniverse.interactions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Sends the original BungeeCord Connect request over the player's advertised proxy channel. */
@EventBusSubscriber(modid = InteractionsMod.MOD_ID, value = Dist.DEDICATED_SERVER, bus = EventBusSubscriber.Bus.MOD)
public final class ServerTransfers {
    public static final ResourceLocation CHANNEL = ResourceLocation.parse("bungeecord:main");
    static final int MAX_PAYLOAD_BYTES = 32766;
    static final CustomPacketPayload.Type<Request> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    static final StreamCodec<FriendlyByteBuf, Request> STREAM_CODEC = StreamCodec.of(
            (buffer, request) -> buffer.writeBytes(request.data), buffer -> {
                int length = buffer.readableBytes();
                if (length > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("Server transfer payload is too large");
                byte[] data = new byte[length];
                buffer.readBytes(data);
                return decode(data);
            });

    private ServerTransfers() { }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        // No physical-client registration: a locally installed client mod must not advertise a nonexistent proxy.
        event.registrar("1").optional().playToClient(TYPE, STREAM_CODEC, (request, context) -> {
            throw new IllegalStateException("A dedicated server cannot handle an outbound BungeeCord Connect request");
        });
    }

    /** Prepares the opaque destination name without normalization or network side effects. */
    public static Request prepare(String target) {
        return new Request(target, encode(target));
    }

    /** Availability belongs to this live connection; a previous login or local codec alone is insufficient. */
    public static void checkAvailable(ServerPlayer player) {
        if (player == null || player.isRemoved() || player.connection == null
                || !player.connection.getConnection().isConnected()
                || player.connection.getConnection().getPacketListener() != player.connection
                || player.connection.protocol() != ConnectionProtocol.PLAY)
            throw new IllegalStateException("Server transfer requires an active player connection");
        if (NetworkRegistry.getCodec(CHANNEL, ConnectionProtocol.PLAY, PacketFlow.CLIENTBOUND) == null
                || !NetworkRegistry.hasChannel(player.connection.getConnection(), ConnectionProtocol.PLAY, CHANNEL))
            throw new IllegalStateException("This player connection has no available BungeeCord transfer channel");
    }

    /** Queues a request, with no acknowledgement or promise that the proxy accepted its destination. */
    public static void send(ServerPlayer player, Request request) {
        if (request == null) throw new IllegalArgumentException("Missing server transfer request");
        checkAvailable(player);
        player.connection.send(new ClientboundCustomPayloadPacket(request));
    }

    static byte[] encode(String target) {
        if (target == null) throw new IllegalArgumentException("Missing server transfer destination");
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeUTF("Connect");
            output.writeUTF(target);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot encode server transfer destination", failure);
        }
        byte[] result = bytes.toByteArray();
        if (result.length > MAX_PAYLOAD_BYTES)
            throw new IllegalArgumentException("Server transfer payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
        return result;
    }

    static Request decode(byte[] data) {
        if (data == null || data.length > MAX_PAYLOAD_BYTES)
            throw new IllegalArgumentException("Invalid server transfer payload size");
        try (var input = new DataInputStream(new ByteArrayInputStream(data))) {
            if (!input.readUTF().equals("Connect")) throw new IllegalArgumentException("Unsupported BungeeCord subchannel");
            String target = input.readUTF();
            if (input.available() != 0) throw new IllegalArgumentException("Unexpected server transfer payload data");
            return prepare(target);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Malformed server transfer payload", failure);
        }
    }

    /** Immutable, prevalidated payload; its bytes are never exposed to callers. */
    public static final class Request implements CustomPacketPayload {
        private final String target;
        private final byte[] data;

        private Request(String target, byte[] data) { this.target = target; this.data = data; }

        public String target() { return target; }

        @Override public CustomPacketPayload.Type<Request> type() { return TYPE; }
    }
}
