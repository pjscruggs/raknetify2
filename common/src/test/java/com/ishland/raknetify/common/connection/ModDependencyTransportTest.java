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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ishland.raknetify.common.Constants;
import com.ishland.raknetify.common.connection.multichannel.CustomPayloadChannel;
import com.ishland.raknetify.common.connection.multichannel.ModPayloadOrdering;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;

import network.ycc.raknet.frame.Frame;
import network.ycc.raknet.frame.FrameData;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.PriorityQueue;
import java.util.Queue;

/** Checks real mod policies against independently acknowledged entity, chunk and menu traffic. */
class ModDependencyTransportTest {
    /**
     * A previously unseen payload must stay between the vanilla state it reads and the next update.
     *
     * @param clientSender whether the mod packet is travelling to the server
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void undeclaredPayloadRetainsBothDeliveryDependencies(final boolean clientSender) {
        var codec = new RakNetSimpleMultiChannelCodec(0xfd);
        codec.addHandler(
                new CustomPayloadChannel.PayloadClassifier(id -> id == 24, null, clientSender));
        codec.addHandler((bytes, quiet) ->
                bytes.getUnsignedByte(bytes.readerIndex()) == 24
                        ? 3
                        : bytes.getUnsignedByte(bytes.readerIndex()));
        byte[] identifier =
                ("unregistered:" + java.util.UUID.randomUUID())
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var message =
                Unpooled.buffer().writeByte(24).writeByte(identifier.length).writeBytes(identifier);
        exerciseBoundaries(begin(codec), message);
    }

    /** A busy unregistered channel resumes making progress with two ticks of outstanding ACKs. */
    @org.junit.jupiter.api.Test
    void sustainedOpaqueTrafficDrainsWithoutExhaustingTheQueue() {
        var server = begin(distantHorizonsCodec());
        var receipts = new Receipts();
        server.pipeline().addFirst(receipts);
        var scheduled = new ArrayDeque<Integer>();
        int submitted = 0;
        int emitted = 0;
        byte[] identifier = "test:unregistered_burst".getBytes(StandardCharsets.US_ASCII);
        try {
            for (int tick = 0; tick < 400; tick++) {
                if (scheduled.size() >= 2) {
                    for (int n = scheduled.removeFirst(); n > 0; n--) {
                        receipts.pending.removeFirst().setSuccess();
                    }
                }
                if (tick < 360) {
                    for (int i = 0; i < 9; i++) {
                        writeQueued(
                                server,
                                Unpooled.buffer()
                                        .writeByte(7)
                                        .writeZero(1299)
                                        .writeInt(submitted++));
                        writeQueued(server, Unpooled.buffer().writeByte(2).writeInt(submitted++));
                        writeQueued(
                                server,
                                Unpooled.buffer()
                                        .writeByte(24)
                                        .writeByte(identifier.length)
                                        .writeBytes(identifier)
                                        .writeZero(1024)
                                        .writeInt(submitted++));
                        writeQueued(server, Unpooled.buffer().writeByte(3).writeInt(submitted++));
                    }
                }
                server.flushOutbound();
                int produced = server.outboundMessages().size();
                scheduled.addLast(produced);
                for (int i = 0; i < produced; i++) {
                    FrameData frame = server.readOutbound();
                    try {
                        ByteBuf bytes = frame.createData();
                        try {
                            assertEquals(
                                    emitted++, bytes.getInt(bytes.writerIndex() - Integer.BYTES));
                        } finally {
                            bytes.release();
                        }
                    } finally {
                        frame.release();
                    }
                }
            }
            assertTrue(server.isActive());
            assertEquals(12_960, submitted);
            assertEquals(submitted, emitted);
            assertTrue(receipts.pending.isEmpty());
            server.writeOutbound(Unpooled.buffer().writeByte(7));
            server.writeOutbound(Unpooled.buffer().writeByte(2));
            expectFrame(server, 7);
            expectFrame(server, 2);
            receipts.pending.removeFirst().setSuccess();
            receipts.pending.removeFirst().setSuccess();
        } finally {
            server.finishAndReleaseAll();
        }
    }

    /**
     * Queues a packet and reports a synchronous rejection without registering a listener.
     *
     * @param endpoint sending connection
     * @param message outbound packet consumed by the connection
     */
    private static void writeQueued(final EmbeddedChannel endpoint, final ByteBuf message) {
        assertNull(endpoint.write(message).cause());
    }

    /** Only the first small reliable successor may travel immediately behind an opaque payload. */
    @org.junit.jupiter.api.Test
    void opaquePayloadPipelinesOneSmallSuccessorWithoutOpeningAnotherLane() {
        var codec = new RakNetSimpleMultiChannelCodec(0xfd);
        codec.addHandler(new CustomPayloadChannel.PayloadClassifier(id -> id == 24));
        codec.addHandler((bytes, quiet) -> bytes.getUnsignedByte(bytes.readerIndex()));
        var server = begin(codec);
        var receipts = new Receipts();
        server.pipeline().addFirst(receipts);
        try {
            server.writeOutbound(Unpooled.buffer().writeByte(7));
            var payload = Unpooled.buffer().writeByte(24).writeByte(8);
            payload.writeCharSequence("test:new", java.nio.charset.StandardCharsets.US_ASCII);
            server.writeOutbound(payload);
            server.writeOutbound(Unpooled.buffer().writeByte(2));
            server.writeOutbound(Unpooled.buffer().writeByte(3));
            expectFrame(server, 7);
            expectFrame(server, 7);
            expectFrame(server, 7);
            receipts.pending.removeLast().setSuccess();
            receipts.pending.removeLast().setSuccess();
            assertTrue(server.outboundMessages().isEmpty());
            receipts.pending.removeFirst().setSuccess();
            expectFrame(server, 3);
            receipts.pending.removeFirst().setSuccess();
        } finally {
            server.finishAndReleaseAll();
        }
    }

    /** Terrain replies can progress while entity ACKs are outstanding, in fragment order. */
    @org.junit.jupiter.api.Test
    void distantHorizonsTerrainRunsWithoutWaitingForUnrelatedAcknowledgements() {
        var server = begin(distantHorizonsCodec());
        var receipts = new Receipts();
        server.pipeline().addFirst(receipts);
        try {
            server.writeOutbound(Unpooled.buffer().writeByte(2));
            expectFrame(server, 2);
            for (int type : new int[] {10, 10, 8, 9}) {
                server.writeOutbound(distantHorizonsPayload(type));
                expectFrame(server, Constants.BACKGROUND_TERRAIN_CHANNEL);
            }
            assertEquals(5, receipts.pending.size());
            while (!receipts.pending.isEmpty()) {
                receipts.pending.removeFirst().setSuccess();
            }
        } finally {
            server.finishAndReleaseAll();
        }
    }

    /** The send queue puts a later Minecraft chunk ahead of queued Distant Horizons fragments. */
    @org.junit.jupiter.api.Test
    void minecraftChunksTakePriorityOverQueuedDistantHorizonsFragments() {
        var server = begin(distantHorizonsCodec());
        Queue<Frame> pending = new PriorityQueue<>(RakNetConnectionUtil.SEND_ORDER);
        try {
            server.writeOutbound(distantHorizonsPayload(10), distantHorizonsPayload(10));
            server.writeOutbound(Unpooled.buffer().writeByte(7).writeZero(1024));
            for (int index : new int[] {0, 1, 0}) {
                FrameData data = server.readOutbound();
                try {
                    pending.add(Frame.createOrdered(data, index, 0));
                } finally {
                    data.release();
                }
            }
            expectPriority(pending, 7, 0);
            expectPriority(pending, Constants.BACKGROUND_TERRAIN_CHANNEL, 0);
            expectPriority(pending, Constants.BACKGROUND_TERRAIN_CHANNEL, 1);
        } finally {
            pending.forEach(Frame::release);
            server.finishAndReleaseAll();
        }
    }

    /**
     * Checks the next scheduled frame and releases its reference.
     *
     * @param pending production-ordered frame queue
     * @param lane expected frame lane
     * @param index expected index within the lane
     */
    private static void expectPriority(
            final Queue<Frame> pending, final int lane, final int index) {
        Frame frame = pending.remove();
        try {
            assertEquals(lane, frame.getOrderChannel());
            assertEquals(index, frame.getOrderIndex());
        } finally {
            frame.release();
        }
    }

    /** Unacknowledged DH terrain and control cannot hold a bundle fence or its successor. */
    @org.junit.jupiter.api.Test
    void gameplayContinuesWithDistantHorizonsReceiptsWithheld() {
        var server = begin(distantHorizonsCodec());
        var receipts = new Receipts();
        server.pipeline().addFirst(receipts);
        try {
            server.attr(BundleWire.PEER).set(true);
            server.writeOutbound(distantHorizonsPayload(10));
            expectFrame(server, Constants.BACKGROUND_TERRAIN_CHANNEL);
            server.writeOutbound(distantHorizonsPayload(2));
            expectFrame(server, Constants.BACKGROUND_TERRAIN_CHANNEL);
            server.writeOutbound(
                    Unpooled.buffer().writeByte(0),
                    Unpooled.buffer().writeByte(2),
                    Unpooled.buffer().writeByte(3),
                    Unpooled.buffer().writeByte(0),
                    Unpooled.buffer().writeByte(7));
            FrameData bundle = server.readOutbound();
            assertNotNull(bundle);
            try {
                assertEquals(BundleWire.MIXED, bundle.getPacketId());
            } finally {
                bundle.release();
            }
            assertNull(server.readOutbound());
            receipts.pending.removeLast().setSuccess();
            expectFrame(server, 7);
            receipts.pending.removeLast().setSuccess();
            assertEquals(2, receipts.pending.size());
            receipts.pending.removeFirst().setSuccess();
            receipts.pending.removeFirst().setSuccess();
        } finally {
            server.finishAndReleaseAll();
        }
    }

    /** DH control waits for earlier gameplay, while subsequent gameplay can overtake DH. */
    @org.junit.jupiter.api.Test
    void distantHorizonsControlWaitsOnlyForThePrecedingGameplay() {
        var server = begin(distantHorizonsCodec());
        var receipts = new Receipts();
        server.pipeline().addFirst(receipts);
        try {
            server.writeOutbound(Unpooled.buffer().writeByte(2));
            expectFrame(server, 2);
            server.writeOutbound(distantHorizonsPayload(2), distantHorizonsPayload(10));
            assertNull(server.readOutbound());
            server.writeOutbound(Unpooled.buffer().writeByte(7));
            expectFrame(server, 7);
            var newerGameplay = receipts.pending.removeLast();
            receipts.pending.removeFirst().setSuccess();
            expectFrame(server, Constants.BACKGROUND_TERRAIN_CHANNEL);
            expectFrame(server, Constants.BACKGROUND_TERRAIN_CHANNEL);
            assertFalse(newerGameplay.isDone());
            newerGameplay.setSuccess();
            receipts.pending.removeFirst().setSuccess();
            receipts.pending.removeFirst().setSuccess();
        } finally {
            server.finishAndReleaseAll();
        }
    }

    /** A large DH burst uses the normal transport window even with a gameplay fence pending. */
    @org.junit.jupiter.api.Test
    void distantHorizonsBulkHasNoDeliveryFenceWindow() {
        var server = begin(distantHorizonsCodec());
        var receipts = new Receipts();
        server.pipeline().addFirst(receipts);
        try {
            server.attr(BundleWire.PEER).set(true);
            server.writeOutbound(
                    Unpooled.buffer().writeByte(0),
                    Unpooled.buffer().writeByte(2),
                    Unpooled.buffer().writeByte(3),
                    Unpooled.buffer().writeByte(0));
            ((FrameData) server.readOutbound()).release();
            for (int i = 0; i < 600; i++) {
                server.writeOutbound(distantHorizonsPayload(10, 4096));
                expectFrame(server, Constants.BACKGROUND_TERRAIN_CHANNEL);
            }
            assertEquals(601, receipts.pending.size());
            while (!receipts.pending.isEmpty()) {
                receipts.pending.removeFirst().setSuccess();
            }
        } finally {
            server.finishAndReleaseAll();
        }
    }

    /** Foreground members of a mixed bundle complete without receiving its DH member. */
    @org.junit.jupiter.api.Test
    void distantHorizonsBundleMembersKeepSeparateCompletions() {
        var server = begin(distantHorizonsCodec());
        var client = new EmbeddedChannel(new RakNetSimpleMultiChannelCodec(0xfd));
        var receipts = new Receipts();
        server.pipeline().addFirst(receipts);
        try {
            server.attr(BundleWire.PEER).set(true);
            client.attr(BundleWire.RECEIVER).set(true);
            var opening = server.write(Unpooled.buffer().writeByte(0));
            var control = server.write(distantHorizonsPayload(2));
            var entity = server.write(Unpooled.buffer().writeByte(2));
            var chunk = server.write(Unpooled.buffer().writeByte(7));
            var closing = server.writeAndFlush(Unpooled.buffer().writeByte(0));
            FrameData envelope = server.readOutbound();
            assertNotNull(envelope);
            client.writeInbound(envelope);
            for (int expected : new int[] {0, 2, 7, 0}) {
                ByteBuf packet = client.readInbound();
                assertNotNull(packet);
                try {
                    assertEquals(expected, packet.readUnsignedByte());
                } finally {
                    packet.release();
                }
            }
            assertNull(client.readInbound());
            assertNull(server.readOutbound());
            receipts.pending.removeFirst().setSuccess();
            assertTrue(opening.isSuccess() && entity.isSuccess() && chunk.isSuccess());
            assertTrue(closing.isSuccess());
            expectFrame(server, Constants.BACKGROUND_TERRAIN_CHANNEL);
            assertFalse(control.isDone());
            receipts.pending.removeFirst().setSuccess();
            assertTrue(control.isSuccess());
        } finally {
            server.finishAndReleaseAll();
            client.finishAndReleaseAll();
        }
    }

    /** A phase reset forgets held DH frames even when old gameplay ACKs arrive later. */
    @org.junit.jupiter.api.Test
    void synchronizationDiscardsHeldDistantHorizonsFrames() {
        var server = begin(distantHorizonsCodec());
        var receipts = new Receipts();
        server.pipeline().addFirst(receipts);
        try {
            server.writeOutbound(Unpooled.buffer().writeByte(2));
            expectFrame(server, 2);
            var control = server.write(distantHorizonsPayload(2));
            var terrain = server.write(distantHorizonsPayload(10));
            assertNull(server.readOutbound());
            server.writeOutbound(SynchronizationLayer.SYNC_REQUEST_OBJECT);
            assertEquals(SynchronizationLayer.SYNC_REQUEST_OBJECT, server.readOutbound());
            assertTrue(control.isSuccess() && terrain.isSuccess());
            receipts.pending.removeFirst().setSuccess();
            receipts.pending.removeFirst().setSuccess();
            assertNull(server.readOutbound());
        } finally {
            server.finishAndReleaseAll();
        }
    }

    /**
     * Installs the normal custom payload classifier and fixture vanilla lanes.
     *
     * @return configured codec before multichannel negotiation
     */
    private static RakNetSimpleMultiChannelCodec distantHorizonsCodec() {
        var codec = new RakNetSimpleMultiChannelCodec(0xfd);
        codec.addHandler(new CustomPayloadChannel.PayloadClassifier(id -> id == 24));
        codec.addHandler((bytes, quiet) -> {
            int id = bytes.getUnsignedByte(bytes.readerIndex());
            return id == 0 ? Integer.MIN_VALUE : id;
        });
        return codec;
    }

    /**
     * Builds a protocol 17 Distant Horizons envelope for the routing test.
     *
     * @param type message registration ID
     * @return fixture packet owned by the caller
     */
    private static ByteBuf distantHorizonsPayload(final int type) {
        return distantHorizonsPayload(type, 1024);
    }

    /**
     * Creates a Distant Horizons reply with the chosen body size.
     *
     * @param type message registration ID
     * @param bodySize test payload size after the protocol header
     * @return fixture packet owned by the caller
     */
    private static ByteBuf distantHorizonsPayload(final int type, final int bodySize) {
        byte[] identifier = "distant_horizons:msg".getBytes(StandardCharsets.US_ASCII);
        return Unpooled.buffer()
                .writeByte(24)
                .writeByte(identifier.length)
                .writeBytes(identifier)
                .writeShort(17)
                .writeShort(type)
                .writeZero(bodySize);
    }

    /**
     * A state rebuild cannot overtake earlier domains or be overtaken by later menu contents.
     *
     * @param packetClass registered optional mod message with cross-domain dependencies
     * @param clientSender whether the client sends the configuration mutation
     */
    @ParameterizedTest
    @CsvSource({
        "top.theillusivec4.curios.common.network.server.sync.SPacketSyncCurios,false",
        "top.theillusivec4.curios.common.network.server.sync.SPacketSyncModifiers,false",
        "net.ltxprogrammer.changed.network.packet.SeatEntityInfoPacket,false",
        "team.creative.creativecore.common.gui.packet.OpenGuiPacket,false",
        "me.fzzyhmstrs.fzzy_config.networking.ConfigSyncS2CCustomPayload,false",
        "me.fzzyhmstrs.fzzy_config.networking.ConfigUpdateS2CCustomPayload,false",
        "me.fzzyhmstrs.fzzy_config.networking.ConfigUpdateC2SCustomPayload,true"
    })
    void stateRebuildRetainsBothDeliveryDependencies(
            final String packetClass, final boolean clientSender) {
        exerciseBoundaries(
                endpoint(packetClass, clientSender), Unpooled.buffer().writeByte(8).writeByte(250));
    }

    /**
     * Client-only registration must not hide configuration dependencies from the sending server.
     *
     * @param wrapped whether Forge's Architectury envelope carries the inner identifier
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void clientOnlyConfigurationRegistrationRetainsDeliveryBoundaries(final boolean wrapped) {
        String identifier = wrapped ? "architectury:network" : "octolib:config_sync";
        var codec = new RakNetSimpleMultiChannelCodec(0xfd);
        codec.addHandler((bytes, quiet) -> {
            int id = bytes.getUnsignedByte(bytes.readerIndex());
            return id == 8
                    ? ModPayloadOrdering.channel(
                            identifier,
                            bytes.slice(bytes.readerIndex() + 1, bytes.readableBytes() - 1),
                            false)
                    : id;
        });
        ByteBuf message = Unpooled.buffer().writeByte(8);
        if (wrapped) {
            byte[] name = "octolib:config_sync".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            message.writeByte(name.length).writeBytes(name);
        }
        exerciseBoundaries(begin(codec), message);
    }

    /**
     * Drives independently acknowledged predecessors and successors through a real mod boundary.
     *
     * @param server ready production codec and delivery queue
     * @param message encoded mod payload owned by the endpoint
     */
    private static void exerciseBoundaries(final EmbeddedChannel server, final ByteBuf message) {
        Receipts receipts = new Receipts();
        server.pipeline().addFirst(receipts);
        try {
            server.writeOutbound(Unpooled.buffer().writeByte(2));
            server.writeOutbound(Unpooled.buffer().writeByte(7));
            expectFrame(server, 2);
            expectFrame(server, 7);
            server.writeOutbound(message);
            server.writeOutbound(Unpooled.buffer().writeByte(3).writeZero(1400));
            assertTrue(server.outboundMessages().isEmpty());
            receipts.pending.removeLast().setSuccess();
            assertTrue(server.outboundMessages().isEmpty());
            receipts.pending.removeFirst().setSuccess();
            expectFrame(server, 0);
            assertTrue(server.outboundMessages().isEmpty());
            receipts.pending.removeFirst().setSuccess();
            expectFrame(server, 3);
            receipts.pending.removeFirst().setSuccess();
            assertTrue(server.outboundMessages().isEmpty());
        } finally {
            server.finishAndReleaseAll();
        }
    }

    /**
     * Binds a real optional message type and starts the production multichannel codec.
     *
     * @param packetClass concrete optional mod message name
     * @param clientSender outgoing direction
     * @return ready endpoint with ordinary game frames and the installed compatibility rule
     */
    private static EmbeddedChannel endpoint(final String packetClass, final boolean clientSender) {
        String channel = "test:dependency/" + packetClass;
        ModPayloadOrdering.register(channel, 250, packetClass);
        var codec = new RakNetSimpleMultiChannelCodec(0xfd);
        codec.addHandler((bytes, quiet) -> {
            int id = bytes.getUnsignedByte(bytes.readerIndex());
            return id == 8
                    ? ModPayloadOrdering.channel(
                            channel, bytes.slice(bytes.readerIndex() + 1, 1), clientSender)
                    : id;
        });
        return begin(codec);
    }

    /**
     * Enables ordinary multichannel framing before controlled acknowledgements are installed.
     *
     * @param codec classifier for the scenario's actual mod envelope
     * @return endpoint with its initial negotiation frame consumed
     */
    private static EmbeddedChannel begin(final RakNetSimpleMultiChannelCodec codec) {
        EmbeddedChannel endpoint = new EmbeddedChannel(new BundleDelivery(), codec);
        endpoint.writeOutbound(RakNetSimpleMultiChannelCodec.SIGNAL_START_MULTICHANNEL);
        ((FrameData) endpoint.readOutbound()).release();
        return endpoint;
    }

    /**
     * Observes an emitted game frame without acknowledging it or retaining its bytes.
     *
     * @param endpoint sender whose next frame must be available
     * @param lane expected independent ordering channel
     */
    private static void expectFrame(final EmbeddedChannel endpoint, final int lane) {
        FrameData frame = endpoint.readOutbound();
        assertNotNull(frame);
        try {
            assertEquals(0xfd, frame.getPacketId());
            assertEquals(lane, frame.getOrderChannel());
        } finally {
            frame.release();
        }
    }

    /** Separates frame emission from the transport acknowledgement controlled by each case. */
    private static final class Receipts extends ChannelOutboundHandlerAdapter {
        /** Outstanding acknowledgements in application write order. */
        private final Deque<ChannelPromise> pending = new ArrayDeque<>();

        /**
         * Forwards a real frame with an independent simulated delivery receipt.
         *
         * @param ctx outbound transport context
         * @param message owned game frame
         * @param promise application delivery completion
         */
        @Override
        public void write(
                final ChannelHandlerContext ctx,
                final Object message,
                final ChannelPromise promise) {
            pending.addLast(promise);
            ctx.write(message).addListener(network.ycc.raknet.RakNet.INTERNAL_WRITE_LISTENER);
        }
    }
}
