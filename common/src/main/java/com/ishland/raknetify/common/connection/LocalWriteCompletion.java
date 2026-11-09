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

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import network.ycc.raknet.RakNet;

/** Completes local protocol transitions after encoding instead of waiting for remote ACKs. */
public final class LocalWriteCompletion {
    /** Prevents construction of this write utility. */
    private LocalWriteCompletion() {}

    /**
     * Sends a transition packet before allowing its callback to change the pipeline.
     *
     * @param context outbound encoding context
     * @param message transition packet transferred to the pipeline
     * @param completion local transition callback
     */
    public static void write(
            final ChannelHandlerContext context,
            final Object message,
            final ChannelPromise completion) {
        ChannelFuture transport = context.write(message);
        if (transport.isDone() && !transport.isSuccess()) {
            completion.tryFailure(transport.cause());
            return;
        }
        transport.addListener(RakNet.INTERNAL_WRITE_LISTENER);
        completion.trySuccess();
    }
}
