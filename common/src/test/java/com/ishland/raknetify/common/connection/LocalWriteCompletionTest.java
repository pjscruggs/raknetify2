/*
 * This file is a part of the Raknetify project, licensed under MIT.
 *
 * Copyright (c) 2022-2025 ishland
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

import static org.junit.jupiter.api.Assertions.*;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Checks transition timing and failures while remote acknowledgements are withheld. */
class LocalWriteCompletionTest {
    /** Encoding precedes the local callback even while the peer acknowledgement is pending. */
    @Test
    void transitionCompletesBeforeRemoteAcknowledgement() {
        HeldWrite held = new HeldWrite(false);
        EmbeddedChannel channel = channel(held);
        try {
            ChannelPromise local = channel.newPromise();
            local.addListener(future -> assertNotNull(held.completion));
            assertSame(local, channel.write("key response", local));
            assertTrue(local.isSuccess());
            assertFalse(java.util.Objects.requireNonNull(held.completion).isDone());
            held.completion.setSuccess();
            channel.checkException();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Immediate encoding failure prevents the encryption transition. */
    @Test
    void immediateFailureFailsLocalTransition() {
        HeldWrite held = new HeldWrite(true);
        EmbeddedChannel channel = channel(held);
        try {
            ChannelPromise local = channel.newPromise();
            assertSame(local, channel.write("key response", local));
            assertTrue(local.isDone());
            assertFalse(local.isSuccess());
            assertInstanceOf(IllegalStateException.class, local.cause());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** A later transport failure remains visible and closes the connection. */
    @Test
    void delayedFailureClosesConnection() {
        HeldWrite held = new HeldWrite(false);
        EmbeddedChannel channel = channel(held);
        try {
            ChannelPromise local = channel.newPromise();
            assertSame(local, channel.write("key response", local));
            assertTrue(local.isSuccess());
            java.util.Objects.requireNonNull(held.completion)
                    .setFailure(new IllegalStateException("Injected delayed failure"));
            assertThrows(IllegalStateException.class, channel::checkException);
            assertFalse(channel.isOpen());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Installs the production write helper in front of a controllable transport.
     *
     * @param held transport completion capture
     * @return isolated channel owning both handlers
     */
    private static EmbeddedChannel channel(final HeldWrite held) {
        return new EmbeddedChannel(held, new TransitionWrite());
    }

    /** Delegates the transition write to the production helper. */
    private static final class TransitionWrite extends ChannelOutboundHandlerAdapter {
        /**
         * Sends a transition through the local completion path.
         *
         * @param context outbound encoding context
         * @param message owned transition message
         * @param completion local transition callback
         */
        @Override
        public void write(
                final ChannelHandlerContext context,
                final Object message,
                final ChannelPromise completion) {
            LocalWriteCompletion.write(context, message, completion);
        }
    }

    /** Holds acknowledgement after consuming the outgoing packet. */
    private static final class HeldWrite extends ChannelOutboundHandlerAdapter {
        /** Whether encoding fails before returning to the transition helper. */
        private final boolean fail;

        /** Pending remote acknowledgement. */
        private @Nullable ChannelPromise completion;

        /**
         * Chooses synchronous failure or deferred acknowledgement.
         *
         * @param fail whether to fail during encoding
         */
        private HeldWrite(final boolean fail) {
            this.fail = fail;
        }

        /**
         * Consumes the packet and retains its transport completion.
         *
         * @param context unused transport context
         * @param message owned outgoing message
         * @param promise remote acknowledgement completion
         */
        @Override
        public void write(
                final ChannelHandlerContext context,
                final Object message,
                final ChannelPromise promise) {
            ReferenceCountUtil.release(message);
            completion = promise;
            if (fail) {
                promise.setFailure(new IllegalStateException("Injected encoding failure"));
            }
        }
    }
}
