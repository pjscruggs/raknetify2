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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.util.AttributeKey;

import network.ycc.raknet.RakNet;
import network.ycc.raknet.frame.FrameData;
import network.ycc.raknet.packet.FramedPacket;

/**
 * Negotiates clientbound bundle envelopes and expands one validated group into a vanilla bundle.
 */
final class BundleWire {
    /** Optional client announcement consumed by servers that understand bundle envelopes. */
    static final int CAPABILITY = 0xE8;

    /** Bundle whose members use one ordering lane. */
    static final int SINGLE = 0xE9;

    /** Bundle spanning multiple lanes, fenced at the sender before and after transmission. */
    static final int MIXED = 0xEA;

    /** Maximum number of members accepted by the vanilla bundle collector. */
    static final int MAX_MEMBERS = 4096;

    /** Whether this endpoint has advertised its ability to unpack clientbound bundles. */
    static final AttributeKey<Boolean> RECEIVER =
            AttributeKey.valueOf("raknetify2.bundle.receiver");

    /** Whether the remote client explicitly supports this envelope version. */
    static final AttributeKey<Boolean> PEER = AttributeKey.valueOf("raknetify2.bundle.peer");

    /** Prevents construction of the fixed wire format. */
    private BundleWire() {}

    /**
     * Advertises support on the client after the ordinary compatibility declaration.
     *
     * @param ctx context for the outbound reliable channel-zero announcement
     */
    static void advertise(final ChannelHandlerContext ctx) {
        ctx.channel().attr(RECEIVER).set(true);
        ByteBuf bytes = ctx.alloc().buffer(1).writeByte(1);
        try {
            ctx.writeAndFlush(FrameData.create(ctx.alloc(), CAPABILITY, bytes))
                    .addListener(RakNet.INTERNAL_WRITE_LISTENER);
        } finally {
            bytes.release();
        }
    }

    /**
     * Validates the peer's one-time advertisement before enabling an optional encoding.
     *
     * @param ctx receiving server connection
     * @param frame client advertisement owned by the caller
     * @param allowed whether the declaration arrived on the server before application traffic
     * @throws CorruptedFrameException if the declaration is malformed, late or repeated
     */
    static void accept(
            final ChannelHandlerContext ctx, final FrameData frame, final boolean allowed) {
        ByteBuf bytes = frame.createData();
        try {
            if (!allowed
                    || frame.getOrderChannel() != 0
                    || frame.getReliability() != FramedPacket.Reliability.RELIABLE_ORDERED
                    || bytes.readableBytes() != 2
                    || bytes.getUnsignedByte(bytes.readerIndex() + 1) != 1
                    || ctx.channel().attr(PEER).setIfAbsent(true) != null) {
                throw new CorruptedFrameException("Invalid bundle capability");
            }
        } finally {
            bytes.release();
        }
    }

    /**
     * Validates all lengths before exposing the opening delimiter to the vanilla decoder. Each
     * retained slice is transferred to the next handler in the same event-loop callback.
     *
     * @param ctx application decoder context
     * @param frame complete reliable ordered envelope owned by the caller
     * @throws CorruptedFrameException if this endpoint or payload cannot receive a bundle
     */
    static void unpack(final ChannelHandlerContext ctx, final FrameData frame) {
        if (!Boolean.TRUE.equals(ctx.channel().attr(RECEIVER).get())
                || CompatibilityPolicy.isOrdered(ctx.channel())
                || frame.getReliability() != FramedPacket.Reliability.RELIABLE_ORDERED
                || frame.getDataSize() > Constants.MAX_QUEUED_SIZE) {
            throw new CorruptedFrameException("Unexpected Minecraft bundle");
        }
        ByteBuf bytes = frame.createData();
        try {
            bytes.skipBytes(1);
            if (bytes.readableBytes() < 2
                    || bytes.readUnsignedByte() != 1
                    || bytes.getUnsignedByte(bytes.readerIndex()) != 0) {
                throw new CorruptedFrameException("Invalid bundle delimiter length");
            }
            ByteBuf delimiter = bytes.readSlice(1);
            validateMembers(bytes.duplicate());
            ctx.fireChannelRead(delimiter.retainedDuplicate());
            while (bytes.isReadable()) {
                ctx.fireChannelRead(bytes.readRetainedSlice(bytes.readInt()));
            }
            ctx.fireChannelRead(delimiter.retainedDuplicate());
        } finally {
            bytes.release();
        }
    }

    /**
     * Checks each length prefix and the vanilla member limit without changing the decoder cursor.
     *
     * @param bytes independent view of the envelope member list
     * @throws CorruptedFrameException if a length or packet count exceeds the bundle boundaries
     */
    private static void validateMembers(final ByteBuf bytes) {
        int count = 0;
        while (bytes.isReadable()) {
            if (bytes.readableBytes() < Integer.BYTES || ++count > MAX_MEMBERS) {
                throw new CorruptedFrameException(
                        "Invalid Minecraft bundle member count or header");
            }
            int length = bytes.readInt();
            if (length <= 0 || length > bytes.readableBytes()) {
                throw new CorruptedFrameException("Invalid Minecraft bundle member length");
            }
            bytes.skipBytes(length);
        }
    }
}
