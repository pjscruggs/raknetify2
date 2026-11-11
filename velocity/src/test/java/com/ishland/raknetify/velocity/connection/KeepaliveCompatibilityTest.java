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

package com.ishland.raknetify.velocity.connection;

import static org.junit.jupiter.api.Assertions.*;

import com.ishland.raknetify.common.connection.CompatibilityPolicy;
import com.velocitypowered.proxy.protocol.packet.KeepAlivePacket;

import io.netty.channel.ChannelConfig;
import io.netty.channel.embedded.EmbeddedChannel;

import network.ycc.raknet.config.DefaultConfig;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Verifies that all proxy sessions require the real client's keepalive response. */
class KeepaliveCompatibilityTest {
    /**
     * Preserves the exact backend request and verifies that a stalled client produces no reply.
     *
     * @param ordered whether the connection requires stream ordering
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void keepaliveFollowsConnectionPolicy(final boolean ordered) {
        EmbeddedChannel client = new RaknetChannel();
        new CompatibilityPolicy(ordered).install(client, client);
        EmbeddedChannel backend =
                new EmbeddedChannel(new RakNetVelocityServerChannelEventListener(client));
        KeepAlivePacket request = new KeepAlivePacket();
        request.setRandomId(42);
        try {
            assertTrue(backend.writeInbound(request));
            assertSame(request, backend.readInbound());
            backend.runScheduledPendingTasks();
            assertNull(backend.readOutbound());
        } finally {
            backend.finishAndReleaseAll();
            client.finishAndReleaseAll();
        }
    }

    /** Supplies RakNet identity and zero RTT without creating a socket. */
    private static final class RaknetChannel extends EmbeddedChannel {
        /** Lazily initialized because superclass construction requests configuration. */
        private @Nullable DefaultConfig transportConfig;

        /**
         * Makes scheduled replies immediately due for deterministic testing.
         *
         * @return persistent zero-RTT configuration
         */
        @Override
        public ChannelConfig config() {
            if (transportConfig == null) {
                transportConfig = new DefaultConfig(this);
                transportConfig.setRTTNanos(0);
            }
            return transportConfig;
        }
    }
}
