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

package com.ishland.raknetify.common.util;

import com.ishland.raknetify.common.connection.MultiChannelingStreamingCompression;
import com.ishland.raknetify.common.connection.SimpleMetricsLogger;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;

import network.ycc.raknet.RakNet;

/**
 * Builds connection diagnostics that include transport metrics and complete Netty handler ordering.
 */
public class DebugUtil {

    /**
     * Keeps a recent peer snapshot for a platform that has enabled connection exception reports. A
     * failing connection may already be unable to deliver a new request. Closing the application
     * channel cancels its sampling task.
     *
     * @param channel modded RakNet connection whose exception logger reads peer diagnostics
     */
    public static void sampleForExceptionReports(final Channel channel) {
        var sample =
                channel.eventLoop()
                        .scheduleWithFixedDelay(
                                () -> {
                                    if (channel.config() instanceof RakNet.Config config
                                            && config.getMetrics()
                                                    instanceof SimpleMetricsLogger logger) {
                                        var metrics = logger.getMetricsSynchronizationHandler();
                                        if (metrics != null) {
                                            metrics.requestSnapshot();
                                        }
                                    }
                                },
                                0,
                                1,
                                java.util.concurrent.TimeUnit.SECONDS);
        channel.closeFuture().addListener(done -> sample.cancel(false));
    }

    /**
     * Describes the channel, its optional RakNet metrics, and each parent channel recursively.
     *
     * @param channel connection or transport channel to inspect
     * @return multiline diagnostic report suitable for logs or a debug command
     */
    public static String printChannelDetails(final Channel channel) {
        final StringBuilder b = new StringBuilder();
        final var local = String.valueOf(channel.localAddress());
        final var peer = String.valueOf(channel.remoteAddress());
        b.append("Channel: ")
                .append(channel)
                .append(" (")
                .append(channel.getClass().getName())
                .append(")\n")
                .append(local)
                .append(" ↔ ")
                .append(peer)
                .append("\nisOpen: ")
                .append(channel.isOpen())
                .append("\nisActive: ")
                .append(channel.isActive())
                .append("\nAUTO_READ: ")
                .append(channel.config().isAutoRead())
                .append('\n');
        if (channel.config() instanceof RakNet.Config config) {
            var language = ConsoleMessages.measurements();
            b.append("MTU: ")
                    .append(language.integer(config.getMTU()))
                    .append("\nRTT: ")
                    .append(language.decimal(config.getRTTNanos() / 1_000_000.0))
                    .append(" ± ")
                    .append(language.decimal(config.getRTTStdDevNanos() / 1_000_000.0))
                    .append(" ms\n");
            if (config.getMetrics() instanceof SimpleMetricsLogger logger) {
                var sync = logger.getMetricsSynchronizationHandler();
                b.append(
                                ConsoleMessages.text(
                                        "queue_sample",
                                        local,
                                        language.decimal(
                                                logger.getCurrentQueuedBytes() / 1048576.0)))
                        .append('\n');
                b.append(
                                ConsoleMessages.text(
                                        "traffic_sample",
                                        local,
                                        logger.hasTrafficSample()
                                                ? language.byteRate(logger.getMeasureBytesInRate())
                                                : "...",
                                        logger.hasTrafficSample()
                                                ? language.byteRate(logger.getMeasureBytesOutRate())
                                                : "..."))
                        .append('\n');
                b.append(
                                language.translate(
                                        "packets",
                                        local,
                                        language.ratio(logger.getMeasureErrorRate(), true),
                                        language.integer(logger.getMeasureTX()),
                                        language.integer(logger.getMeasureRX()),
                                        language.integer(
                                                logger.getMeasureBurstTokens()
                                                        + config.getDefaultPendingFrameSets())))
                        .append('\n');
                if (sync != null && sync.isRemoteSupported()) {
                    b.append(
                                    ConsoleMessages.text(
                                            "queue_sample",
                                            peer,
                                            language.decimal(sync.getQueuedBytes() / 1048576.0)))
                            .append('\n');
                    b.append(
                                    language.translate(
                                            "packets",
                                            peer,
                                            language.ratio(sync.getErrorRate(), true),
                                            language.integer(sync.getTX()),
                                            language.integer(sync.getRX()),
                                            language.integer(sync.getBurst())))
                            .append('\n');
                }
                var compression = channel.pipeline().get(MultiChannelingStreamingCompression.class);
                if (compression != null && compression.isActive()) {
                    b.append(
                                    language.translate(
                                            "compression",
                                            language.ratio(
                                                    compression.getInCompressionRatio(), false),
                                            language.ratio(
                                                    compression.getOutCompressionRatio(), false)))
                            .append('\n');
                }
            }
        }

        b.append('\n');
        appendPipeline(b, channel);

        if (channel.parent() != null) {
            b.append("\nChannel.parent(): \n")
                    .append(printChannelDetails(channel.parent()).replace("\n", "\n\t"));
        }

        return b.toString();
    }

    /**
     * Appends handler names and implementation types in the channel's current pipeline order.
     *
     * @param b diagnostic report being assembled
     * @param channel channel whose handlers may be removed concurrently while the report is built
     */
    private static void appendPipeline(final StringBuilder b, final Channel channel) {
        b.append("ChannelPipeline: \n");
        for (String name : channel.pipeline().names()) {
            final ChannelHandler channelHandler = channel.pipeline().get(name);
            if (channelHandler == null) {
                b.append('\t').append(name).append(": \tnull\n");
            } else {
                b.append('\t')
                        .append(name)
                        .append(": \t")
                        .append(channelHandler.toString())
                        .append('(')
                        .append(channelHandler.getClass().getName())
                        .append(")\n");
            }
        }
    }
}
