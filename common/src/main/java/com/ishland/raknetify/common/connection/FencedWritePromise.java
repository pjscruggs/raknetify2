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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;

import java.util.Objects;

/**
 * Carries a local delivery boundary through compression and encryption without changing the wire.
 */
final class FencedWritePromise extends DefaultChannelPromise {
    /**
     * Preserves the application's completion while marking one write for cross-channel delivery.
     *
     * @param context connection and event loop owning the outgoing packet
     * @param original original application promise receiving the final transport outcome
     */
    FencedWritePromise(final ChannelHandlerContext context, final ChannelPromise original) {
        this(context);
        ChannelPromise completion = original.unvoid();
        addListener(result -> {
            if (result.isSuccess()) {
                completion.trySuccess();
            } else {
                completion.tryFailure(Objects.requireNonNull(result.cause()));
            }
        });
    }

    /**
     * Creates an internal transport completion carrying the original write's boundary.
     *
     * @param context connection event loop owning the replacement
     * @param original completion whose boundary must survive a dictionary dependency
     * @return new transport promise retaining the local boundary marker
     */
    static ChannelPromise replacement(
            final ChannelHandlerContext context, final ChannelPromise original) {
        return original instanceof FencedWritePromise
                ? new FencedWritePromise(context)
                : context.newPromise();
    }

    /**
     * Creates a boundary completion whose caller combines its dependency outcomes.
     *
     * @param context connection and event loop owning the transport completion
     */
    private FencedWritePromise(final ChannelHandlerContext context) {
        super(context.channel(), context.executor());
    }
}
