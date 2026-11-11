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
import com.ishland.raknetify.common.util.MathUtil;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import network.ycc.raknet.frame.FrameData;
import network.ycc.raknet.packet.FramedPacket;

import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;

/**
 * Wraps Minecraft payloads in RakNet frames and selects their ordering or reliability policy. The
 * channel event loop owns negotiation and pending writes. Override registration is synchronized
 * because protocol handlers can be installed from another thread.
 */
public class RakNetSimpleMultiChannelCodec extends ChannelDuplexHandler {

    /** Pipeline name used to locate the Minecraft-to-RakNet framing handler. */
    public static final String NAME = "raknetify-simple-multi-channel-data-codec";

    /** Local outbound marker requesting multichannel negotiation rather than a wire payload. */
    public static final Object SIGNAL_START_MULTICHANNEL = new Object();

    /** Clientbound group under construction from consecutive vanilla writes. */
    private final OutboundBundle bundle = new OutboundBundle();

    /** Frame identifier used to wrap and recognize Minecraft payloads. */
    private final int packetId;

    /**
     * Creates a framing codec before any protocol-specific channel rules are installed.
     *
     * @param packetId RakNet frame identifier reserved for Minecraft payloads
     */
    public RakNetSimpleMultiChannelCodec(final int packetId) {
        this.packetId = packetId;
    }

    /** Immutable classifier snapshot published after registration changes, in precedence order. */
    private volatile OverrideHandler[] handlers = new OverrideHandler[0];

    /** Serializes rare registration changes without locking the packet-processing path. */
    private final Object handlersLock = new Object();

    /**
     * Appends an override after the existing rules. Earlier nonzero results take precedence.
     *
     * @param handler packet classifier to consult during encoding
     * @return this codec for chained registration
     */
    public RakNetSimpleMultiChannelCodec addHandler(final OverrideHandler handler) {
        synchronized (handlersLock) {
            OverrideHandler[] updated = Arrays.copyOf(handlers, handlers.length + 1);
            updated[updated.length - 1] = handler;
            handlers = updated;
        }
        return this;
    }

    /**
     * Removes a classifier when its protocol phase or integration is no longer active.
     *
     * @param handler previously registered classifier to remove
     */
    public void removeHandler(final OverrideHandler handler) {
        synchronized (handlersLock) {
            int index = Arrays.asList(handlers).indexOf(handler);
            if (index < 0) {
                return;
            }
            OverrideHandler[] updated = new OverrideHandler[handlers.length - 1];
            System.arraycopy(handlers, 0, updated, 0, index);
            System.arraycopy(handlers, index + 1, updated, index, updated.length - index);
            handlers = updated;
        }
    }

    /**
     * Finds the first registered classifier assignable to the requested type.
     *
     * @param <T> classifier type expected by the caller
     * @param clazz runtime type used to identify the classifier
     * @return the first matching classifier, or null when none is installed
     */
    public <T> @Nullable T getHandler(final Class<T> clazz) {
        for (OverrideHandler handler : handlers) {
            if (clazz.isInstance(handler)) {
                return clazz.cast(handler);
            }
        }
        return null;
    }

    /** Whether the negotiation marker has completed and channel overrides may affect delivery. */
    private boolean isMultichannelEnabled;

    /** Whether Minecraft writes must wait for the multichannel negotiation marker. */
    private boolean queuePendingWrites;

    /** Single-channel packets whose delivery must precede independently ordered traffic. */
    private int pendingSingleChannelWrites;

    /** Whether the current negotiation marker has completed transport delivery. */
    private boolean negotiationAcknowledged;

    /** Owned encoded frames and their completion promises awaiting negotiation completion. */
    private final Queue<PendingWrite> pendingWrites = new ArrayDeque<>();

    /**
     * Fails outstanding negotiation-delayed writes and releases their retained frames.
     *
     * @param ctx context being removed from the pipeline
     * @throws Exception if superclass removal processing fails
     */
    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) throws Exception {
        super.handlerRemoved(ctx);
        final var failure = new IllegalStateException("Channel closed");
        bundle.fail(failure);
        failPendingWrites(failure);
    }

    /**
     * Handles local negotiation markers and transfers Minecraft buffers into routed frames.
     * Consumed payload buffers are released after encoding. Unrelated pipeline messages pass
     * through.
     *
     * @param ctx outbound context used for allocation and frame delivery
     * @param msg Minecraft payload, local control marker, or unrelated pipeline message
     * @param promise completion notification for the outgoing write
     * @throws Exception if forwarding through the superclass fails
     */
    @Override
    public void write(
            final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
            throws Exception {
        if (ignoreControl(ctx, msg)) {
            promise.trySuccess();
            return;
        }
        if (msg == SIGNAL_START_MULTICHANNEL) {
            startMultichannel(ctx, promise);
            return;
        }
        if (msg instanceof SynchronizationLayer.SyncRequest) {
            if (this.isMultichannelEnabled) {
                if (Constants.DEBUG) {
                    System.out.println(
                            "Raknetify: [MultiChannellingDataCodec] Stopped multichannel");
                }
                this.isMultichannelEnabled = false;
                super.write(ctx, msg, promise);
            } else {
                promise.setSuccess();
            }
            return; // discard sync request when multichannel is not active
        }

        if (msg instanceof ByteBuf buf && buf.isReadable()) {
            try {
                writePayload(ctx, buf, promise);
            } catch (RuntimeException failure) {
                bundle.fail(failure);
                ctx.close().addListener(network.ycc.raknet.RakNet.INTERNAL_WRITE_LISTENER);
                throw failure;
            } finally {
                buf.release();
            }
            return;
        }

        super.write(ctx, msg, promise);
    }

    /**
     * Keeps strict sessions out of multichannel negotiation and destructive phase resets.
     *
     * @param ctx connection owning the delivery policy
     * @param message local outbound control or application payload
     * @return whether the local control has no meaning in ordered compatibility mode
     */
    private static boolean ignoreControl(final ChannelHandlerContext ctx, final Object message) {
        return CompatibilityPolicy.isOrdered(ctx.channel())
                && (message == SIGNAL_START_MULTICHANNEL
                        || message instanceof SynchronizationLayer.SyncRequest);
    }

    /**
     * Starts channel negotiation and holds writes until the ordered marker has been delivered.
     *
     * @param ctx outbound context used to send the marker on channel seven
     * @param promise notification for accepting the local start request, not the peer
     *     acknowledgement
     */
    private void startMultichannel(final ChannelHandlerContext ctx, final ChannelPromise promise) {
        promise.trySuccess();
        if (this.isMultichannelEnabled) {
            return;
        }
        if (!this.isMultichannelAvailable()) {
            System.out.println(
                    "Raknetify: [MultiChannellingDataCodec] Failed to start multichannel: not"
                            + " available");
            return;
        }
        final ByteBuf buf = ctx.alloc().buffer(1).writeByte(0);
        try {
            final FrameData frameData =
                    FrameData.create(ctx.alloc(), Constants.RAKNET_PING_PACKET_ID, buf);
            frameData.setOrderChannel(7);
            this.negotiationAcknowledged = false;
            this.queuePendingWrites = true;
            ctx.write(frameData)
                    .addListener(future -> {
                        if (!future.isSuccess()) {
                            failPendingWrites(future.cause());
                            ctx.close()
                                    .addListener(
                                            io.netty.channel.ChannelFutureListener
                                                    .FIRE_EXCEPTION_ON_FAILURE);
                            return;
                        }
                        negotiationAcknowledged = true;
                        finishNegotiation(ctx);
                    });
        } finally {
            buf.release();
        }
    }

    /**
     * Tracks every pre-negotiation packet so a marker ACK cannot bypass an earlier lost frame.
     *
     * @param ctx outbound context forwarding the owned frame
     * @param frame encoded packet whose delivery may guard the next ordering phase
     * @param promise original application completion
     * @throws Exception if downstream forwarding fails
     */
    private void writeFrame(
            final ChannelHandlerContext ctx, final FrameData frame, final ChannelPromise promise)
            throws Exception {
        final ChannelPromise completion = promise.unvoid();
        if (!isMultichannelEnabled) {
            pendingSingleChannelWrites++;
            completion.addListener(future -> {
                pendingSingleChannelWrites--;
                if (!future.isSuccess() && queuePendingWrites) {
                    failPendingWrites(future.cause());
                    ctx.close().addListener(network.ycc.raknet.RakNet.INTERNAL_WRITE_LISTENER);
                    return;
                }
                finishNegotiation(ctx);
            });
        }
        super.write(ctx, frame, completion);
    }

    /**
     * Opens independent channels only after both the marker and all prior packets are delivered.
     *
     * @param ctx context draining retained application frames in submission order
     */
    private void finishNegotiation(final ChannelHandlerContext ctx) {
        if (!queuePendingWrites || !negotiationAcknowledged || pendingSingleChannelWrites != 0) {
            return;
        }
        isMultichannelEnabled = true;
        if (Constants.DEBUG) {
            System.out.println("Raknetify: [MultiChannellingDataCodec] Started multichannel");
        }
        flushPendingWrites(ctx);
        ctx.flush();
    }

    /**
     * Removes and releases each held frame before notifying its caller, including already cancelled
     * writes. Emptying the queue also prevents a late marker acknowledgement from forwarding
     * released frames.
     *
     * @param failure negotiation or channel-removal cause preserved on every unfinished write
     */
    private void failPendingWrites(final Throwable failure) {
        PendingWrite pendingWrite;
        while ((pendingWrite = this.pendingWrites.poll()) != null) {
            pendingWrite.frameData.release();
            pendingWrite.promise.tryFailure(failure);
        }
    }

    /**
     * Classifies each encoded write while its native packet capture is still active. An updated
     * client receives a complete vanilla bundle in a single reliable envelope.
     *
     * @param ctx outbound application connection
     * @param buf encoded Minecraft packet owned by the caller
     * @param promise original packet completion
     * @throws Exception if transport forwarding fails
     */
    private void writePayload(
            final ChannelHandlerContext ctx, final ByteBuf buf, final ChannelPromise promise)
            throws Exception {
        if (CompatibilityPolicy.isOrdered(ctx.channel())) {
            writeFrame(ctx, FrameData.create(ctx.alloc(), packetId, buf), promise);
            return;
        }
        final int route = getChannelOverride(buf, !isMultichannelEnabled);
        if (route == Integer.MIN_VALUE) {
            delimiter(ctx, buf, promise);
            return;
        }
        final int lane = isMultichannelEnabled || queuePendingWrites ? route : 0;
        if (bundle.active()) {
            bundle.append(buf, lane, promise);
            return;
        }
        final FrameData frame = FrameData.create(ctx.alloc(), packetId, buf);
        if (lane >= 0) {
            frame.setOrderChannel(lane);
        } else {
            frame.setReliability(
                    lane == -1
                            ? FramedPacket.Reliability.RELIABLE
                            : FramedPacket.Reliability.UNRELIABLE);
        }
        emit(ctx, frame, promise);
    }

    /**
     * Opens or closes an advertised clientbound bundle. Older peers retain their historical
     * per-member delivery. The vanilla play delimiter has no fields and uses packet ID zero.
     *
     * @param ctx outbound game context
     * @param buf classified delimiter bytes
     * @param promise delimiter's original completion
     * @throws Exception if the completed envelope cannot be delivered
     */
    private void delimiter(
            final ChannelHandlerContext ctx, final ByteBuf buf, final ChannelPromise promise)
            throws Exception {
        if (!Boolean.TRUE.equals(ctx.channel().attr(BundleWire.PEER).get())
                || buf.readableBytes() != 1
                || buf.getByte(buf.readerIndex()) != 0) {
            promise.trySuccess();
        } else if (bundle.active()) {
            final var delivery = ctx.newPromise();
            emit(ctx, bundle.finish(ctx, promise, delivery), delivery);
        } else {
            bundle.open(ctx, buf, promise);
        }
    }

    /**
     * Preserves the negotiation barrier for ordinary packets and completed bundles alike.
     *
     * @param ctx outbound application context
     * @param frame completed frame whose ownership transfers to the queue or transport
     * @param promise transport delivery completion
     * @throws Exception if the outbound pipeline rejects the write
     */
    private void emit(
            final ChannelHandlerContext ctx, final FrameData frame, final ChannelPromise promise)
            throws Exception {
        if (queuePendingWrites) {
            pendingWrites.add(new PendingWrite(frame, promise));
        } else {
            writeFrame(ctx, frame, promise);
        }
    }

    /**
     * Resumes ordinary writes and drains retained frames in their original submission order.
     * Forwarding failures are reported to the pipeline.
     *
     * @param ctx outbound context that receives the queued frames
     */
    private void flushPendingWrites(final ChannelHandlerContext ctx) {
        this.queuePendingWrites = false;
        PendingWrite pendingWrite;
        while ((pendingWrite = this.pendingWrites.poll()) != null) {
            try {
                super.write(ctx, pendingWrite.frameData, pendingWrite.promise);
            } catch (Throwable t) {
                ctx.fireExceptionCaught(t);
            }
        }
    }

    /**
     * Checks whether any protocol classifier has been installed for negotiation.
     *
     * @return true when at least one override handler is registered
     */
    protected boolean isMultichannelAvailable() {
        return handlers.length != 0;
    }

    /**
     * Consults classifiers in registration order and uses the first nonzero decision.
     *
     * @param buf Minecraft payload inspected without consuming its reader index
     * @param suppressWarning whether classifiers should suppress unknown-packet diagnostics
     * @return ordering channel, -1 for reliable unordered, -2 for unreliable, or Integer.MIN_VALUE
     *     to discard
     */
    protected int getChannelOverride(final ByteBuf buf, final boolean suppressWarning) {
        for (OverrideHandler handler : handlers) {
            final int override = handler.getChannelOverride(buf, suppressWarning);
            if (override != 0) {
                return override;
            }
        }
        return 0;
    }

    /**
     * Unwraps complete Minecraft frames, consumes negotiation markers, and forwards other traffic.
     * A forwarded frame retains its own reference before the input reference is released.
     *
     * @param ctx inbound context receiving decoded payloads
     * @param msg incoming frame or unrelated pipeline message
     * @throws Exception if superclass inbound processing fails
     */
    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (msg instanceof FrameData packet && !packet.isFragment() && packet.getDataSize() > 0) {
            try {
                if (packetId == packet.getPacketId()) {
                    ctx.fireChannelRead(packet.createData().skipBytes(1));
                } else if (packet.getPacketId() == BundleWire.SINGLE
                        || packet.getPacketId() == BundleWire.MIXED) {
                    BundleWire.unpack(ctx, packet);
                } else if (packet.getPacketId() == Constants.RAKNET_PING_PACKET_ID) {
                    return;
                } else {
                    ctx.fireChannelRead(packet.retain());
                }
            } finally {
                packet.release();
            }
            return;
        }
        super.channelRead(ctx, msg);
    }

    /** Classifies a Minecraft packet without changing the payload reader index. */
    @FunctionalInterface
    public interface OverrideHandler {
        /**
         * Selects a delivery policy while allowing later classifiers to handle the default channel.
         *
         * @param buf payload beginning with the Minecraft packet ID, which must remain unconsumed
         * @param suppressWarning whether an unknown packet should be inspected without a diagnostic
         * @return zero to defer, a positive ordering channel, -1 for reliable unordered, -2 for
         *     unreliable, or Integer.MIN_VALUE to discard the packet
         */
        int getChannelOverride(ByteBuf buf, boolean suppressWarning);
    }

    /** Uses a protocol-specific packet-ID table and warns once for each unknown packet ID. */
    public static class PacketIdBasedOverrideHandler implements OverrideHandler {

        /**
         * Packet IDs already reported as unknown, preventing repeated diagnostics on a connection.
         */
        private final IntOpenHashSet unknownPacketIds = new IntOpenHashSet();

        /** Private copy of the packet-ID routes supplied for this protocol direction. */
        private final Int2IntOpenHashMap channelMapping;

        /** Human-readable protocol and direction included in unknown-packet diagnostics. */
        private final String descriptiveProtocolStatus;

        /**
         * Snapshots the routing table so later caller changes do not alter an active classifier.
         *
         * @param channelMapping packet IDs mapped to ordering channels or special delivery policies
         * @param descriptiveProtocolStatus protocol and direction to identify in diagnostics
         */
        public PacketIdBasedOverrideHandler(
                final Int2IntMap channelMapping, final String descriptiveProtocolStatus) {
            this.channelMapping = new Int2IntOpenHashMap(channelMapping);
            this.descriptiveProtocolStatus = descriptiveProtocolStatus;
        }

        /**
         * Reads the packet ID from a slice and looks up its delivery policy. An explicit unknown
         * sentinel falls back to channel seven, with at most one warning per ID.
         *
         * @param buf Minecraft payload whose reader index remains unchanged
         * @param suppressWarning whether an unknown ID should be handled silently
         * @return table policy, or channel seven for an Integer.MAX_VALUE table entry
         */
        @Override
        public int getChannelOverride(final ByteBuf buf, final boolean suppressWarning) {
            final ByteBuf slice = buf.slice();
            final int packetId = MathUtil.readVarInt(slice);
            final int override = this.channelMapping.get(packetId);
            if (override == Integer.MAX_VALUE) {
                if (!suppressWarning) {
                    if (this.unknownPacketIds.add(packetId)) {
                        System.err.println(
                                "Raknetify: Unknown packet id %d for %s"
                                        .formatted(packetId, descriptiveProtocolStatus));
                    }
                }
                return 7;
            }
            return override;
        }
    }

    /**
     * Keeps an owned frame paired with the write promise while negotiation delays delivery.
     *
     * @param frameData encoded frame released on removal or transferred when the queue drains
     * @param promise completion notification belonging to the original Minecraft write
     */
    private record PendingWrite(FrameData frameData, ChannelPromise promise) {}
}
