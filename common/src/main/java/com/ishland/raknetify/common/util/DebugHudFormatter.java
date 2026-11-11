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

import com.ishland.raknetify.common.connection.MetricsSynchronizationHandler;
import com.ishland.raknetify.common.connection.MultiChannelingStreamingCompression;
import com.ishland.raknetify.common.connection.SimpleMetricsLogger;

import io.netty.channel.Channel;

import network.ycc.raknet.RakNet;

import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/** Formats transport statistics without depending on a particular Minecraft client's classes. */
public final class DebugHudFormatter {

    /** Prevents instances of the shared HUD formatter. */
    private DebugHudFormatter() {}

    /**
     * Emits the active connection's HUD lines or the localized inactive marker.
     *
     * @param consumer destination for complete display lines
     * @param channel network channel, or null while the client is disconnected
     * @param language formatter bound to the player's current language
     */
    public static void append(
            final Consumer<String> consumer,
            final @Nullable Channel channel,
            final HudLanguage language) {
        final Consumer<String> line = message -> consumer.accept("[Raknetify2] " + message);
        if (channel == null || !(channel.config() instanceof RakNet.Config config)) {
            line.accept(language.translate("inactive"));
            return;
        }
        if (config.getMetrics() instanceof SimpleMetricsLogger logger) {
            appendConnectionMetrics(line, language, config, logger);
        } else {
            line.accept(language.translate("active", config.getMTU()));
        }
        final MultiChannelingStreamingCompression compression =
                channel.pipeline().get(MultiChannelingStreamingCompression.class);
        if (compression != null && compression.isActive()) {
            line.accept(
                    language.translate(
                            "compression",
                            language.ratio(compression.getInCompressionRatio(), false),
                            language.ratio(compression.getOutCompressionRatio(), false)));
        }
    }

    /**
     * Formats local measurements and includes peer statistics only after metrics negotiation.
     *
     * @param line destination for each display line
     * @param language formatter bound to the player's current language
     * @param config transport configuration supplying the MTU and pending-frame allowance
     * @param logger local traffic samples and the optional remote metrics report
     */
    private static void appendConnectionMetrics(
            final Consumer<String> line,
            final HudLanguage language,
            final RakNet.Config config,
            final SimpleMetricsLogger logger) {
        line.accept(
                language.translate(
                        "latency",
                        config.getMTU(),
                        language.decimal(logger.getMeasureRTTns() / 1_000_000.0),
                        language.decimal(logger.getMeasureRTTnsStdDev() / 1_000_000.0)));
        final MetricsSynchronizationHandler serverSync = logger.getMetricsSynchronizationHandler();
        if (serverSync != null) {
            serverSync.requestSnapshot();
        }
        final String queued = language.decimal(logger.getCurrentQueuedBytes() / 1048576.0);
        if (serverSync != null && serverSync.isRemoteSupported()) {
            line.accept(
                    language.translate(
                            "queues",
                            queued,
                            language.decimal(serverSync.getQueuedBytes() / 1048576.0)));
        } else {
            line.accept(language.translate("queue", queued));
        }

        line.accept(
                language.translate(
                        "traffic",
                        logger.getMeasureTrafficInFormatted(),
                        logger.getMeasureTrafficOutFormatted()));
        line.accept(
                language.translate(
                        "packets",
                        language.translate("client"),
                        language.ratio(logger.getMeasureErrorRate(), true),
                        logger.getMeasureTX(),
                        logger.getMeasureRX(),
                        logger.getMeasureBurstTokens() + config.getDefaultPendingFrameSets()));
        if (serverSync != null && serverSync.isRemoteSupported()) {
            line.accept(
                    language.translate(
                            "packets",
                            language.translate("server"),
                            language.ratio(serverSync.getErrorRate(), true),
                            serverSync.getTX(),
                            serverSync.getRX(),
                            serverSync.getBurst()));
        }
    }
}
