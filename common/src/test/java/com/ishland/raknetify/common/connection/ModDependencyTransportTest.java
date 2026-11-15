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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ishland.raknetify.common.connection.multichannel.ModPayloadOrdering;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;

import network.ycc.raknet.frame.FrameData;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayDeque;
import java.util.Deque;

/** Checks real mod policies against independently acknowledged entity, chunk and menu traffic. */
class ModDependencyTransportTest {
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
            server.writeOutbound(Unpooled.buffer().writeByte(3));
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
