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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Exercises application flushes across separate application and UDP event loops. */
class NoFlushTest {
    /** Two lazy writes share a wake-up, and empty flushes leave the transport scheduler alone. */
    @Test
    void wakesOnceForPendingWrites() {
        NioEventLoopGroup io = new NioEventLoopGroup(1);
        DefaultEventLoopGroup game = new DefaultEventLoopGroup(1);
        RakNetClientThreadedChannel channel = new RakNetClientThreadedChannel();
        CompletableFuture<Void> release = new CompletableFuture<>();
        try {
            io.register(channel.parent()).syncUninterruptibly();
            game.register(channel).syncUninterruptibly();
            channel.pipeline().addLast(new NoFlush());
            channel.parent().pipeline().addLast(new Sink());
            SingleThreadEventExecutor worker = (SingleThreadEventExecutor) io.next();
            CompletableFuture<Void> entered = new CompletableFuture<>();
            worker.execute(() -> {
                entered.complete(null);
                release.join();
            });
            entered.orTimeout(5, TimeUnit.SECONDS).join();
            int initial = worker.pendingTasks();
            var writes =
                    game.submit(
                                    () -> {
                                        channel.flush();
                                        assertEquals(initial, worker.pendingTasks());
                                        var first = channel.writeAndFlush(1);
                                        var second = channel.writeAndFlush(2);
                                        channel.flush();
                                        assertEquals(initial + 3, worker.pendingTasks());
                                        return List.of(first, second);
                                    })
                            .syncUninterruptibly();
            release.complete(null);
            writes.getNow().forEach(ChannelFuture::syncUninterruptibly);
        } finally {
            release.complete(null);
            channel.close().awaitUninterruptibly();
            game.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            io.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        }
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
