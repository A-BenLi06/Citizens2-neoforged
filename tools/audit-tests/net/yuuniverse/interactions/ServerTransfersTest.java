package net.yuuniverse.interactions;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Arrays;
import java.util.List;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class ServerTransfersTest {
    @Test void requestUsesTheExactBungeeConnectWireLayout() throws Exception {
        byte[] bytes = ServerTransfers.encode("hub");
        assertArrayEquals(new byte[] {0, 7, 'C', 'o', 'n', 'n', 'e', 'c', 't', 0, 3, 'h', 'u', 'b'}, bytes);
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            assertEquals("Connect", input.readUTF());
            assertEquals("hub", input.readUTF());
            assertEquals(-1, input.read());
        }
        assertEquals("bungeecord:main", ServerTransfers.prepare("hub").type().id().toString());
    }

    @Test void destinationIsOneOpaqueCaseSensitiveString() {
        for (String target : List.of("", " ", "  Lobby  ", "Lobby;Other", "host:25565", "伍德", "Realm\u0000😀")) {
            var request = ServerTransfers.prepare(target);
            assertEquals(target, request.target());
            assertEquals(target, ServerTransfers.decode(ServerTransfers.encode(target)).target());
        }
        assertThrows(IllegalArgumentException.class, () -> ServerTransfers.prepare(null));
    }

    @Test void nulAndSupplementaryCharactersUseModifiedUtfRatherThanMinecraftUtf() {
        byte[] bytes = ServerTransfers.encode("\u0000😀");
        assertArrayEquals(new byte[] {0, 7, 'C', 'o', 'n', 'n', 'e', 'c', 't', 0, 8,
                (byte) 0xc0, (byte) 0x80, (byte) 0xed, (byte) 0xa0, (byte) 0xbd,
                (byte) 0xed, (byte) 0xb8, (byte) 0x80}, bytes);
        assertEquals("\u0000😀", ServerTransfers.decode(bytes).target());
    }

    @Test void legacyPayloadLimitCountsTheHeadersAndModifiedUtfBytes() {
        int destinationBytes = 32755;
        for (String target : List.of("a".repeat(destinationBytes),
                "\u0000".repeat(destinationBytes / 2) + "a", "😀".repeat(destinationBytes / 6) + "a")) {
            byte[] bytes = ServerTransfers.encode(target);
            assertEquals(32766, bytes.length);
            assertEquals(target, ServerTransfers.decode(bytes).target());
            assertThrows(IllegalArgumentException.class, () -> ServerTransfers.prepare(target + "a"));
        }
        assertThrows(IllegalArgumentException.class, () -> ServerTransfers.prepare("a".repeat(65536)));
        assertThrows(IllegalArgumentException.class, () -> ServerTransfers.prepare("😀".repeat(10923)));
    }

    @Test void streamCodecWritesOnlyPayloadBytesAndRoundTripsTheExactTarget() {
        var request = ServerTransfers.prepare(" Lobby;伍德\u0000😀 ");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ServerTransfers.STREAM_CODEC.encode(buffer, request);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), bytes);
            assertArrayEquals(ServerTransfers.encode(request.target()), bytes);
            var decoded = ServerTransfers.STREAM_CODEC.decode(buffer);
            assertEquals(request.target(), decoded.target());
            assertEquals(ServerTransfers.CHANNEL, decoded.type().id());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test void decoderRejectsUnsupportedTruncatedMalformedAndTrailingData() throws Exception {
        byte[] valid = ServerTransfers.encode("hub");
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        byte[] malformedUtf = new byte[] {0, 7, 'C', 'o', 'n', 'n', 'e', 'c', 't', 0, 1, (byte) 0xc0};
        for (byte[] bytes : List.of(new byte[0], new byte[] {0}, Arrays.copyOf(valid, 9),
                Arrays.copyOf(valid, valid.length - 1), trailing, malformedUtf,
                payload("ConnectOther", "hub"), payload("connect", "hub"), new byte[32767]))
            assertThrows(IllegalArgumentException.class, () -> ServerTransfers.decode(bytes));
        assertThrows(IllegalArgumentException.class, () -> ServerTransfers.decode(null));

        FriendlyByteBuf oversized = new FriendlyByteBuf(Unpooled.wrappedBuffer(new byte[32767]));
        try {
            assertThrows(IllegalArgumentException.class, () -> ServerTransfers.STREAM_CODEC.decode(oversized));
        } finally {
            oversized.release();
        }
    }

    private static byte[] payload(String subchannel, String target) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeUTF(subchannel);
            output.writeUTF(target);
        }
        return bytes.toByteArray();
    }
}
