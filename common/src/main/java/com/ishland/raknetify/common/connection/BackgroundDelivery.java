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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.TooLongFrameException;

import network.ycc.raknet.frame.FrameData;

import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/** One-way delivery dependencies for Distant Horizons session messages. */
final class BackgroundDelivery {
    /** Gameplay since the latest DH control write. */
    private GameplayPrefix current = new GameplayPrefix();

    /** Sealed groups of gameplay writes that have not completed as a contiguous prefix. */
    private final Deque<GameplayPrefix> predecessors = new ArrayDeque<>();

    /** DH messages held by a control write, in the order DH submitted them. */
    private final Deque<Waiting> waiting = new ArrayDeque<>();

    /** Memory reserved by retained frames, including their bookkeeping. */
    private long bytes;

    /** Prevents an inline write completion from draining the queue out of order. */
    private boolean draining;

    /**
     * Records a gameplay submission, including writes still held by the gameplay queue.
     *
     * @param ctx transport whose event loop owns the queues
     * @param promise original gameplay completion
     * @return gameplay completion with prefix accounting
     */
    ChannelPromise track(final ChannelHandlerContext ctx, final ChannelPromise promise) {
        GameplayPrefix group = current;
        group.pending++;
        return promise.unvoid()
                .addListener(result -> {
                    if (--group.pending == 0) {
                        advance(ctx);
                    }
                });
    }

    /**
     * Passes DH terrain immediately unless an earlier DH control still needs gameplay state. Every
     * forwarded DH write uses the same reliable FIFO lane and normal send capacity.
     *
     * @param ctx transport context bypassing the gameplay queue
     * @param frame owned DH frame
     * @param promise completion, marked for control writes requiring a gameplay prefix
     */
    void write(
            final ChannelHandlerContext ctx, final FrameData frame, final ChannelPromise promise) {
        if (promise instanceof FencedWritePromise && current.pending != 0) {
            predecessors.addLast(current);
            current = new GameplayPrefix();
        }
        if (predecessors.isEmpty() && waiting.isEmpty() && !draining) {
            ctx.write(frame, promise);
            return;
        }
        long size = 32L + frame.getDataSize();
        if (bytes + size > Constants.MAX_QUEUED_SIZE) {
            frame.release();
            promise.tryFailure(
                    new TooLongFrameException("Distant Horizons delivery queue exceeded"));
            return;
        }
        bytes += size;
        waiting.addLast(new Waiting(frame, promise, predecessors.peekLast(), size));
    }

    /**
     * Releases only the acknowledged prefix existing at each control write's submission.
     *
     * @param ctx transport context used to forward ready frames
     */
    private void advance(final ChannelHandlerContext ctx) {
        while (!predecessors.isEmpty() && predecessors.getFirst().pending == 0) {
            predecessors.removeFirst().ready = true;
        }
        if (draining || waiting.isEmpty() || !waiting.getFirst().ready()) {
            return;
        }
        draining = true;
        try {
            Waiting next;
            while ((next = waiting.peekFirst()) != null && next.ready()) {
                waiting.removeFirst();
                bytes -= next.size();
                ctx.write(next.frame(), next.promise());
            }
            ctx.flush();
        } finally {
            draining = false;
        }
    }

    /** Invalidates held DH traffic along with the previous world's transport cursors. */
    void reset() {
        discard(null);
    }

    /**
     * Releases held traffic when the connection closes.
     *
     * @param cause error reported to pending DH writes
     */
    void close(final Throwable cause) {
        discard(cause);
    }

    /**
     * Discards retained frames on close or after a world or protocol transition.
     *
     * @param cause failure to report, or null for the transport's successful reset semantics
     */
    private void discard(final @Nullable Throwable cause) {
        predecessors.clear();
        current = new GameplayPrefix();
        Waiting next;
        while ((next = waiting.pollFirst()) != null) {
            bytes -= next.size();
            next.frame().release();
            if (cause == null) {
                next.promise().trySuccess();
            } else {
                next.promise().tryFailure(cause);
            }
        }
    }

    /** Mutable completion state for the gameplay submitted between two controls. */
    private static final class GameplayPrefix {
        /** Writes whose downstream promises have not completed. */
        private int pending;

        /** This group and all earlier gameplay groups are complete. */
        private boolean ready;
    }

    /**
     * A held DH write awaiting the gameplay prefix that preceded it.
     *
     * @param frame owned outbound frame
     * @param promise original write completion
     * @param predecessor required gameplay group, if any
     * @param size retained byte reservation
     */
    private record Waiting(
            FrameData frame,
            ChannelPromise promise,
            @Nullable GameplayPrefix predecessor,
            long size) {
        /**
         * Checks whether the transport can accept this background write.
         *
         * @return whether earlier gameplay dependencies have completed
         */
        private boolean ready() {
            return predecessor == null || predecessor.ready;
        }
    }
}
