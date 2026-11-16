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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;

import network.ycc.raknet.frame.FrameData;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayDeque;

/**
 * Exercises real encoded bundles under independent lane delivery and deliberately delayed
 * acknowledgements.
 */
class BundleTransportTest {
    /**
     * Starts the production multichannel codec with test packet IDs denoting their natural lanes.
     *
     * @param peer whether the receiving client advertised envelope support
     * @return ready server with the negotiation marker already acknowledged
     */
    private static EmbeddedChannel server(final boolean peer) {
        final var codec = new RakNetSimpleMultiChannelCodec(0xfd);
        codec.addHandler((bytes, quiet) -> {
            int id = bytes.getUnsignedByte(bytes.readerIndex());
            return id == 0 ? Integer.MIN_VALUE : id == 5 ? -3 : id;
        });
        EmbeddedChannel server = new EmbeddedChannel(new BundleDelivery(), codec);
        server.attr(BundleWire.PEER).set(peer);
        server.writeOutbound(RakNetSimpleMultiChannelCodec.SIGNAL_START_MULTICHANNEL);
        ((FrameData) server.readOutbound()).release();
        return server;
    }

    /**
     * Constructs a receiving endpoint with the capability it would advertise at login.
     *
     * @return client decoder ready for ordinary and bundled frames
     */
    private static EmbeddedChannel client() {
        EmbeddedChannel client = new EmbeddedChannel(new RakNetSimpleMultiChannelCodec(0xfd));
        client.attr(BundleWire.RECEIVER).set(true);
        return client;
    }

    /**
     * Submits vanilla packet bytes, including real one-byte zero delimiters.
     *
     * @param server sending endpoint
     * @param ids packet IDs in application write order
     */
    private static void send(final EmbeddedChannel server, final int... ids) {
        for (int id : ids) {
            server.writeOutbound(Unpooled.buffer(1).writeByte(id));
        }
    }

    /**
     * Verifies every vanilla packet delivered to the receiver, including the precise bundle
     * boundaries.
     *
     * @param client receiving endpoint
     * @param expected complete expected ordered sequence of decoded packet IDs
     */
    private static void expect(final EmbeddedChannel client, final int... expected) {
        for (int id : expected) {
            ByteBuf bytes = client.readInbound();
            try {
                assertEquals(1, bytes.readableBytes());
                assertEquals(id, bytes.readUnsignedByte());
            } finally {
                bytes.release();
            }
        }
        assertNull(client.readInbound());
    }

    /** Reversing independent lane delivery cannot splice a chunk update into an entity bundle. */
    @Test
    void singleLaneBundleSurvivesReversal() {
        EmbeddedChannel server = server(true);
        EmbeddedChannel client = client();
        try {
            send(server, 0, 2, 2, 0, 7);
            FrameData bundle = server.readOutbound();
            assertEquals(BundleWire.SINGLE, bundle.getPacketId());
            assertEquals(2, bundle.getOrderChannel());
            client.writeInbound((Object) server.readOutbound());
            client.writeInbound(bundle);
            expect(client, 7, 0, 2, 2, 0);
        } finally {
            server.finishAndReleaseAll();
            client.finishAndReleaseAll();
        }
    }

    /** Neither an early ACK on a different lane nor a later write may cross a mixed bundle. */
    @Test
    void mixedBundleWaitsForEveryPredecessorAndBlocksSuccessors() {
        EmbeddedChannel server = server(true);
        EmbeddedChannel client = client();
        Receipts receipts = new Receipts();
        server.pipeline().addFirst(receipts);
        try {
            send(server, 2, 7, 0, 2, 7, 0, 3);
            FrameData first = server.readOutbound();
            client.writeInbound((Object) server.readOutbound());
            receipts.pending.removeLast().setSuccess();
            assertNull(server.readOutbound());
            client.writeInbound(first);
            receipts.pending.removeFirst().setSuccess();
            FrameData group = server.readOutbound();
            assertEquals(BundleWire.MIXED, group.getPacketId());
            assertNull(server.readOutbound());
            client.writeInbound(group);
            receipts.pending.removeFirst().setSuccess();
            client.writeInbound((Object) server.readOutbound());
            receipts.pending.removeFirst().setSuccess();
            expect(client, 7, 2, 0, 2, 7, 0, 3);
        } finally {
            server.finishAndReleaseAll();
            client.finishAndReleaseAll();
        }
    }

    /**
     * A mod boundary survives frame replacement by authenticated encryption.
     *
     * @param encrypted whether the actual frame encryption handlers replace outbound frames
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mixedModPacketFencesBothSidesWithoutChangingTheWire(final boolean encrypted) {
        EmbeddedChannel server = server(false);
        EmbeddedChannel client = client();
        Receipts receipts = new Receipts();
        server.pipeline().addFirst(receipts);
        if (encrypted) {
            var key = new javax.crypto.spec.SecretKeySpec(new byte[16], "AES");
            server.pipeline()
                    .addAfter(
                            server.pipeline().context(BundleDelivery.class).name(),
                            "cipher",
                            new MultiChannellingEncryption(key, false));
            client.pipeline()
                    .addBefore(
                            client.pipeline().context(RakNetSimpleMultiChannelCodec.class).name(),
                            "cipher",
                            new MultiChannellingEncryption(key, true));
        }
        try {
            send(server, 2, 7, 5, 3);
            FrameData first = server.readOutbound();
            client.writeInbound((Object) server.readOutbound());
            receipts.pending.removeLast().setSuccess();
            assertNull(server.readOutbound());
            client.writeInbound(first);
            receipts.pending.removeFirst().setSuccess();
            FrameData mixed = server.readOutbound();
            assertEquals(0xfd, mixed.getPacketId());
            assertEquals(0, mixed.getOrderChannel());
            assertNull(server.readOutbound());
            client.writeInbound(mixed);
            receipts.pending.removeFirst().setSuccess();
            client.writeInbound((Object) server.readOutbound());
            receipts.pending.removeFirst().setSuccess();
            expect(client, 7, 2, 5, 3);
        } finally {
            server.finishAndReleaseAll();
            client.finishAndReleaseAll();
        }
    }

    /**
     * A fenced payload may follow terrain immediately on its FIFO lane. A receipt for the payload
     * alone cannot release the next entity update while terrain is still missing.
     *
     * @param success whether the delayed terrain frame is ultimately delivered
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void fenceSharesThePredecessorLaneButWaitsForItsDelivery(final boolean success) {
        var server = server(true);
        var receipts = new Receipts();
        server.pipeline().addFirst(receipts);
        try {
            var terrainWrite = server.writeAndFlush(Unpooled.buffer(1).writeByte(7));
            var fence = server.writeAndFlush(Unpooled.buffer(1).writeByte(5));
            var entity = server.writeAndFlush(Unpooled.buffer(1).writeByte(2));
            assertEquals(2, receipts.pending.size());
            FrameData terrain = server.readOutbound();
            FrameData payload = server.readOutbound();
            try {
                assertEquals(7, terrain.getOrderChannel());
                assertEquals(7, payload.getOrderChannel());
            } finally {
                terrain.release();
                payload.release();
            }
            receipts.pending.removeLast().setSuccess();
            assertFalse(fence.isDone());
            assertNull(server.readOutbound());
            var first = receipts.pending.removeFirst();
            if (success) {
                first.setSuccess();
                assertTrue(fence.isSuccess());
                FrameData next = server.readOutbound();
                assertEquals(2, next.getOrderChannel());
                next.release();
                receipts.pending.removeFirst().setSuccess();
                assertTrue(entity.isSuccess());
            } else {
                var failure = new java.io.IOException("Undelivered terrain");
                first.setFailure(failure);
                assertSame(failure, terrainWrite.cause());
                assertSame(failure, fence.cause());
                assertSame(failure, entity.cause());
                assertFalse(server.isActive());
            }
        } finally {
            server.finishAndReleaseAll();
        }
    }

    /** Same-lane fences can travel together while a late fragment still holds the next lane. */
    @Test
    void sameLaneFencesShareOneAcknowledgementRound() {
        EmbeddedChannel server = server(true);
        var receipts = new Receipts();
        server.pipeline().addFirst(receipts);
        try {
            var first = server.writeAndFlush(Unpooled.buffer(1).writeByte(5));
            var second = server.writeAndFlush(Unpooled.buffer(1).writeByte(5));
            send(server, 3, 5);
            assertEquals(2, receipts.pending.size());
            receipts.pending.removeLast().setSuccess();
            assertFalse(first.isDone());
            assertFalse(second.isDone());
            assertEquals(2, server.outboundMessages().size());
            receipts.pending.removeFirst().setSuccess();
            assertTrue(first.isSuccess());
            assertTrue(second.isSuccess());
            assertEquals(3, server.outboundMessages().size());
            receipts.pending.removeFirst().setSuccess();
            assertEquals(4, server.outboundMessages().size());
            receipts.pending.removeFirst().setSuccess();
        } finally {
            server.finishAndReleaseAll();
        }
    }

    /** A pair of mixed envelopes on different lanes still requires two delivery rounds. */
    @Test
    void mixedEnvelopesOnDifferentLanesStayExclusive() {
        var receipts = new Receipts();
        var server = new EmbeddedChannel(receipts, new BundleDelivery());
        try {
            for (int lane : new int[] {2, 7}) {
                ByteBuf body = Unpooled.buffer(1).writeByte(0);
                try {
                    FrameData data = FrameData.create(server.alloc(), BundleWire.MIXED, body);
                    data.setOrderChannel(lane);
                    server.writeOutbound(data);
                } finally {
                    body.release();
                }
            }
            assertEquals(1, receipts.pending.size());
            assertEquals(1, server.outboundMessages().size());
            receipts.pending.removeFirst().setSuccess();
            assertEquals(2, server.outboundMessages().size());
            receipts.pending.removeFirst().setSuccess();
        } finally {
            server.finishAndReleaseAll();
        }
    }

    /** A missing first ACK limits pipelining and a failed fence fails the remaining prefix. */
    @Test
    void missingFenceBoundsReceiptsAndFailureClosesTheRun() {
        EmbeddedChannel server = server(true);
        var receipts = new Receipts();
        server.pipeline().addFirst(receipts);
        try {
            var writes = new java.util.ArrayList<io.netty.channel.ChannelFuture>();
            for (int i = 0; i < 100; i++) {
                writes.add(server.writeAndFlush(Unpooled.buffer(1).writeByte(5)));
            }
            int submitted = receipts.pending.size();
            assertTrue(submitted > 1 && submitted < writes.size());
            var missing = receipts.pending.removeFirst();
            while (!receipts.pending.isEmpty()) {
                receipts.pending.removeLast().setSuccess();
            }
            assertEquals(submitted, server.outboundMessages().size());
            assertTrue(writes.stream().noneMatch(io.netty.channel.ChannelFuture::isDone));
            var failure = new java.io.IOException("Missing fenced frame");
            missing.setFailure(failure);
            writes.forEach(write -> assertSame(failure, write.cause()));
            assertFalse(server.isActive());
        } finally {
            server.finishAndReleaseAll();
        }
    }

    /** Starting a compression dictionary must retain a mod packet's delivery boundary. */
    @Test
    void compressionStartupPreservesModFence() {
        EmbeddedChannel server =
                new EmbeddedChannel(
                        new BundleDelivery(), new MultiChannelingStreamingCompression(0xfd, 0xfe));
        EmbeddedChannel client =
                new EmbeddedChannel(new MultiChannelingStreamingCompression(0xfd, 0xfe));
        try {
            client.flushOutbound();
            server.writeInbound((Object) client.readOutbound());
            server.flushOutbound();
            FrameData control;
            while ((control = server.readOutbound()) != null) {
                client.writeInbound(control);
            }
            Receipts receipts = new Receipts();
            server.pipeline().addFirst(receipts);
            ChannelHandlerContext context =
                    server.pipeline().context(MultiChannelingStreamingCompression.class);
            ChannelPromise original = server.newPromise();
            ByteBuf body = Unpooled.buffer().writeZero(64);
            FrameData data = FrameData.create(server.alloc(), 0xfd, body);
            data.setOrderChannel(1);
            body.release();
            assertFalse(server.write(data, new FencedWritePromise(context, original)).isDone());
            server.flush();
            FrameData dictionary = server.readOutbound();
            FrameData compressed = server.readOutbound();
            assertNotNull(dictionary);
            assertNotNull(compressed);
            assertEquals(0xfe, compressed.getPacketId());
            assertEquals(dictionary.getOrderChannel(), compressed.getOrderChannel());
            receipts.pending.removeLast().setSuccess();
            assertFalse(original.isDone());
            client.writeInbound(dictionary);
            client.writeInbound(compressed);
            receipts.pending.removeFirst().setSuccess();
            assertTrue(original.isSuccess());
            FrameData decoded = client.readInbound();
            assertNotNull(decoded);
            assertEquals(65, decoded.getDataSize());
            decoded.release();
        } finally {
            server.finishAndReleaseAll();
            client.finishAndReleaseAll();
        }
    }

    /** Peers that never advertised bundle support receive their original per-member framing. */
    @Test
    void unannouncedClientGetsLegacyFrames() {
        EmbeddedChannel server = server(false);
        EmbeddedChannel client = client();
        try {
            send(server, 0, 2, 7, 0);
            client.writeInbound((Object) server.readOutbound());
            client.writeInbound((Object) server.readOutbound());
            assertNull(server.readOutbound());
            expect(client, 2, 7);
        } finally {
            server.finishAndReleaseAll();
            client.finishAndReleaseAll();
        }
    }

    /**
     * A truncated member is rejected before the vanilla decoder sees even the opening delimiter.
     */
    @Test
    void malformedGroupDeliversNothing() {
        EmbeddedChannel client = client();
        ByteBuf bytes = Unpooled.buffer().writeByte(1).writeByte(0).writeInt(8).writeByte(2);
        FrameData group = FrameData.create(client.alloc(), BundleWire.SINGLE, bytes);
        bytes.release();
        try {
            assertThrows(CorruptedFrameException.class, () -> client.writeInbound(group));
            assertNull(client.readInbound());
            assertEquals(0, group.refCnt());
        } finally {
            client.finishAndReleaseAll();
        }
    }

    /**
     * Separates transport delivery from acknowledgement so the fixture controls each dependency.
     */
    private static final class Receipts extends ChannelOutboundHandlerAdapter {
        /** Original write acknowledgements outstanding on the simulated link. */
        private final java.util.Deque<ChannelPromise> pending = new ArrayDeque<>();

        /**
         * Forwards the actual frame while retaining its independent acknowledgement.
         *
         * @param ctx outbound fixture transport
         * @param msg owned production frame
         * @param promise acknowledgement released by the test after delivery
         */
        @Override
        public void write(
                final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) {
            pending.addLast(promise);
            ctx.write(msg).addListener(network.ycc.raknet.RakNet.INTERNAL_WRITE_LISTENER);
        }
    }
}
