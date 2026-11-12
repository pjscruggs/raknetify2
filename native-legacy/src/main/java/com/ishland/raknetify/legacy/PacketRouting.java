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

package com.ishland.raknetify.legacy;

import com.ishland.raknetify.common.connection.RakNetSimpleMultiChannelCodec;
import com.ishland.raknetify.common.connection.RakNetSimpleMultiChannelCodec.OverrideHandler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;

/** Classifies encoded Minecraft packets before vanilla's optional compression hides their IDs. */
public final class PacketRouting implements OverrideHandler {
    /** Native channel supplying the current protocol and compression handlers. */
    private final Channel channel;

    /** Routes custom payloads using negotiated identifier policies. */
    private final OverrideHandler payload;

    /** Maps remaining play packet IDs to the appropriate transport channels. */
    private final OverrideHandler packets;

    /** Route in effect while the synchronous compressor forwards the current packet. */
    private int compressedRoute;

    /**
     * Installs classifiers for one endpoint and protocol version.
     *
     * @param channel native channel that owns all routing state
     * @param payload custom-payload classifier for the outgoing direction
     * @param packets numeric play-packet routing table for this endpoint
     */
    PacketRouting(
            final Channel channel, final OverrideHandler payload, final OverrideHandler packets) {
        this.channel = channel;
        this.payload = payload;
        this.packets = packets;
    }

    /**
     * Classifies only play bytes so FML login and configuration exchanges remain reliable and
     * ordered.
     *
     * @param buf encoded Minecraft packet before optional vanilla compression
     * @param quiet whether to suppress unmapped packet diagnostics
     * @return channel override or the negotiated bundle delimiter marker
     */
    private int route(final ByteBuf buf, final boolean quiet) {
        if (!GamePackets.playing(channel)) {
            return 0;
        }
        int custom = payload.getChannelOverride(buf, quiet);
        return custom == 0 ? packets.getChannelOverride(buf, quiet) : custom;
    }

    /** {@inheritDoc} */
    @Override
    public int getChannelOverride(final ByteBuf buf, final boolean quiet) {
        return channel.pipeline().get("compress") == null ? route(buf, quiet) : compressedRoute;
    }

    /**
     * Follows the game installing and removing its vanilla compression handlers during login.
     *
     * @param pipeline pipeline after Minecraft has updated the compression threshold
     */
    public static void updateCompression(final ChannelPipeline pipeline) {
        RakNetSimpleMultiChannelCodec codec = pipeline.get(RakNetSimpleMultiChannelCodec.class);
        if (codec == null) {
            return;
        }
        String capture = "raknetify-pre-compression-route";
        if (pipeline.get(capture) != null) {
            pipeline.remove(capture);
        }
        if (pipeline.get("compress") != null) {
            PacketRouting routing = codec.getHandler(PacketRouting.class);
            if (routing != null) {
                pipeline.addAfter("compress", capture, routing.new Capture());
            }
        }
    }

    /** Retains only a route across the compressor's synchronous write, including nested writes. */
    private final class Capture extends ChannelOutboundHandlerAdapter {
        /** {@inheritDoc} */
        @Override
        public void write(
                final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
                throws Exception {
            int previous = compressedRoute;
            try {
                compressedRoute = msg instanceof ByteBuf bytes ? route(bytes, true) : 0;
                super.write(ctx, msg, promise);
            } finally {
                compressedRoute = previous;
            }
        }
    }
}
