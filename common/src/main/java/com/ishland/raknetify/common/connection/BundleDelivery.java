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
 * Fences bundles and mod messages that join different ordering lanes. Queued game frames can share
 * one FIFO lane until a backlog drains.
 */
final class BundleDelivery extends ChannelDuplexHandler {
    /** Queued writes blocked by an earlier mixed-lane bundle. */
    private final java.util.Deque<Waiting> queue = new ArrayDeque<>();

    /** Distant Horizons may depend on a gameplay prefix, with no dependency in reverse. */
    private final BackgroundDelivery background = new BackgroundDelivery();

    /** Owned queue bytes including a header reservation for empty control messages. */
    private long bytes;

    /** Ordinary writes awaiting delivery per FIFO lane. Slot zero holds all non-FIFO writes. */
    private final int[] pendingCounts = new int[Constants.ORDER_CHANNEL_COUNT + 1];

    /** One bit per occupied slot in the delivery counts. */
    private int pendingLanes;

    /** Bounds completion records when newer datagrams arrive before the oldest one. */
    private static final int MAX_IN_FLIGHT_FENCES = 32;

    /** Begin draining on one lane while there is still room for incoming game traffic. */
    private static final int BURST_QUEUE_FRAMES = 128;

    /** Large packets can create significant pressure even in a short queue. */
    private static final long BURST_QUEUE_BYTES = 2L * 1024 * 1024;

    /** Allow a wider acknowledgement window while bounding completion records. */
    private static final int MAX_IN_FLIGHT_BURST = 512;

    /** The normal reliable gameplay lane used to preserve every cross-lane dependency. */
    private static final int BURST_LANE = 0;

    /** Submitted fenced writes whose ordered prefix has not yet been acknowledged. */
    private final java.util.Deque<Fence> fences = new ArrayDeque<>();

    /** Total framed bytes represented by the outstanding fenced writes. */
    private long fenceBytes;

    /** Guards synchronous completion during a queue drain. */
    private boolean draining;

    /** Keep queued game frames on one lane until the last migrated frame is acknowledged. */
    private boolean burst;

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
        int lane = orderedLane(message);
        if (lane == Constants.BACKGROUND_TERRAIN_CHANNEL) {
            background.write(ctx, (FrameData) message, promise);
            return;
        }
        if (message instanceof SynchronizationLayer.SyncRequest) {
            background.reset();
        }
        writeGameplay(ctx, message, background.track(ctx, promise), lane);
    }

    /**
     * Writes or holds a gameplay packet using dependencies among gameplay packets only.
     *
     * @param ctx outbound transport context
     * @param message owned foreground message
     * @param promise completion tracked by the background dependency queue
     * @param lane reliable FIFO lane, or minus one for a non-FIFO message
     */
    private void writeGameplay(
            final ChannelHandlerContext ctx,
            final Object message,
            final ChannelPromise promise,
            final int lane) {
        boolean mixed = requiresFence(message, promise);
        if (!burst && queue.isEmpty() && canSend(message, mixed, lane)) {
            send(ctx, message, promise, mixed);
            return;
        }
        long size = 32L + (message instanceof FrameData frame ? frame.getDataSize() : 0);
        if (queue.size() >= 8192 || bytes + size > Constants.MAX_QUEUED_SIZE) {
            TooLongFrameException failure =
                    new TooLongFrameException(
                            "Mixed bundle delivery queue exceeded ("
                                    + queue.size()
                                    + " frames, "
                                    + bytes
                                    + " bytes)");
            ReferenceCountUtil.release(message);
            // A failure listener may send a fallback packet synchronously on this channel.
            abort(ctx, failure);
            promise.tryFailure(failure);
            return;
        }
        bytes += size;
        queue.addLast(new Waiting(message, promise, mixed, size));
        if (burst || backlogRequiresBurst()) {
            burst = true;
            drain(ctx);
        }
    }

    /**
     * Starts draining on one lane when either queue dimension shows pressure.
     *
     * @return whether the backlog has reached a frame or byte threshold
     */
    private boolean backlogRequiresBurst() {
        return queue.size() >= BURST_QUEUE_FRAMES || bytes >= BURST_QUEUE_BYTES;
    }

    /**
     * Recognizes local mod boundaries and negotiated mixed bundle envelopes.
     *
     * @param message outgoing frame or control message
     * @param promise completion carrying any local mod boundary
     * @return whether earlier and later deliveries must remain outside this write
     */
    private static boolean requiresFence(final Object message, final ChannelPromise promise) {
        return promise instanceof FencedWritePromise
                || (message instanceof FrameData frame && frame.getPacketId() == BundleWire.MIXED);
    }

    /**
     * Allows adjacent fences to share RakNet's ordering guarantee only on one reliable FIFO lane.
     *
     * @param message next write in submission order
     * @param mixed whether the write has dependencies across lanes
     * @param lane reliable FIFO lane the write will use, or minus one for non-FIFO traffic
     * @return whether the transport can accept the write now
     */
    private boolean canSend(final Object message, final boolean mixed, final int lane) {
        if (fences.isEmpty()) {
            return !mixed || pendingLanes == 0 || (lane >= 0 && lane == solePendingLane());
        }
        return mixed && canPipeline(message, lane);
    }

    /**
     * Bounds the outstanding FIFO prefix by both bytes and acknowledgement records.
     *
     * @param message prospective write
     * @param lane lane the write would use
     * @return whether the active FIFO prefix can accept this write
     */
    private boolean canPipeline(final Object message, final int lane) {
        int limit = burst && lane == BURST_LANE ? MAX_IN_FLIGHT_BURST : MAX_IN_FLIGHT_FENCES;
        return lane >= 0
                && lane == fences.getFirst().lane()
                && fences.size() < limit
                && message instanceof FrameData frame
                && fenceBytes + frame.getDataSize() <= Constants.MAX_QUEUED_SIZE;
    }

    /**
     * Keeps transport controls and unreliable traffic on their routes. Ordered gameplay frames can
     * share a lane before compression.
     *
     * @param message queued application message
     * @param lane original ordered lane, or minus one if it cannot provide FIFO delivery
     * @return whether this message can become part of the queued FIFO sequence
     */
    private static boolean canShareLane(final Object message, final int lane) {
        if (lane < 0 || !(message instanceof FrameData frame)) {
            return false;
        }
        int id = frame.getPacketId();
        return id == Constants.RAKNET_GAME_PACKET_ID
                || id == BundleWire.SINGLE
                || id == BundleWire.MIXED;
    }

    /**
     * Identifies the ordered stream that will reassemble and deliver this frame.
     *
     * @param message outgoing frame or transport control
     * @return FIFO lane, or minus one if this frame needs exclusive delivery
     */
    private static int orderedLane(final Object message) {
        if (!(message instanceof FrameData frame)) {
            return -1;
        }
        var policy = frame.getReliability();
        int lane = frame.getOrderChannel();
        return policy.isReliable
                        && policy.isOrdered
                        && !policy.isSequenced
                        && lane >= 0
                        && lane < Constants.ORDER_CHANNEL_COUNT
                ? lane
                : -1;
    }

    /**
     * Finds the lane whose FIFO ordering covers every outstanding ordinary write.
     *
     * @return the sole pending FIFO lane or minus one when none qualifies
     */
    private int solePendingLane() {
        return Integer.bitCount(pendingLanes) == 1
                ? Integer.numberOfTrailingZeros(pendingLanes) - 1
                : -1;
    }

    /**
     * Selects the stream before compression so both peers use the same per-lane dictionary.
     * Eligibility is checked again against actual frames when the write reaches this handler.
     * Background terrain remains separate from fences.
     *
     * @param pipeline channel containing the delivery handler
     * @return a current reliable FIFO lane, or the default lane for independent predecessors
     */
    static int preferredFenceLane(final io.netty.channel.ChannelPipeline pipeline) {
        ChannelHandlerContext context = pipeline.context(BundleDelivery.class);
        if (context == null || !context.executor().inEventLoop()) {
            return 0;
        }
        BundleDelivery delivery = (BundleDelivery) context.handler();
        int lane =
                delivery.fences.isEmpty()
                        ? delivery.solePendingLane()
                        : delivery.fences.getLast().lane();
        return Math.max(0, lane);
    }

    /**
     * Tracks ordinary deliveries and preserves submission order for fenced write completions.
     *
     * @param ctx transport context
     * @param message owned write
     * @param promise caller completion
     * @param mixed whether this write fences traffic on other channels
     */
    private void send(
            final ChannelHandlerContext ctx,
            final Object message,
            final ChannelPromise promise,
            final boolean mixed) {
        if (mixed) {
            sendFence(ctx, message, promise.unvoid());
            return;
        }
        int slot = orderedLane(message) + 1;
        pendingCounts[slot]++;
        pendingLanes |= 1 << slot;
        ctx.write(message, promise.unvoid())
                .addListener(result -> {
                    if (--pendingCounts[slot] == 0) {
                        pendingLanes &= ~(1 << slot);
                    }
                    if (!closed && !result.isSuccess() && (!queue.isEmpty() || !fences.isEmpty())) {
                        abort(ctx, Objects.requireNonNull(result.cause()));
                    } else {
                        releaseDelivered(ctx);
                    }
                });
    }

    /**
     * Records a fence before forwarding it so a synchronous transport completion sees its entry.
     *
     * @param ctx transport context on the owning event loop
     * @param message owned frame or exclusive control
     * @param promise application completion released in submission order
     */
    private void sendFence(
            final ChannelHandlerContext ctx, final Object message, final ChannelPromise promise) {
        if (!promise.setUncancellable()) {
            ReferenceCountUtil.release(message);
            abort(ctx, Objects.requireNonNull(promise.cause()));
            return;
        }
        int lane = orderedLane(message);
        int size = message instanceof FrameData frame ? frame.getDataSize() : 0;
        var transport = ctx.newPromise();
        fences.addLast(new Fence(lane, size, transport, promise));
        fenceBytes += size;
        ctx.write(message, transport).addListener(result -> finishFence(ctx, result));
    }

    /**
     * Publishes the acknowledged prefix. A later ACK cannot release a gap or the next lane.
     *
     * @param ctx transport context owning pending completions
     * @param result individual frame delivery, including every fragment
     */
    private void finishFence(final ChannelHandlerContext ctx, final Future<?> result) {
        if (closed) {
            return;
        }
        if (!result.isSuccess()) {
            abort(ctx, Objects.requireNonNull(result.cause()));
            return;
        }
        releaseDelivered(ctx);
    }

    /**
     * Holds even acknowledged fences while a preceding fragment on their lane is outstanding. A
     * queued sequence ends when no queued writes or fences remain.
     *
     * @param ctx transport context owning the pending writes
     */
    private void releaseDelivered(final ChannelHandlerContext ctx) {
        while (pendingLanes == 0
                && !fences.isEmpty()
                && fences.getFirst().transport().isSuccess()) {
            Fence next = fences.removeFirst();
            fenceBytes -= next.size();
            next.promise().trySuccess();
        }
        if (queue.isEmpty() && fences.isEmpty()) {
            burst = false;
        }
        drain(ctx);
    }

    /**
     * Releases queued traffic in submission order, stopping at each active cross-lane boundary.
     *
     * @param ctx connection owning every queued write
     */
    private void drain(final ChannelHandlerContext ctx) {
        if (closed || draining || queue.isEmpty()) {
            return;
        }
        draining = true;
        try {
            while (!queue.isEmpty()) {
                if (!drainNext(ctx)) {
                    break;
                }
            }
            ctx.flush();
        } finally {
            draining = false;
        }
    }

    /**
     * Moves the queue head to the transport when its predecessors make its route safe.
     *
     * @param ctx connection owning the queue
     * @return whether the head was submitted
     */
    private boolean drainNext(final ChannelHandlerContext ctx) {
        final Waiting next = queue.getFirst();
        int lane = orderedLane(next.message());
        boolean share = burst && canShareLane(next.message(), lane);
        boolean ordered = next.mixed() || share;
        if (!canSend(next.message(), ordered, share ? BURST_LANE : lane)) {
            return false;
        }
        queue.removeFirst();
        bytes -= next.bytes();
        if (share) {
            ((FrameData) next.message()).setOrderChannel(BURST_LANE);
        }
        send(ctx, next.message(), next.promise(), ordered);
        return true;
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
        background.close(cause);
        while (!fences.isEmpty()) {
            fences.removeFirst().promise().tryFailure(cause);
        }
        fenceBytes = 0;
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
     * One sent fence, retained only until it and its predecessors have completed.
     *
     * @param lane reliable ordered lane, or minus one for an exclusive write
     * @param size framed bytes charged to the in-flight limit
     * @param transport actual transport completion
     * @param promise caller completion waiting for earlier fences
     */
    private record Fence(int lane, int size, ChannelPromise transport, ChannelPromise promise) {}

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
