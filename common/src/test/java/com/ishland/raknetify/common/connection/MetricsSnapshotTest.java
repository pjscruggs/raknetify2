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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ishland.raknetify.common.Constants;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;

import network.ycc.raknet.frame.FrameData;

import org.junit.jupiter.api.Test;

/**
 * Verifies that an incomplete peer update cannot publish fields or poison the accepted timestamp.
 */
class MetricsSnapshotTest {
    /** Polling a visible HUD coalesces frames and removing the handler stops further requests. */
    @Test
    void requestsAreViewerDrivenAndCoalesced() {
        var handler = new MetricsSynchronizationHandler();
        var channel = new EmbeddedChannel(handler);
        try {
            channel.runPendingTasks();
            channel.flushOutbound();
            org.junit.jupiter.api.Assertions.assertNull(channel.readOutbound());
            handler.requestSnapshot();
            handler.requestSnapshot();
            channel.runPendingTasks();
            channel.flushOutbound();
            FrameData request = channel.readOutbound();
            var data = request.createData();
            try {
                assertEquals(Constants.RAKNET_METRICS_SYNC_PACKET_ID, data.readUnsignedByte());
                assertEquals(1, data.readUnsignedByte());
                assertFalse(data.isReadable());
            } finally {
                data.release();
                request.release();
            }
            org.junit.jupiter.api.Assertions.assertNull(channel.readOutbound());
            channel.pipeline().remove(handler);
            handler.requestSnapshot();
            channel.runPendingTasks();
            channel.flushOutbound();
            org.junit.jupiter.api.Assertions.assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Parsing must finish before either peer support or its most recent measurements change. */
    @Test
    void truncatedSnapshotCannotReplaceAcceptedMeasurements() {
        var handler = new MetricsSynchronizationHandler();
        var channel = new EmbeddedChannel(handler);
        try {
            assertFalse(handler.isRemoteSupported());
            handler.requestSnapshot();
            channel.writeInbound(snapshot(1, 11));
            assertTrue(handler.isRemoteSupported());
            FrameData truncated = frame(Unpooled.buffer().writeByte(0).writeLong(100).writeInt(99));
            assertFalse(channel.writeInbound(truncated));
            assertEquals(0, truncated.refCnt());
            assertEquals(11, handler.getQueuedBytes());
            assertEquals(4, handler.getBurst());
            assertEquals(0.125, handler.getErrorRate());
            assertEquals(6, handler.getTX());
            assertEquals(7, handler.getRX());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * A reliable refusal stops polling, tolerates in-flight requests and enforces the server
     * setting.
     *
     * @throws InterruptedException if the acknowledgement grace period is interrupted
     */
    @Test
    void refusalControlsBothEndpoints() throws InterruptedException {
        var viewer = new MetricsSynchronizationHandler(DiagnosticsPolicy.CLIENT);
        var client = new EmbeddedChannel(viewer);
        var strict = new EmbeddedChannel(new MetricsSynchronizationHandler());
        var lenient =
                new EmbeddedChannel(
                        new MetricsSynchronizationHandler(new DiagnosticsPolicy(false, false)));
        try {
            viewer.requestSnapshot();
            client.runPendingTasks();
            client.flushOutbound();
            FrameData query = client.readOutbound();
            assertTrue(query.getReliability().isReliable);
            lenient.writeInbound(query.retain());
            strict.writeInbound(query);
            strict.flushOutbound();
            FrameData refusal = strict.readOutbound();
            assertTrue(refusal.getReliability().isReliable);
            client.writeInbound(refusal);
            strict.writeInbound(frame(Unpooled.buffer().writeByte(1)));
            assertTrue(strict.isOpen());
            lenient.flushOutbound();
            ((FrameData) lenient.readOutbound()).release();
            Thread.sleep(3100);
            viewer.requestSnapshot();
            client.runPendingTasks();
            client.flushOutbound();
            assertNull(client.readOutbound());
            for (int strike = 1; strike <= 3; strike++) {
                for (var server : new EmbeddedChannel[] {strict, lenient}) {
                    server.writeInbound(snapshot(2, 22));
                    server.writeInbound(frame(Unpooled.buffer().writeByte(1)));
                    if (strike < 3) {
                        server.writeInbound(frame(Unpooled.buffer().writeByte(1)));
                        assertTrue(server.isOpen());
                    }
                    server.flush();
                    assertNull(server.readOutbound());
                }
                if (strike < 3) {
                    Thread.sleep(1050);
                }
            }
            assertFalse(strict.isOpen());
            assertTrue(lenient.isOpen());
        } finally {
            client.finishAndReleaseAll();
            strict.finishAndReleaseAll();
            lenient.finishAndReleaseAll();
        }
    }

    /**
     * Only explicitly enabled disclosure can expose server measurements.
     *
     * @param dir temporary server configuration directory
     * @throws java.io.IOException if the fixture cannot write its test configuration
     */
    @Test
    void serverPolicyDefaultsAndOverrides(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir)
            throws java.io.IOException {
        var file = dir.resolve("settings.json");
        assertEquals(DiagnosticsPolicy.DEFAULT_SERVER, DiagnosticsPolicy.load(file));
        java.nio.file.Files.writeString(file, "{\"disconnectOnRejectedRequests\":false}");
        assertEquals(new DiagnosticsPolicy(false, false), DiagnosticsPolicy.load(file));
        java.nio.file.Files.writeString(file, "{\"shareServerMetrics\":true}");
        assertEquals(new DiagnosticsPolicy(true, true), DiagnosticsPolicy.load(file));
        java.nio.file.Files.writeString(file, "{\"shareServerMetrics\":\"true\"}");
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> DiagnosticsPolicy.load(file));
    }

    /**
     * Produces one supported wire snapshot with independently chosen timestamp and queue size.
     *
     * @param time sender's monotonically increasing fixture timestamp
     * @param queuedBytes advertised queue size
     * @return owned metric frame consumed by the handler
     */
    private static FrameData snapshot(final long time, final int queuedBytes) {
        return frame(
                Unpooled.buffer()
                        .writeByte(0)
                        .writeLong(time)
                        .writeInt(queuedBytes)
                        .writeInt(4)
                        .writeDouble(0.125)
                        .writeInt(6)
                        .writeInt(7));
    }

    /**
     * Transfers fixture bytes into the same frame envelope accepted by the production pipeline.
     *
     * @param data owned metric payload released after the frame retains it
     * @return owned transport frame
     */
    private static FrameData frame(final ByteBuf data) {
        try {
            return FrameData.create(
                    UnpooledByteBufAllocator.DEFAULT,
                    Constants.RAKNET_METRICS_SYNC_PACKET_ID,
                    data);
        } finally {
            data.release();
        }
    }
}
