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

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;

import network.ycc.raknet.frame.FrameData;

import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Fences only bundles that join different ordering lanes. Single-lane traffic can run concurrently.
 */
final class BundleDelivery extends ChannelDuplexHandler {
    /** Queued writes blocked by an earlier mixed-lane bundle. */
    private final java.util.Deque<Waiting> queue = new ArrayDeque<>();

    /** Owned queue bytes including a header reservation for empty control messages. */
    private long bytes;

    /** Transport writes awaiting delivery before a mixed-lane bundle may start. */
    private int pending;

    /** Whether an emitted mixed bundle is awaiting acknowledgement. */
    private boolean fenced;

    /** Guards synchronous completion during a queue drain. */
    private boolean draining;

    /** Rejects writes and late callbacks after a terminal failure or removal. */
    private boolean closed;

    /**
     * Forwards ordinary traffic immediately except across a pending mixed-bundle boundary.
     *
     * @param ctx server transport context
     * @param message owned game frame or phase control
     * @param promise original write completion
     */
    @Override
    public void write(
            final ChannelHandlerContext ctx, final Object message, final ChannelPromise promise) {
        if (closed) {
            ReferenceCountUtil.release(message);
            promise.tryFailure(new ClosedChannelException());
            return;
        }
        boolean mixed =
                message instanceof FrameData frame && frame.getPacketId() == BundleWire.MIXED;
        if (queue.isEmpty() && !fenced && (!mixed || pending == 0)) {
            send(ctx, message, promise, mixed);
            return;
        }
        long size = 32L + (message instanceof FrameData frame ? frame.getDataSize() : 0);
        if (queue.size() >= 8192 || bytes + size > Constants.MAX_QUEUED_SIZE) {
            TooLongFrameException failure =
                    new TooLongFrameException("Mixed bundle delivery queue exceeded");
            ReferenceCountUtil.release(message);
            promise.tryFailure(failure);
            abort(ctx, failure);
            return;
        }
        bytes += size;
        queue.addLast(new Waiting(message, promise, mixed, size));
    }

    /**
     * Tracks actual transport delivery, including independently acknowledged fragments.
     *
     * @param ctx context transferring message ownership
     * @param message queued or immediately deliverable packet
     * @param promise original completion
     * @param mixed whether following writes must wait for this delivery
     */
    private void send(
            final ChannelHandlerContext ctx,
            final Object message,
            final ChannelPromise promise,
            final boolean mixed) {
        pending++;
        fenced = mixed;
        ctx.write(message, promise.unvoid()).addListener(result -> delivered(ctx, result, mixed));
    }

    /**
     * Advances waiting traffic once its preceding delivery dependencies are satisfied.
     *
     * @param ctx server event-loop context
     * @param result transport completion
     * @param mixed whether this acknowledgement releases an active fence
     */
    private void delivered(
            final ChannelHandlerContext ctx, final Future<?> result, final boolean mixed) {
        pending--;
        if (closed) {
            return;
        }
        if (!result.isSuccess() && (mixed || !queue.isEmpty())) {
            abort(ctx, Objects.requireNonNull(result.cause()));
            return;
        }
        if (mixed) {
            fenced = false;
        }
        drain(ctx);
    }

    /**
     * Releases queued traffic in submission order, stopping at each active cross-lane boundary.
     *
     * @param ctx connection owning every queued write
     */
    private void drain(final ChannelHandlerContext ctx) {
        if (draining || queue.isEmpty()) {
            return;
        }
        draining = true;
        try {
            while (!queue.isEmpty() && !fenced) {
                final Waiting next = queue.getFirst();
                if (next.mixed() && pending != 0) {
                    break;
                }
                queue.removeFirst();
                bytes -= next.bytes();
                send(ctx, next.message(), next.promise(), next.mixed());
            }
            ctx.flush();
        } finally {
            draining = false;
        }
    }

    /**
     * Closes a session whose required bundle dependencies cannot be delivered.
     *
     * @param ctx failed connection
     * @param failure cause propagated to held writes
     */
    private void abort(final ChannelHandlerContext ctx, final Throwable failure) {
        discard(failure);
        ctx.close().addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }

    /**
     * Releases all held messages once, including writes whose application stopped observing them.
     *
     * @param cause terminal error shared with queued completions
     */
    private void discard(final Throwable cause) {
        closed = true;
        while (!queue.isEmpty()) {
            final Waiting waiting = queue.removeFirst();
            ReferenceCountUtil.release(waiting.message());
            waiting.promise().tryFailure(cause);
        }
        bytes = 0;
    }

    /**
     * Releases waiting writes when the transport closes.
     *
     * @param ctx disconnected context forwarding the event
     */
    @Override
    public void channelInactive(final ChannelHandlerContext ctx) {
        discard(new ClosedChannelException());
        ctx.fireChannelInactive();
    }

    /**
     * Releases waiting writes on explicit pipeline removal.
     *
     * @param ctx removed server-side handler context
     */
    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) {
        discard(new ClosedChannelException());
    }

    /**
     * Holds a message until preceding bundle dependencies have completed.
     *
     * @param message owned frame or control
     * @param promise original application completion
     * @param mixed whether the write is a cross-lane bundle
     * @param bytes queue reservation
     */
    private record Waiting(Object message, ChannelPromise promise, boolean mixed, long bytes) {}
}
