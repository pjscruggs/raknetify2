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

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import network.ycc.raknet.client.channel.RakNetClientThreadedChannel;
import network.ycc.raknet.server.channel.RakNetApplicationChannel;

import java.util.concurrent.atomic.AtomicBoolean;

/** Suppresses application flush requests so RakNet retains control of its own send cadence. */
public class NoFlush extends ChannelDuplexHandler {

    /** Set on the application loop when at least one write has passed since its last flush. */
    private boolean wrote;

    /** Shares a single wake-up task across flushes until the transport loop runs it. */
    private final AtomicBoolean wakePending = new AtomicBoolean();

    /** Clears the wake-up reservation on the transport loop after earlier lazy writes. */
    private void acknowledgeWake() {
        wakePending.set(false);
    }

    /**
     * Records an application write and preserves its original ownership and completion.
     *
     * @param ctx application pipeline forwarding the write
     * @param message outbound frame transferred to the next handler
     * @param promise completion associated with the frame
     * @throws Exception if the downstream pipeline rejects the write
     */
    @Override
    public void write(
            final ChannelHandlerContext ctx, final Object message, final ChannelPromise promise)
            throws Exception {
        wrote = true;
        super.write(ctx, message, promise);
    }

    /**
     * Wakes a separate transport loop to process Netty's lazily enqueued writes. RakNet still
     * decides when to assemble and send datagrams within its congestion window.
     *
     * @param ctx outbound context intentionally left unflushed
     */
    @Override
    public void flush(final ChannelHandlerContext ctx) {
        if (!wrote) {
            return;
        }
        wrote = false;
        Channel application = ctx.channel();
        if (application instanceof RakNetApplicationChannel
                || application instanceof RakNetClientThreadedChannel) {
            var loop = application.parent().eventLoop();
            if (!loop.inEventLoop() && wakePending.compareAndSet(false, true)) {
                loop.execute(this::acknowledgeWake);
            }
        }
    }
}
