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

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.SingleThreadEventExecutor;

import network.ycc.raknet.client.channel.RakNetClientThreadedChannel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Exercises application flushes across separate application and UDP event loops. */
class NoFlushTest {
    /** Two lazy writes share a wake-up, and empty flushes leave the transport scheduler alone. */
    @Test
    void wakesOnceForPendingWrites() {
        NioEventLoopGroup io = new NioEventLoopGroup(1);
        DefaultEventLoopGroup game = new DefaultEventLoopGroup(1);
        RakNetClientThreadedChannel channel = new RakNetClientThreadedChannel();
        try {
            io.register(channel.parent()).syncUninterruptibly();
            game.register(channel).syncUninterruptibly();
            channel.pipeline().addLast(new NoFlush());
            channel.parent().pipeline().addLast(new Sink());
            SingleThreadEventExecutor worker = (SingleThreadEventExecutor) io.next();
            var writes =
                    worker.submit(() -> measureQueue(worker, channel))
                            .syncUninterruptibly()
                            .getNow();
            writes.forEach(ChannelFuture::syncUninterruptibly);
        } finally {
            channel.close().awaitUninterruptibly();
            game.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            io.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    /**
     * Samples the queue on its owning NIO thread, including on older Netty versions that dispatch
     * queue inspections back to that same thread.
     *
     * @param worker parent transport executor currently running this measurement
     * @param channel application channel whose writes should share a wake-up
     * @return pending writes to complete once the parent resumes draining its queue
     * @throws Exception if the application loop fails to submit its batch
     */
    private static List<ChannelFuture> measureQueue(
            final SingleThreadEventExecutor worker, final RakNetClientThreadedChannel channel)
            throws Exception {
        int initial = worker.pendingTasks();
        var game = channel.eventLoop();
        game.submit(channel::flush).get(5, TimeUnit.SECONDS);
        assertEquals(initial, worker.pendingTasks());
        var writes = game.submit(() -> writeBatch(channel)).get(5, TimeUnit.SECONDS);
        assertEquals(initial + 3, worker.pendingTasks());
        return writes;
    }

    /**
     * Submits two writes followed by an empty flush on the application thread.
     *
     * @param channel application endpoint forwarding the writes
     * @return both original write completions
     */
    private static List<ChannelFuture> writeBatch(final RakNetClientThreadedChannel channel) {
        var first = channel.writeAndFlush(1);
        var second = channel.writeAndFlush(2);
        channel.flush();
        return List.of(first, second);
    }

    /** Consumes the two integer messages on the transport executor before datagram encoding. */
    private static final class Sink extends ChannelOutboundHandlerAdapter {
        /** {@inheritDoc} */
        @Override
        public void write(
                final ChannelHandlerContext ctx,
                final Object message,
                final ChannelPromise promise) {
            promise.setSuccess();
        }
    }
}
