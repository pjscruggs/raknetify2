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
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.util.concurrent.Future;

import network.ycc.raknet.frame.FrameData;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Collects one contiguous sequence emitted by vanilla's unbundler on the application event loop.
 */
final class OutboundBundle {
    /** Owned envelope buffer between delimiters. */
    private @Nullable ByteBuf bytes;

    /** Original packet completions, resolved only when the complete envelope is delivered. */
    private final List<ChannelPromise> promises = new ArrayList<>();

    /** Ordered lanes required by members after protocol classification. */
    private int lanes;

    /** Number of vanilla members following the opening delimiter. */
    private int count;

    /**
     * Reports whether a vanilla opening delimiter still requires its matching end.
     *
     * @return true while collecting member packets
     */
    boolean active() {
        return bytes != null;
    }

    /**
     * Captures an opening delimiter without transferring ownership of the caller's buffer.
     *
     * @param ctx connection allocator
     * @param delimiter encoded delimiter packet
     * @param promise original delimiter completion
     */
    void open(
            final ChannelHandlerContext ctx,
            final ByteBuf delimiter,
            final ChannelPromise promise) {
        bytes = ctx.alloc().buffer(256, Constants.MAX_QUEUED_SIZE - 1);
        bytes.writeByte(delimiter.readableBytes())
                .writeBytes(delimiter, delimiter.readerIndex(), delimiter.readableBytes());
        promises.add(promise);
        count = 0;
        lanes = 0;
    }

    /**
     * Copies an encoded packet and records the ordering domain it must share with surrounding
     * writes.
     *
     * @param packet vanilla packet bytes left owned by the caller
     * @param lane selected route, negative for an independently deliverable packet
     * @param promise original member completion
     * @throws TooLongFrameException if the vanilla count or transport byte ceiling is exceeded
     */
    void append(final ByteBuf packet, final int lane, final ChannelPromise promise) {
        final ByteBuf data = Objects.requireNonNull(bytes);
        if (++count > BundleWire.MAX_MEMBERS
                || packet.readableBytes() + (long) Integer.BYTES > data.maxWritableBytes()) {
            throw new TooLongFrameException("Minecraft bundle exceeds transport limits");
        }
        if (lane >= 0) {
            lanes |= 1 << lane;
        }
        data.writeInt(packet.readableBytes())
                .writeBytes(packet, packet.readerIndex(), packet.readableBytes());
        promises.add(promise);
    }

    /**
     * Transfers one completed bundle frame and associates all original writes with its delivery.
     *
     * @param ctx frame allocator
     * @param closing original closing delimiter completion
     * @param delivery independent promise completed by the transport
     * @return owned envelope on its member lane or a fenced lane for a mixed group
     */
    FrameData finish(
            final ChannelHandlerContext ctx,
            final ChannelPromise closing,
            final ChannelPromise delivery) {
        final ByteBuf data = Objects.requireNonNull(bytes);
        final int id = Integer.bitCount(lanes) > 1 ? BundleWire.MIXED : BundleWire.SINGLE;
        FrameData frame = FrameData.create(ctx.alloc(), id, data);
        frame.setOrderChannel(lanes == 0 ? 0 : Integer.numberOfTrailingZeros(lanes));
        promises.add(closing);
        List<ChannelPromise> completions = List.copyOf(promises);
        delivery.addListener(result -> complete(completions, result));
        clear();
        return frame;
    }

    /**
     * Mirrors one envelope's transport outcome to all vanilla writes it represents.
     *
     * @param completions detached original write completions
     * @param result envelope delivery or terminal failure
     */
    private static void complete(final List<ChannelPromise> completions, final Future<?> result) {
        for (ChannelPromise completion : completions) {
            if (result.isSuccess()) {
                completion.trySuccess();
            } else {
                completion.tryFailure(Objects.requireNonNull(result.cause()));
            }
        }
    }

    /**
     * Releases an incomplete envelope and fails every retained vanilla write.
     *
     * @param failure terminal connection or encoding error
     */
    void fail(final Throwable failure) {
        final var failed = List.copyOf(promises);
        clear();
        failed.forEach(promise -> promise.tryFailure(failure));
    }

    /** Releases the exclusively owned buffer before the object can collect another bundle. */
    private void clear() {
        if (bytes != null) {
            bytes.release();
            bytes = null;
        }
        promises.clear();
    }
}
