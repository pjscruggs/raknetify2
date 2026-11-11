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

import com.ishland.raknetify.common.Constants;
import com.ishland.raknetify.common.util.ConsoleMessages;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;

import network.ycc.raknet.RakNet;
import network.ycc.raknet.frame.FrameData;
import network.ycc.raknet.packet.FramedPacket;

/** Advertises endpoint capabilities before Minecraft and enforces server-selected stream order. */
public final class CompatibilityNegotiation extends ChannelDuplexHandler {
    /** Pipeline name placed after decompression and before Minecraft framing. */
    public static final String NAME = "raknetify-compatibility";

    /** Optional extension ignored by older peers in multichannel mode. */
    public static final int PACKET_ID = 0xF6;

    /** Shared policy also consulted by framing, proxy replies, and world synchronization. */
    private final CompatibilityPolicy policy;

    /** True only on the server or client-facing proxy endpoint. */
    private final boolean server;

    /** Local advertisement is emitted at most once, including already-active initialization. */
    private boolean sent;

    /** Peer capabilities must arrive once, before any Minecraft data. */
    private boolean received;

    /** Prevents late declarations from changing a live session's delivery policy. */
    private boolean gameReceived;

    /**
     * Creates one endpoint's negotiation state without sharing it across connections.
     *
     * @param policy connection-local policy
     * @param server whether this endpoint is authoritative
     */
    public CompatibilityNegotiation(final CompatibilityPolicy policy, final boolean server) {
        this.policy = policy;
        this.server = server;
    }

    /**
     * Advertises when installed into an already active application channel.
     *
     * @param ctx newly installed handler context
     */
    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) {
        if (ctx.channel().isActive()) {
            advertise(ctx);
        }
    }

    /**
     * Sends capabilities before downstream handlers can send Minecraft packets.
     *
     * @param ctx activated application channel
     */
    @Override
    public void channelActive(final ChannelHandlerContext ctx) {
        advertise(ctx);
        ctx.fireChannelActive();
    }

    /**
     * Writes a versioned, reliable channel-zero declaration with no mod or player identifiers.
     *
     * @param ctx allocator and outbound context
     */
    private void advertise(final ChannelHandlerContext ctx) {
        if (sent) {
            return;
        }
        sent = true;
        ByteBuf data =
                ctx.alloc()
                        .buffer(3)
                        .writeByte(1)
                        .writeBoolean(server)
                        .writeBoolean(server && CompatibilityPolicy.isOrdered(ctx.channel()));
        try {
            ctx.writeAndFlush(FrameData.create(ctx.alloc(), PACKET_ID, data))
                    .addListener(RakNet.INTERNAL_WRITE_LISTENER);
            if (!server) {
                BundleWire.advertise(ctx);
            }
        } finally {
            data.release();
        }
    }

    /**
     * Consumes declarations and validates the preserved transport metadata before Minecraft
     * decoding.
     *
     * @param ctx inbound application context
     * @param message owned frame or unrelated pipeline message
     * @throws Exception if downstream processing fails
     */
    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object message)
            throws Exception {
        if (!(message instanceof FrameData frame)) {
            ctx.fireChannelRead(message);
            return;
        }
        try {
            if (frame.getPacketId() == PACKET_ID) {
                accept(ctx, frame);
                frame.release();
                return;
            }
            if (frame.getPacketId() == BundleWire.CAPABILITY) {
                BundleWire.accept(ctx, frame, server && !gameReceived);
                frame.release();
                return;
            }
            validateGame(ctx, frame);
        } catch (RuntimeException failure) {
            frame.release();
            ctx.close().addListener(RakNet.INTERNAL_WRITE_LISTENER);
            throw failure;
        }
        ctx.fireChannelRead(message);
    }

    /**
     * Applies only an initial opposite-role declaration with a valid protocol version.
     *
     * @param ctx receiving endpoint
     * @param frame owned declaration retained by the caller
     * @throws IllegalArgumentException if the declaration is late, repeated, or malformed
     */
    private void accept(final ChannelHandlerContext ctx, final FrameData frame) {
        if (received || gameReceived || !orderedFrame(frame) || frame.getDataSize() != 4) {
            throw new IllegalArgumentException(
                    ConsoleMessages.transport(ctx.channel(), "incompatible"));
        }
        ByteBuf bytes = frame.createData();
        try {
            bytes.skipBytes(1);
            int version = bytes.readUnsignedByte();
            int role = bytes.readUnsignedByte();
            int mode = bytes.readUnsignedByte();
            validateDeclaration(ctx, version, role, mode);
            if (mode == 1) {
                policy.requireOrdered();
            }
            received = true;
            if (CompatibilityPolicy.isOrdered(ctx.channel())) {
                System.out.println(
                        ConsoleMessages.text("ordered_peer", ctx.channel().remoteAddress()));
            }
        } finally {
            bytes.release();
        }
    }

    /**
     * Validates the fixed wire fields without allowing clients to select server policy.
     *
     * @param ctx channel that received the declaration
     * @param version wire protocol version
     * @param role zero for client, one for server
     * @param mode zero for ordinary capability, one for required ordering
     * @throws IllegalArgumentException if the version, role, or policy is invalid
     */
    private void validateDeclaration(
            final ChannelHandlerContext ctx, final int version, final int role, final int mode) {
        if (version != 1 || role != (server ? 0 : 1) || mode > 1 || (server && mode != 0)) {
            throw new IllegalArgumentException(
                    ConsoleMessages.transport(ctx.channel(), "incompatible"));
        }
    }

    /**
     * Requires updated peers and channel-zero delivery for raw and compressed Minecraft data.
     *
     * @param ctx receiving endpoint
     * @param frame incoming data or an unrelated transport control frame
     * @throws IllegalArgumentException if the peer does not meet the required ordering contract
     */
    private void validateGame(final ChannelHandlerContext ctx, final FrameData frame) {
        int id = frame.getPacketId();
        if (id != Constants.RAKNET_GAME_PACKET_ID
                && id != Constants.RAKNET_STREAMING_COMPRESSION_PACKET_ID) {
            return;
        }
        gameReceived = true;
        if (CompatibilityPolicy.isOrdered(ctx.channel()) && (!received || !orderedFrame(frame))) {
            throw new IllegalArgumentException(
                    ConsoleMessages.transport(ctx.channel(), "ordered_required"));
        }
    }

    /**
     * Checks the exact delivery contract rather than trusting the peer's capability advertisement.
     *
     * @param frame complete application frame
     * @return whether reliable channel-zero ordering was used
     */
    private static boolean orderedFrame(final FrameData frame) {
        return !frame.isFragment()
                && frame.getOrderChannel() == 0
                && frame.getReliability() == FramedPacket.Reliability.RELIABLE_ORDERED;
    }
}
