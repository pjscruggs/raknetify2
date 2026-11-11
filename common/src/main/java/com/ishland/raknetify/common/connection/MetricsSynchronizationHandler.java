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
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;

import network.ycc.raknet.RakNet;
import network.ycc.raknet.frame.FrameData;
import network.ycc.raknet.packet.FramedPacket;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.TimeUnit;

/**
 * Exchanges diagnostic snapshots with the peer over unreliable RakNet frames. Snapshots carry the
 * sender's timestamp so reordered older reports do not replace newer data. These values describe
 * the remote endpoint and do not control local transport behavior.
 */
public class MetricsSynchronizationHandler extends ChannelDuplexHandler {

    // Legacy and solicited snapshots share this payload layout.
    // Packet format:
    // byte: version (currently 0x00)
    // long: packet sent time (used to filter out old packets)
    // int: buffer size in bytes
    // int: burst size
    // double: error rate
    // int: tx
    // int: rx

    /** Wire-layout version accepted by this metrics exchange implementation. */
    private static final byte VERSION = 0x00;

    /** A one-byte request ignored by older readers of the versioned envelope. */
    private static final int REQUEST = 1;

    /** Reply distinguished from a legacy unsolicited snapshot to prevent reply loops. */
    private static final int RESPONSE = 2;

    /** Reliable single-byte reply declining all requests on this connection. */
    private static final int REFUSED = 3;

    /** An envelope contains either one control byte or one control byte and 32 snapshot bytes. */
    private static final int SNAPSHOT_SIZE = 33;

    /** Local configuration fixed independently of any packets from a peer. */
    private final DiagnosticsPolicy policy;

    /** True after the first local diagnostic read, including when the peer uses legacy pushes. */
    private volatile boolean wanted;

    /** Only a solicited answer enables polling. Legacy peers push independently. */
    private volatile Peer peer = Peer.UNKNOWN;

    /** Peer behavior observed on this connection. */
    private enum Peer {
        /** No compatible response has arrived. */
        UNKNOWN,
        /** The peer answered a local request. */
        POLLING,
        /** The peer sends version-zero reports on its own. */
        LEGACY,
        /** The peer declined for the lifetime of this connection. */
        REFUSED
    }

    /** Owned by the transport loop until the peer answers or declines. */
    private boolean awaitingReport;

    /** Allows each responder to send its initial decision reliably exactly once. */
    private boolean answered;

    /** Delivery of the reliable refusal starts the grace period on the transport event loop. */
    private boolean refusalDelivered;

    /** Earliest time a further request can count after delivery or a previous strike. */
    private long nextStrikeAt;

    /** Number of separate post-grace requests observed at least one second apart. */
    private int strikes;

    /** Time of last accepted legacy sample, for limiting unsolicited parsing. */
    private long lastLegacy = System.nanoTime() - TimeUnit.SECONDS.toNanos(1);

    /** Creates a handler with the default accepting-server policy. */
    public MetricsSynchronizationHandler() {
        this(DiagnosticsPolicy.DEFAULT_SERVER);
    }

    /**
     * Retains the endpoint's locally selected permissions.
     *
     * @param policy permissions selected by the local transport role
     */
    MetricsSynchronizationHandler(final DiagnosticsPolicy policy) {
        this.policy = policy;
    }

    /** Minimum interval between requests, and independently between replies. */
    private static final long INTERVAL = TimeUnit.SECONDS.toNanos(1);

    /** Installed transport context, available to a HUD reader outside its event loop. */
    private volatile @Nullable ChannelHandlerContext context;

    /** Monotonic last request time, guarded by this handler's monitor. */
    private long lastRequest = System.nanoTime() - INTERVAL;

    /** Monotonic last reply time, owned by the transport event loop. */
    private long lastReply = System.nanoTime() - INTERVAL;

    /**
     * Records the transport context without scheduling any background traffic.
     *
     * @param ctx installed transport handler context
     */
    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) {
        context = ctx;
    }

    /**
     * Drops the transport context so later viewer reads cannot enqueue writes.
     *
     * @param ctx removed transport context
     */
    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) {
        context = null;
    }

    /** Requests a current peer snapshot at most once per second while a diagnostic view is read. */
    public synchronized void requestSnapshot() {
        final ChannelHandlerContext ctx = context;
        long now = System.nanoTime();
        if (ctx == null) {
            return;
        }
        final boolean first = !wanted;
        wanted = true;
        if (!(peer == Peer.POLLING || (first && peer == Peer.UNKNOWN))
                || now - lastRequest < INTERVAL) {
            return;
        }
        lastRequest = now;
        ctx.executor()
                .execute(() -> {
                    if (!ctx.isRemoved()
                            && ctx.channel().isActive()
                            && peer != Peer.REFUSED
                            && peer != Peer.LEGACY) {
                        awaitingReport = true;
                        control(ctx, REQUEST, first).addListener(RakNet.INTERNAL_WRITE_LISTENER);
                    }
                });
    }

    /**
     * Sends a one-byte negotiation or polling message.
     *
     * @param ctx transport event loop
     * @param type request or refusal
     * @param reliable whether the decision must survive packet loss
     * @return delivery promise for this control frame
     */
    private static ChannelFuture control(
            final ChannelHandlerContext ctx, final int type, final boolean reliable) {
        ByteBuf payload = ctx.alloc().buffer(1).writeByte(type);
        try {
            return send(ctx, payload, reliable);
        } finally {
            payload.release();
        }
    }

    /**
     * Emits a best-effort control frame using the transport's usual flush batching.
     *
     * @param ctx owning transport context
     * @param reliable whether delivery requires acknowledgement
     * @return frame delivery promise
     * @param payload borrowed envelope bytes
     */
    private static ChannelFuture send(
            final ChannelHandlerContext ctx, final ByteBuf payload, final boolean reliable) {
        FrameData frame =
                FrameData.create(ctx.alloc(), Constants.RAKNET_METRICS_SYNC_PACKET_ID, payload);
        frame.setReliability(
                reliable ? FramedPacket.Reliability.RELIABLE : FramedPacket.Reliability.UNRELIABLE);
        return ctx.write(frame);
    }

    /**
     * Sends the latest local queue, burst, retransmission, and packet-rate measurements. The
     * temporary payload is released after an unreliable frame takes ownership of its data.
     *
     * @param ctx installed transport context
     * @param reliable whether this first response confirms support reliably
     * @param version snapshot layout marker understood by the requester
     */
    private void sendSyncPacket(
            final ChannelHandlerContext ctx, final int version, final boolean reliable) {
        long now = System.nanoTime();
        if (!reliable && now - lastReply < INTERVAL) {
            return;
        }
        lastReply = now;
        if (ctx.channel().config() instanceof RakNet.Config config
                && config.getMetrics() instanceof SimpleMetricsLogger logger) {
            ByteBuf buffer = null;
            try {
                buffer = ctx.alloc().buffer(1 + 8 + 4 + 4 + 8 + 4 + 4);
                buffer.writeByte(version);
                buffer.writeLong(System.currentTimeMillis());
                buffer.writeInt(logger.getCurrentQueuedBytes());
                buffer.writeInt(
                        (int)
                                (logger.getMeasureBurstTokens()
                                        + config.getDefaultPendingFrameSets()));
                buffer.writeDouble(logger.getMeasureErrorRate());
                buffer.writeInt(logger.getMeasureTX());
                buffer.writeInt(logger.getMeasureRX());
                send(ctx, buffer, reliable).addListener(RakNet.INTERNAL_WRITE_LISTENER);
            } finally {
                if (buffer != null) {
                    buffer.release();
                }
            }
        }
    }

    /** Initial zero values distinguish an unsupported peer from an accepted all-zero snapshot. */
    private static final RemoteMetrics EMPTY = new RemoteMetrics(0, 0, 0, 0, 0, 0);

    /**
     * Complete peer measurements published together for readers outside the transport event loop.
     */
    private volatile @Nullable RemoteMetrics remote;

    /**
     * Keeps parsing failures and concurrent readers from observing partially replaced statistics.
     *
     * @param time sender timestamp used to reject older snapshots
     * @param queuedBytes peer's queued transport bytes
     * @param burst peer's pending-frame allowance
     * @param errorRate peer's retransmitted-byte fraction
     * @param tx peer's outgoing packets per second
     * @param rx peer's incoming packets per second
     */
    private record RemoteMetrics(
            long time, int queuedBytes, int burst, double errorRate, int tx, int rx) {}

    /**
     * Consumes supported metric frames and rejects stale snapshots without forwarding them. Every
     * consumed frame and its payload view are released even when parsing fails.
     *
     * @param ctx inbound context receiving messages unrelated to metrics
     * @param msg incoming metric frame or other pipeline message
     * @throws Exception if superclass processing of an unrelated message fails
     */
    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (msg instanceof FrameData frameData
                && frameData.getDataSize() > 0
                && frameData.getPacketId() == Constants.RAKNET_METRICS_SYNC_PACKET_ID) {
            ByteBuf byteBuf = null;
            try {
                int size = frameData.getDataSize();
                if (size != 2 && (size != 1 + SNAPSHOT_SIZE || (!policy.share() && !wanted))) {
                    return;
                }
                byteBuf = frameData.createData().skipBytes(1);
                receive(ctx, byteBuf);
            } finally {
                if (byteBuf != null) {
                    byteBuf.release();
                }
                frameData.release();
            }
            return;
        }
        super.channelRead(ctx, msg);
    }

    /**
     * Answers pull requests and continues to serve older viewers that only send push snapshots.
     *
     * @param ctx transport context for a rate-limited response
     * @param bytes borrowed metrics envelope without its outer identifier
     */
    private void receive(final ChannelHandlerContext ctx, final ByteBuf bytes) {
        int version = bytes.readUnsignedByte();
        if (!bytes.isReadable()) {
            handleControl(ctx, version);
        } else if (version == VERSION) {
            receiveLegacy(ctx, bytes);
        } else if (version == RESPONSE && awaitingReport && peer != Peer.REFUSED) {
            awaitingReport = false;
            peer = Peer.POLLING;
            acceptSnapshot(bytes);
        }
    }

    /**
     * Handles control-only messages without accepting trailing bytes.
     *
     * @param ctx transport event loop
     * @param version request or refusal identifier
     */
    private void handleControl(final ChannelHandlerContext ctx, final int version) {
        if (version == REQUEST) {
            answerRequest(ctx);
        }
        if (version == REFUSED && wanted) {
            peer = Peer.REFUSED;
            awaitingReport = false;
            remote = null;
        }
    }

    /**
     * Limits unsolicited legacy samples independently of the peer's sending rate.
     *
     * @param ctx transport event loop
     * @param bytes complete legacy report
     */
    private void receiveLegacy(final ChannelHandlerContext ctx, final ByteBuf bytes) {
        long now = System.nanoTime();
        if (peer == Peer.REFUSED || now - lastLegacy < INTERVAL) {
            return;
        }
        lastLegacy = now;
        peer = Peer.LEGACY;
        if (policy.share()) {
            sendSyncPacket(ctx, VERSION, false);
        }
        if (wanted) {
            acceptSnapshot(bytes);
        }
    }

    /**
     * Enforces the local policy independently of whether the peer cooperates.
     *
     * @param ctx transport event loop owning this connection
     */
    private void answerRequest(final ChannelHandlerContext ctx) {
        boolean first = !answered;
        answered = true;
        if (policy.share()) {
            sendSyncPacket(ctx, RESPONSE, first);
        } else if (first) {
            control(ctx, REFUSED, true)
                    .addListener(RakNet.INTERNAL_WRITE_LISTENER)
                    .addListener(done -> {
                        if (done.isSuccess()) {
                            nextStrikeAt = System.nanoTime() + 3 * INTERVAL;
                            refusalDelivered = true;
                        }
                    });
        } else if (policy.disconnect() && refusalDelivered) {
            long now = System.nanoTime();
            if (now - nextStrikeAt >= 0) {
                nextStrikeAt = now + INTERVAL;
                if (++strikes == 3) {
                    System.out.println(
                            ConsoleMessages.text(
                                    "diagnostics_denied", ctx.channel().remoteAddress()));
                    ctx.close().addListener(RakNet.INTERNAL_WRITE_LISTENER);
                }
            }
        }
    }

    /**
     * Publishes a complete sample only when its timestamp has not been superseded.
     *
     * @param bytes exactly 32 bytes after the envelope type
     */
    private void acceptSnapshot(final ByteBuf bytes) {
        long time = bytes.readLong();
        if (time < snapshot().time()) {
            return;
        }
        remote =
                new RemoteMetrics(
                        time,
                        bytes.readInt(),
                        bytes.readInt(),
                        bytes.readDouble(),
                        bytes.readInt(),
                        bytes.readInt());
        receivedAt = System.nanoTime();
    }

    /** Monotonic receipt time for hiding old values after a viewer stops requesting reports. */
    private volatile long receivedAt;

    /**
     * Reads a stable snapshot while preserving the distinction between no report and reported
     * zeroes.
     *
     * @return latest peer report, or the immutable zero values before negotiation
     */
    private RemoteMetrics snapshot() {
        RemoteMetrics current = remote;
        return current == null ? EMPTY : current;
    }

    /**
     * Reports whether remote metric values have been initialized by a compatible snapshot.
     *
     * @return true after at least one supported snapshot has been accepted
     */
    public boolean isRemoteSupported() {
        return remote != null && System.nanoTime() - receivedAt < 3 * INTERVAL;
    }

    /**
     * Reports the peer's most recently advertised queue size.
     *
     * @return remote queued bytes, or zero before the first snapshot
     */
    public int getQueuedBytes() {
        return snapshot().queuedBytes();
    }

    /**
     * Reports the peer's most recently advertised send capacity.
     *
     * @return remote burst tokens plus its baseline pending-frame-set allowance
     */
    public int getBurst() {
        return snapshot().burst();
    }

    /**
     * Reports the peer's rolling retransmission measurement.
     *
     * @return retransmitted-byte fraction, not a percentage or a measured packet-loss probability
     */
    public double getErrorRate() {
        return snapshot().errorRate();
    }

    /**
     * Reports traffic sent by the peer, which is inbound from this endpoint's perspective.
     *
     * @return remote outgoing packets per second
     */
    public int getTX() {
        return snapshot().tx();
    }

    /**
     * Reports traffic received by the peer, which is outbound from this endpoint's perspective.
     *
     * @return remote incoming packets per second
     */
    public int getRX() {
        return snapshot().rx();
    }
}
