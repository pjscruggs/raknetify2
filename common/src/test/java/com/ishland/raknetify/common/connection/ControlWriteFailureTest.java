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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

import network.ycc.raknet.config.DefaultConfig;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.channels.ClosedChannelException;
import java.util.Map;

/** Checks shutdown races and unexpected failures of transport control writes. */
class ControlWriteFailureTest {
    /**
     * Closed transports fail queued controls without reporting another pipeline error.
     *
     * @param metrics whether to exercise requested metrics instead of ordering declarations
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closedTransportDoesNotReportControlFailure(final boolean metrics) {
        HeldWrite held = new HeldWrite();
        EmbeddedChannel channel = channel(metrics, held);
        try {
            java.util.Objects.requireNonNull(held.promise).setFailure(new ClosedChannelException());
            assertDoesNotThrow(channel::checkException);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Unexpected control failures remain observable and terminate the affected connection.
     *
     * @param metrics whether to exercise requested metrics instead of ordering declarations
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unexpectedControlFailureIsNotHidden(final boolean metrics) {
        HeldWrite held = new HeldWrite();
        EmbeddedChannel channel = channel(metrics, held);
        try {
            java.util.Objects.requireNonNull(held.promise)
                    .setFailure(new IllegalStateException("Injected write failure"));
            assertThrows(IllegalStateException.class, channel::checkException);
            assertFalse(channel.isOpen());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Creates one real control write whose transport completion the test can delay.
     *
     * @param metrics selects the requested metrics control instead of the activation declaration
     * @param held captures and releases the generated frame while retaining its completion
     * @return channel owning the selected production handler
     */
    private static EmbeddedChannel channel(final boolean metrics, final HeldWrite held) {
        EmbeddedChannel channel = new MetricsChannel();
        channel.pipeline().addLast(held);
        if (metrics) {
            var handler = new MetricsSynchronizationHandler();
            channel.pipeline().addLast(handler);
            handler.requestSnapshot();
            channel.runPendingTasks();
        } else {
            channel.pipeline().addLast(new PayloadOrderingNegotiation(Map.of()));
        }
        return channel;
    }

    /** Updated peers answer only a viewer request and stop after the report reaches the viewer. */
    @org.junit.jupiter.api.Test
    void requestedSnapshotMakesOneRoundTrip() {
        var left = new MetricsChannel();
        var right = new MetricsChannel();
        try {
            var viewer = new MetricsSynchronizationHandler();
            left.pipeline().addLast(viewer);
            right.pipeline().addLast(new MetricsSynchronizationHandler(DiagnosticsPolicy.CLIENT));
            viewer.requestSnapshot();
            left.runPendingTasks();
            left.flushOutbound();
            right.writeInbound((Object) left.readOutbound());
            right.flushOutbound();
            left.writeInbound((Object) right.readOutbound());
            org.junit.jupiter.api.Assertions.assertTrue(viewer.isRemoteSupported());
            left.flushOutbound();
            org.junit.jupiter.api.Assertions.assertNull(left.readOutbound());
        } finally {
            left.finishAndReleaseAll();
            right.finishAndReleaseAll();
        }
    }

    /** Holds only write completion while releasing the fixture-owned frame immediately. */
    private static final class HeldWrite extends ChannelOutboundHandlerAdapter {
        /** Completion retained until the test injects its transport outcome. */
        private @Nullable ChannelPromise promise;

        /**
         * Captures the pending control and releases its reference-counted storage.
         *
         * @param context unused transport context
         * @param message owned control frame released by this fixture
         * @param completion write promise completed explicitly by the test
         */
        @Override
        public void write(
                final ChannelHandlerContext context,
                final Object message,
                final ChannelPromise completion) {
            ReferenceCountUtil.release(message);
            promise = completion;
        }
    }

    /** Supplies the production transport marker and metrics producer to an embedded channel. */
    private static final class MetricsChannel extends EmbeddedChannel {
        /** Lazily initialized because the superclass queries configuration during construction. */
        private @Nullable DefaultConfig transportConfig;

        /**
         * Returns this channel's transport configuration and metric counters.
         *
         * @return persistent configuration for the metric publication task
         */
        @Override
        public ChannelConfig config() {
            if (transportConfig == null) {
                transportConfig = new DefaultConfig(this);
                transportConfig.setMetrics(new SimpleMetricsLogger());
            }
            return transportConfig;
        }
    }
}
