/*
 * This file is a part of the Raknetify project, licensed under MIT.
 *
 * Copyright (c) 2022-2025 ishland
 * Copyright (c) 2026 pjscruggs
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.ishland.raknetify.common.connection;

import static org.junit.jupiter.api.Assertions.*;

import com.ishland.raknetify.common.Constants;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

import network.ycc.raknet.frame.FrameData;
import network.ycc.raknet.packet.FramedPacket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import javax.crypto.spec.SecretKeySpec;

/** Verifies negotiated ordering, genuine payload preservation, and rejection of invalid peers. */
class CompatibilityNegotiationTest {
    /**
     * Builds a connection with policy installed before its advertisement can be emitted.
     *
     * @param server whether the endpoint is a server
     * @param ordered whether the server requires ordering
     * @return active endpoint with owned outbound declaration
     */
    private static EmbeddedChannel endpoint(final boolean server, final boolean ordered) {
        EmbeddedChannel channel = new EmbeddedChannel();
        CompatibilityPolicy policy = new CompatibilityPolicy(ordered);
        policy.install(channel, channel);
        channel.pipeline()
                .addLast(
                        CompatibilityNegotiation.NAME,
                        new CompatibilityNegotiation(policy, server));
        channel.pipeline()
                .addLast(
                        RakNetSimpleMultiChannelCodec.NAME,
                        new RakNetSimpleMultiChannelCodec(Constants.RAKNET_GAME_PACKET_ID));
        return channel;
    }

    /**
     * Exchanges the one initial declaration in each direction.
     *
     * @param client connecting endpoint
     * @param server accepting endpoint
     */
    private static void negotiate(final EmbeddedChannel client, final EmbeddedChannel server) {
        server.writeInbound((Object) client.readOutbound());
        client.writeInbound((Object) server.readOutbound());
    }

    /**
     * Creates a raw Minecraft frame with bounded fixture content.
     *
     * @param lane original ordering channel
     * @param reliability original reliability
     * @return owned frame for transfer to a test channel
     */
    private static FrameData game(final int lane, final FramedPacket.Reliability reliability) {
        ByteBuf bytes = Unpooled.buffer().writeInt(42);
        try {
            FrameData frame =
                    FrameData.create(
                            io.netty.buffer.UnpooledByteBufAllocator.DEFAULT,
                            Constants.RAKNET_GAME_PACKET_ID,
                            bytes);
            frame.setOrderChannel(lane);
            frame.setReliability(reliability);
            return frame;
        } finally {
            bytes.release();
        }
    }

    /** Only the requested connection adopts ordering, leaving another server's client unchanged. */
    @Test
    void serverSelectsConnectionLocalPolicy() {
        EmbeddedChannel client = endpoint(false, false);
        EmbeddedChannel server = endpoint(true, true);
        EmbeddedChannel other = endpoint(false, false);
        try {
            negotiate(client, server);
            assertTrue(CompatibilityPolicy.isOrdered(client));
            assertTrue(CompatibilityPolicy.isOrdered(server));
            assertFalse(CompatibilityPolicy.isOrdered(other));
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
            other.finishAndReleaseAll();
        }
    }

    /**
     * Every classifier outcome, including bundle discard, becomes ordered delivery.
     *
     * @param route original packet classifier result
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 7, -1, -2, Integer.MIN_VALUE})
    void orderedModeOverridesAllPacketRoutes(final int route) {
        EmbeddedChannel channel = endpoint(true, true);
        try {
            FrameData hello = channel.readOutbound();
            hello.release();
            channel.pipeline()
                    .get(RakNetSimpleMultiChannelCodec.class)
                    .addHandler((bytes, quiet) -> route);
            channel.writeOutbound(RakNetSimpleMultiChannelCodec.SIGNAL_START_MULTICHANNEL);
            channel.writeOutbound(SynchronizationLayer.SYNC_REQUEST_OBJECT);
            channel.writeOutbound(Unpooled.buffer().writeInt(42));
            FrameData data = channel.readOutbound();
            assertEquals(0, data.getOrderChannel());
            assertEquals(FramedPacket.Reliability.RELIABLE_ORDERED, data.getReliability());
            ByteBuf bytes = data.createData();
            try {
                assertEquals(42, bytes.skipBytes(1).readInt());
            } finally {
                bytes.release();
                data.release();
            }
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Old clients cannot enter gameplay on a server requiring the negotiated contract. */
    @Test
    void rejectsUnannouncedPeer() {
        EmbeddedChannel server = endpoint(true, true);
        FrameData frame = game(0, FramedPacket.Reliability.RELIABLE_ORDERED);
        try {
            assertThrows(IllegalArgumentException.class, () -> server.writeInbound(frame));
            assertEquals(0, frame.refCnt());
            assertFalse(server.isOpen());
        } finally {
            server.finishAndReleaseAll();
        }
    }

    /** Advertising support does not permit gameplay to bypass the required ordering lane. */
    @Test
    void rejectsPeerThatIgnoresPolicy() {
        EmbeddedChannel client = endpoint(false, false);
        EmbeddedChannel server = endpoint(true, true);
        try {
            negotiate(client, server);
            FrameData frame = game(2, FramedPacket.Reliability.RELIABLE_ORDERED);
            assertThrows(IllegalArgumentException.class, () -> server.writeInbound(frame));
            assertEquals(0, frame.refCnt());
            assertFalse(server.isOpen());
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
        }
    }

    /** Unordered delivery on channel zero is also rejected after successful negotiation. */
    @Test
    void rejectsUnorderedFrames() {
        EmbeddedChannel client = endpoint(false, false);
        EmbeddedChannel server = endpoint(true, true);
        try {
            negotiate(client, server);
            FrameData frame = game(0, FramedPacket.Reliability.RELIABLE);
            assertThrows(IllegalArgumentException.class, () -> server.writeInbound(frame));
            assertEquals(0, frame.refCnt());
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
        }
    }

    /** A default server still admits an older client without a capability advertisement. */
    @Test
    void defaultModeRetainsLegacyCompatibility() {
        EmbeddedChannel server = endpoint(true, false);
        try {
            assertTrue(server.writeInbound(game(2, FramedPacket.Reliability.RELIABLE_ORDERED)));
            ByteBuf bytes = server.readInbound();
            assertEquals(42, bytes.readInt());
            bytes.release();
            assertTrue(server.isOpen());
        } finally {
            server.finishAndReleaseAll();
        }
    }

    /** Unrelated extension frames reach their owning handler without becoming capabilities. */
    @Test
    void preservesUnrelatedEncryptionWitness() {
        EmbeddedChannel server = endpoint(true, false);
        ByteBuf payload = Unpooled.buffer().writeLong(42);
        FrameData witness = FrameData.create(server.alloc(), 0xF8, payload);
        payload.release();
        try {
            assertTrue(server.writeInbound(witness));
            assertSame(witness, server.readInbound());
            witness.release();
        } finally {
            server.finishAndReleaseAll();
        }
    }

    /** Encrypted delivery retains the negotiated contract after the login cipher transition. */
    @Test
    void encryptionPreservesOrderingAndPayload() {
        EmbeddedChannel client = endpoint(false, false);
        EmbeddedChannel server = endpoint(true, true);
        try {
            negotiate(client, server);
            var key = new SecretKeySpec(new byte[16], "AES");
            client.pipeline().addFirst(new MultiChannellingEncryption(key, true));
            server.pipeline().addFirst(new MultiChannellingEncryption(key, false));
            client.writeOutbound(Unpooled.buffer().writeInt(42));
            FrameData encrypted = client.readOutbound();
            server.writeInbound(encrypted);
            ByteBuf decoded = server.readInbound();
            assertEquals(42, decoded.readInt());
            decoded.release();
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
        }
    }

    /** Duplicate declarations cannot reset an established connection's policy. */
    @Test
    void rejectsRepeatedDeclaration() {
        EmbeddedChannel client = endpoint(false, false);
        EmbeddedChannel server = endpoint(true, true);
        FrameData declaration = client.readOutbound();
        declaration.retain();
        try {
            server.writeInbound(declaration);
            assertThrows(IllegalArgumentException.class, () -> server.writeInbound(declaration));
            assertEquals(0, declaration.refCnt());
            assertFalse(server.isOpen());
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
        }
    }

    /** A declaration after Minecraft traffic cannot change the delivery mode midstream. */
    @Test
    void rejectsLateDeclaration() {
        EmbeddedChannel client = endpoint(false, false);
        EmbeddedChannel server = endpoint(true, false);
        try {
            server.writeInbound(game(0, FramedPacket.Reliability.RELIABLE_ORDERED));
            FrameData declaration = client.readOutbound();
            assertThrows(IllegalArgumentException.class, () -> server.writeInbound(declaration));
            assertEquals(0, declaration.refCnt());
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
        }
    }

    /** A client cannot pretend to be the authoritative endpoint or request its own policy. */
    @Test
    void rejectsWrongRole() {
        EmbeddedChannel impostor = endpoint(true, true);
        EmbeddedChannel server = endpoint(true, false);
        try {
            FrameData declaration = impostor.readOutbound();
            assertThrows(IllegalArgumentException.class, () -> server.writeInbound(declaration));
            assertEquals(0, declaration.refCnt());
            assertFalse(CompatibilityPolicy.isOrdered(server));
        } finally {
            impostor.finishAndReleaseAll();
            server.finishAndReleaseAll();
        }
    }

    /** Ordered mode never lets a synchronization marker advance receive cursors. */
    @Test
    void rejectsOrderingReset() {
        EmbeddedChannel channel = new EmbeddedChannel();
        new CompatibilityPolicy(true).install(channel, channel);
        channel.pipeline().addLast(new SynchronizationLayer());
        ByteBuf payload = Unpooled.buffer().writeInt(42);
        FrameData marker =
                FrameData.create(channel.alloc(), Constants.RAKNET_SYNC_PACKET_ID, payload);
        payload.release();
        try {
            assertThrows(IllegalArgumentException.class, () -> channel.writeInbound(marker));
            assertEquals(0, marker.refCnt());
            assertFalse(channel.isOpen());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Invalid or misspelled configuration cannot silently downgrade the server requirement.
     *
     * @param directory isolated configuration directory
     * @throws Exception if the isolated file cannot be read
     */
    @Test
    void configurationIsExplicitAndBounded(@TempDir final Path directory) throws Exception {
        assertFalse(CompatibilityPolicy.load(directory.resolve("absent.json")));
        assertTrue(CompatibilityPolicy.parse("{\"mode\":\"ordered\"}"));
        assertFalse(CompatibilityPolicy.parse("{\"mode\":\"multichannel\"}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CompatibilityPolicy.parse("{\"mode\":\"orderd\"}"));
        assertThrows(
                IllegalArgumentException.class, () -> CompatibilityPolicy.parse("{\"mode\":true}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CompatibilityPolicy.parse("{\"mode\":\"ordered\",\"typo\":1}"));
    }
}
