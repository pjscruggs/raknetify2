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
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.util.concurrent.PromiseCombiner;

import network.ycc.raknet.frame.FrameData;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Maintains independent zlib streams for reliable ordered RakNet channels. Stream dictionaries
 * persist between packets and restart at explicit channel markers, so unrelated ordering channels
 * never depend on each other's compression history.
 */
public class MultiChannelingStreamingCompression extends ChannelDuplexHandler {

    /** Pipeline name used to locate compression state and statistics. */
    public static final String NAME = "raknetify-multichannel-streaming-compression";

    /** Control payload advertising support for channel-specific streaming compression. */
    public static final long SERVER_HANDSHAKE = 0x40000010;

    /** Ordered control payload that starts a fresh dictionary for its channel. */
    public static final long CHANNEL_START = 0x40000012;

    /** Inbound zlib dictionaries indexed by ordering channel, owned by the event loop. */
    private final Inflater[] inflaters = new Inflater[Constants.ORDER_CHANNEL_COUNT];

    /** Outbound zlib dictionaries indexed by ordering channel, owned by the event loop. */
    private final Deflater[] deflaters = new Deflater[Constants.ORDER_CHANNEL_COUNT];

    /** Delivery of each dictionary marker, which must precede completion of dependent data. */
    private final ChannelFuture[] channelStarts = new ChannelFuture[Constants.ORDER_CHANNEL_COUNT];

    /**
     * Temporary zlib output, allocated on first use and confined to this connection's event loop.
     */
    private byte @Nullable [] scratch;

    /**
     * Returns scratch for the current drain loop, which ends before any downstream callback.
     *
     * @return reusable zlib output chunk
     */
    private byte[] scratch() {
        if (scratch == null) {
            scratch = new byte[16 * 1024];
        }
        return scratch;
    }

    /** Frame identifier recognized before compression and restored after decompression. */
    private final int rawPacketId;

    /** Frame identifier marking payloads that require the channel's inflater. */
    private final int compressedPacketId;

    /** Cumulative outgoing payload bytes before compression, excluding frame identifiers. */
    private final java.util.concurrent.atomic.AtomicLong outBytesRaw =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Cumulative outgoing payload bytes after compression, including small payloads passed through
     * raw.
     */
    private final java.util.concurrent.atomic.AtomicLong outBytesCompressed =
            new java.util.concurrent.atomic.AtomicLong();

    /** Cumulative compressed payload bytes consumed by inbound inflaters. */
    private final java.util.concurrent.atomic.AtomicLong inBytesCompressed =
            new java.util.concurrent.atomic.AtomicLong();

    /** Cumulative payload bytes produced by inbound inflaters. */
    private final java.util.concurrent.atomic.AtomicLong inBytesRaw =
            new java.util.concurrent.atomic.AtomicLong();

    /** Whether the peer has advertised compatible streaming compression. */
    private volatile boolean active;

    /**
     * Creates inactive per-channel compression state awaiting the peer handshake.
     *
     * @param rawPacketId frame identifier for uncompressed Minecraft payloads
     * @param compressedPacketId frame identifier for compressed Minecraft payloads
     */
    public MultiChannelingStreamingCompression(
            final int rawPacketId, final int compressedPacketId) {
        this.rawPacketId = rawPacketId;
        this.compressedPacketId = compressedPacketId;
    }

    /**
     * Advertises streaming compression support and releases the temporary control payload.
     *
     * @param ctx outbound context used to allocate and send the handshake frame
     */
    private void doServerHandshake(final ChannelHandlerContext ctx) {
        final ByteBuf buf = ctx.alloc().buffer().writeLong(SERVER_HANDSHAKE);
        try {
            final FrameData data =
                    FrameData.create(
                            ctx.alloc(),
                            Constants.RAKNET_STREAMING_COMPRESSION_HANDSHAKE_PACKET_ID,
                            buf);
            ctx.write(data).addListener(network.ycc.raknet.RakNet.INTERNAL_WRITE_LISTENER);
        } finally {
            buf.release();
        }
    }

    /**
     * Sends dictionary-reset markers only for channels whose outgoing streams have started. Does
     * nothing until the peer has advertised compression support.
     *
     * @param ctx outbound context preserving each marker's order relative to later payloads
     */
    private void doChannelStart(final ChannelHandlerContext ctx) {
        if (!active) {
            return;
        }
        for (int channel = 0; channel < deflaters.length; channel++) {
            if (deflaters[channel] != null) {
                startChannel(ctx, channel);
            }
        }
    }

    /**
     * Starts a dictionary on its first use, after any login encryption transition.
     *
     * @param ctx outbound context preserving marker order
     * @param channel ordering channel whose dictionary starts here
     */
    private void startChannel(final ChannelHandlerContext ctx, final int channel) {
        ByteBuf buf = ctx.alloc().buffer().writeLong(CHANNEL_START);
        try {
            FrameData data =
                    FrameData.create(
                            ctx.alloc(),
                            Constants.RAKNET_STREAMING_COMPRESSION_HANDSHAKE_PACKET_ID,
                            buf);
            data.setOrderChannel(channel);
            channelStarts[channel] = ctx.write(data);
            channelStarts[channel].addListener(network.ycc.raknet.RakNet.INTERNAL_WRITE_LISTENER);
            initDeflater(channel);
        } finally {
            buf.release();
        }
    }

    /**
     * Replaces one outbound zlib dictionary after releasing its native resources.
     *
     * @param channel ordering-channel index whose outgoing stream is restarting
     */
    private void initDeflater(final int channel) {
        Objects.checkIndex(channel, deflaters.length);
        if (!active) {
            return;
        }
        if (deflaters[channel] != null) {
            deflaters[channel].end();
        }
        deflaters[channel] = new Deflater();
        if (Constants.DEBUG) {
            System.out.println(
                    "Raknetify: Streaming compression deflater for ch%d is ready"
                            .formatted(channel));
        }
    }

    /**
     * Replaces one inbound zlib dictionary after releasing its native resources.
     *
     * @param channel ordering-channel index whose incoming stream is restarting
     */
    private void initInflater(final int channel) {
        Objects.checkIndex(channel, inflaters.length);
        // Independent channels can deliver their start marker before the capability handshake.
        if (inflaters[channel] != null) {
            inflaters[channel].end();
        }
        inflaters[channel] = new Inflater();
        if (Constants.DEBUG) {
            System.out.println(
                    "Raknetify: Streaming compression inflater for ch%d is ready"
                            .formatted(channel));
        }
    }

    /**
     * Advertises compression support after forwarding channel activation.
     *
     * @param ctx newly active connection context
     * @throws Exception if activation forwarding fails
     */
    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        doServerHandshake(ctx);
    }

    /**
     * Consumes compression control frames and inflates eligible ordered payloads. Frames without a
     * matching active dictionary continue through the pipeline unchanged.
     *
     * @param ctx inbound context receiving decompressed replacement frames
     * @param msg incoming frame or unrelated pipeline message
     * @throws Exception if decompression or superclass inbound processing fails
     */
    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (msg instanceof FrameData compressedFrameData) {
            compressedFrameData.touch();
            final int channel = checkedOrderChannel(compressedFrameData);
            if (compressedFrameData.getPacketId()
                    == Constants.RAKNET_STREAMING_COMPRESSION_HANDSHAKE_PACKET_ID) {
                handleHandshake(ctx, compressedFrameData);
                return;
            } else if (compressedFrameData.getPacketId() == compressedPacketId
                    && compressedFrameData.getReliability().isReliable
                    && compressedFrameData.getReliability().isOrdered
                    && !compressedFrameData.getReliability().isSequenced
                    && inflaters[channel] != null) {
                inflateFrame(ctx, compressedFrameData);
                return;
            }
        }
        super.channelRead(ctx, msg);
    }

    /**
     * Compresses eligible ordered payloads and restarts dictionaries after synchronization
     * requests. Payloads shorter than sixteen bytes retain their raw representation.
     *
     * @param ctx outbound context receiving compressed or unchanged frames
     * @param msg outgoing frame, local synchronization request, or unrelated message
     * @param promise completion notification transferred to the emitted frame
     * @throws Exception if superclass outbound processing fails
     */
    @Override
    public void write(
            final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
            throws Exception {
        if (msg instanceof SynchronizationLayer.SyncRequest) {
            super.write(ctx, msg, promise);
            doChannelStart(ctx);
            return;
        } else if (msg instanceof FrameData rawFrameData) {
            rawFrameData.touch();
            final int channel = checkedOrderChannel(rawFrameData);
            if (rawFrameData.getPacketId() == rawPacketId
                    && rawFrameData.getReliability().isReliable
                    && rawFrameData.getReliability().isOrdered
                    && !rawFrameData.getReliability().isSequenced
                    && active) {
                if (deflaters[channel] == null) {
                    startChannel(ctx, channel);
                }
                final ChannelPromise delivery = includeChannelStart(ctx, channel, promise);
                if (rawFrameData.getDataSize() < 16 + 1) {
                    outBytesRaw.addAndGet(rawFrameData.getDataSize() - 1);
                    outBytesCompressed.addAndGet(rawFrameData.getDataSize() - 1);
                    super.write(ctx, rawFrameData, delivery);
                    return;
                }

                deflateFrame(ctx, rawFrameData, delivery);
                return;
            }
        }
        super.write(ctx, msg, promise);
    }

    /**
     * Includes the dictionary marker in delivery completion so phase barriers cannot overtake it.
     *
     * @param ctx event loop owning both transport completions
     * @param channel ordering lane whose dictionary the payload requires
     * @param promise application completion for the payload and its prerequisite
     * @return transport completion to attach to the payload frame
     */
    private ChannelPromise includeChannelStart(
            final ChannelHandlerContext ctx, final int channel, final ChannelPromise promise) {
        if (channelStarts[channel].isSuccess()) {
            return promise;
        }
        final ChannelPromise delivery = ctx.newPromise();
        final PromiseCombiner completion = new PromiseCombiner();
        completion.add(channelStarts[channel]);
        completion.add((io.netty.util.concurrent.Future<?>) delivery);
        completion.finish(promise.unvoid());
        return delivery;
    }

    /**
     * Rejects unsupported channels before transferring or consuming a frame's ownership.
     *
     * @param frame owned frame whose channel will select a compression dictionary
     * @return validated ordering-channel index
     * @throws CorruptedFrameException after releasing a frame with an invalid channel
     */
    private static int checkedOrderChannel(final FrameData frame) {
        try {
            return Objects.checkIndex(frame.getOrderChannel(), Constants.ORDER_CHANNEL_COUNT);
        } catch (IndexOutOfBoundsException invalid) {
            frame.release();
            throw new CorruptedFrameException("Invalid compression ordering channel", invalid);
        }
    }

    /**
     * Consumes a compression control frame and starts the requested channel's stream.
     *
     * @param ctx pipeline context used to acknowledge the peer's handshake
     * @param compressedFrameData control frame whose reference is released on every exit
     */
    private void handleHandshake(
            final ChannelHandlerContext ctx, final FrameData compressedFrameData) {
        final int orderChannel =
                Objects.checkIndex(compressedFrameData.getOrderChannel(), inflaters.length);
        ByteBuf payload = null;
        try {
            payload = compressedFrameData.createData().skipBytes(1);
            if (payload.readableBytes() == 8) {
                final long l = payload.readLong();
                if (l == CHANNEL_START) {
                    initInflater(orderChannel);
                    return;
                } else if (l == SERVER_HANDSHAKE) {
                    active = true;
                    startChannel(ctx, 0);
                    return;
                }
            }
        } finally {
            compressedFrameData.release();
            if (payload != null) {
                payload.release();
            }
        }
    }

    /**
     * Inflates one ordered frame with that channel's retained dictionary and forwards its payload.
     *
     * @param ctx pipeline context that allocates and receives the replacement frame
     * @param compressedFrameData owned compressed frame, released after decoding or failure
     * @throws DataFormatException if the peer's compressed stream is invalid
     */
    private void inflateFrame(final ChannelHandlerContext ctx, final FrameData compressedFrameData)
            throws DataFormatException {
        final int orderChannel =
                Objects.checkIndex(compressedFrameData.getOrderChannel(), inflaters.length);
        final Inflater inflater = inflaters[orderChannel];
        final ByteBuf data = compressedFrameData.createData().skipBytes(1);

        ByteBuf out = null;
        FrameData rawFrameData = null;
        try {
            inflater.setInput(data.nioBuffer());

            inBytesCompressed.addAndGet(data.readableBytes());

            out = ctx.alloc().buffer(256, Constants.MAX_QUEUED_SIZE - 1);
            inflateInto(inflater, scratch(), out);

            inBytesRaw.addAndGet(out.writerIndex());

            rawFrameData = FrameData.create(ctx.alloc(), rawPacketId, out);
            rawFrameData.setReliability(compressedFrameData.getReliability());
            rawFrameData.setOrderChannel(orderChannel);
            ctx.fireChannelRead(rawFrameData);
            rawFrameData = null;
            return;
        } catch (DataFormatException | CorruptedFrameException invalid) {
            ctx.close().addListener(network.ycc.raknet.RakNet.INTERNAL_WRITE_LISTENER);
            throw invalid;
        } finally {
            data.release();
            compressedFrameData.release();
            if (out != null) {
                out.release();
            }
            if (rawFrameData != null) {
                rawFrameData.release();
            }
        }
    }

    /**
     * Drains one compressed packet within the destination's configured payload ceiling.
     *
     * @param inflater channel dictionary positioned at this packet's compressed input
     * @param scratch reusable transient output from zlib
     * @param out bounded destination excluding the frame identifier
     * @throws DataFormatException if the stream is malformed
     * @throws CorruptedFrameException if decompression exceeds the frame budget
     */
    static void inflateInto(final Inflater inflater, final byte[] scratch, final ByteBuf out)
            throws DataFormatException {
        int count;
        while ((count = inflater.inflate(scratch)) != 0) {
            if (count > out.maxWritableBytes()) {
                throw new CorruptedFrameException(
                        "Decompressed RakNet frame exceeds the transport limit");
            }
            out.writeBytes(scratch, 0, count);
        }
    }

    /**
     * Compresses one frame without resetting its channel's dictionary between packets.
     *
     * @param ctx outbound pipeline context that receives the compressed replacement
     * @param rawFrameData owned raw frame, released after encoding or failure
     * @param promise completion notification transferred to the replacement frame
     * @throws Exception if downstream forwarding of the compressed replacement fails
     * @throws IllegalArgumentException if the raw payload exceeds the transport frame budget
     */
    private void deflateFrame(
            final ChannelHandlerContext ctx,
            final FrameData rawFrameData,
            final ChannelPromise promise)
            throws Exception {
        final int orderChannel =
                Objects.checkIndex(rawFrameData.getOrderChannel(), deflaters.length);
        final Deflater deflater = deflaters[orderChannel];
        final ByteBuf data = rawFrameData.createData().skipBytes(1);

        ByteBuf out = null;
        FrameData compressedFrameData = null;
        try {
            if (data.readableBytes() >= Constants.MAX_QUEUED_SIZE) {
                throw new IllegalArgumentException("Minecraft frame exceeds the transport limit");
            }
            byte[] deflateBuffer = scratch();
            deflater.setInput(data.nioBuffer());

            outBytesRaw.addAndGet(data.readableBytes());

            out = ctx.alloc().buffer();
            {
                int deflatedBytes;
                while ((deflatedBytes =
                                deflater.deflate(
                                        deflateBuffer,
                                        0,
                                        deflateBuffer.length,
                                        Deflater.SYNC_FLUSH))
                        != 0) {
                    out.writeBytes(deflateBuffer, 0, deflatedBytes);
                }
            }

            outBytesCompressed.addAndGet(out.writerIndex());

            compressedFrameData = FrameData.create(ctx.alloc(), compressedPacketId, out);
            compressedFrameData.setReliability(rawFrameData.getReliability());
            compressedFrameData.setOrderChannel(orderChannel);
            super.write(ctx, compressedFrameData, promise);
            compressedFrameData = null;
            return;
        } finally {
            data.release();
            rawFrameData.release();
            if (out != null) {
                out.release();
            }
            if (compressedFrameData != null) {
                compressedFrameData.release();
            }
        }
    }

    /**
     * Reports payload bytes consumed from compressed inbound frames.
     *
     * @return cumulative compressed bytes, excluding RakNet frame identifiers
     */
    public long getInBytesCompressed() {
        return inBytesCompressed.get();
    }

    /**
     * Reports payload bytes produced by decompression.
     *
     * @return cumulative decompressed inbound bytes
     */
    public long getInBytesRaw() {
        return inBytesRaw.get();
    }

    /**
     * Reports payload bytes emitted through the compression path.
     *
     * @return cumulative compressed bytes plus small eligible payloads sent without compression
     */
    public long getOutBytesCompressed() {
        return outBytesCompressed.get();
    }

    /**
     * Reports the original size of outgoing payloads eligible for compression.
     *
     * @return cumulative outgoing bytes before compression or small-payload bypass
     */
    public long getOutBytesRaw() {
        return outBytesRaw.get();
    }

    /**
     * Reports whether the peer has completed compression capability negotiation.
     *
     * @return true after a compatible server-handshake marker has been received
     */
    public boolean isActive() {
        return active;
    }

    /** Once-per-second event-loop task that updates compression ratios. */
    private @Nullable ScheduledFuture<?> future;

    /**
     * Starts periodic compression sampling on the connection's event loop.
     *
     * @param ctx installed handler context supplying the scheduling event loop
     */
    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) {
        this.future =
                ctx.channel()
                        .eventLoop()
                        .scheduleAtFixedRate(this::tickMetrics, 1000, 1000, TimeUnit.MILLISECONDS);
    }

    /**
     * Cancels metric sampling and releases all native zlib resources.
     *
     * @param ctx removed handler context, unused because resources belong to this instance
     */
    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) {
        if (future != null) {
            future.cancel(false);
        }
        for (int i = 0; i < 8; i++) {
            if (inflaters[i] != null) {
                inflaters[i].end();
            }
            if (deflaters[i] != null) {
                deflaters[i].end();
            }
        }
    }

    /** Previous sample's compressed inbound byte total used to calculate an interval delta. */
    private long lastInBytesCompressed;

    /** Previous sample's decompressed inbound byte total used to calculate an interval delta. */
    private long lastInBytesRaw;

    /** Previous sample's original outgoing byte total used to calculate an interval delta. */
    private long lastOutBytesRaw;

    /** Previous sample's transmitted outgoing byte total used to calculate an interval delta. */
    private long lastOutBytesCompressed;

    /** Rolling sixteen-sample history of compressed-to-raw inbound byte ratios. */
    private final DescriptiveStatistics inCompressionRatioStats = new DescriptiveStatistics(16);

    /** Rolling sixteen-sample history of compressed-to-raw outbound byte ratios. */
    private final DescriptiveStatistics outCompressionRatioStats = new DescriptiveStatistics(16);

    /** Published mean inbound compressed-to-raw ratio, possibly NaN before a nonempty sample. */
    private volatile double inCompressionRatio;

    /** Published mean outbound compressed-to-raw ratio, possibly NaN before a nonempty sample. */
    private volatile double outCompressionRatio;

    /**
     * Samples interval byte ratios, skips idle directions, and publishes rolling means for the HUD.
     */
    private void tickMetrics() {
        long deltaInBytesCompressed = this.inBytesCompressed.get() - this.lastInBytesCompressed;
        long deltaInBytesRaw = this.inBytesRaw.get() - this.lastInBytesRaw;
        long deltaOutBytesCompressed = this.outBytesCompressed.get() - this.lastOutBytesCompressed;
        long deltaOutBytesRaw = this.outBytesRaw.get() - this.lastOutBytesRaw;

        if (deltaInBytesRaw != 0) {
            double currentInCompressionRatio = deltaInBytesCompressed / (double) deltaInBytesRaw;
            inCompressionRatioStats.addValue(currentInCompressionRatio);
        }

        if (deltaOutBytesRaw != 0) {
            double currentOutCompressionRatio = deltaOutBytesCompressed / (double) deltaOutBytesRaw;
            outCompressionRatioStats.addValue(currentOutCompressionRatio);
        }

        this.inCompressionRatio = inCompressionRatioStats.getMean();
        this.outCompressionRatio = outCompressionRatioStats.getMean();

        this.lastInBytesCompressed = this.inBytesCompressed.get();
        this.lastInBytesRaw = this.inBytesRaw.get();
        this.lastOutBytesCompressed = this.outBytesCompressed.get();
        this.lastOutBytesRaw = this.outBytesRaw.get();
    }

    /**
     * Reports the rolling mean size ratio for inbound compressed traffic.
     *
     * @return compressed bytes divided by raw bytes, or NaN before a nonempty sample
     */
    public double getInCompressionRatio() {
        return inCompressionRatio;
    }

    /**
     * Reports the rolling mean size ratio for outbound traffic eligible for compression.
     *
     * @return transmitted bytes divided by original bytes, or NaN before a nonempty sample
     */
    public double getOutCompressionRatio() {
        return outCompressionRatio;
    }
}
