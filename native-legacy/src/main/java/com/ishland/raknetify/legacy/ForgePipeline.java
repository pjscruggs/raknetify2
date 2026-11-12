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

import com.ishland.raknetify.common.Constants;
import com.ishland.raknetify.common.connection.CompatibilityNegotiation;
import com.ishland.raknetify.common.connection.RakNetConnectionUtil;
import com.ishland.raknetify.common.connection.RakNetSimpleMultiChannelCodec;
import com.ishland.raknetify.common.connection.SynchronizationLayer;
import com.ishland.raknetify.common.connection.multichannel.CustomPayloadChannel;
import com.ishland.raknetify.common.data.ProtocolMultiChannelMappings;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;

import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;

import network.ycc.raknet.RakNet;

/** Wraps the complete Forge channel initializer, preserving FML negotiation and packet handlers. */
final class ForgePipeline extends ChannelInitializer<Channel> {
    /** Forge's initializer, including any other mod's bootstrap hooks. */
    private final ChannelHandler original;

    /** Direction of the packets written by this application endpoint. */
    private final boolean client;

    /**
     * Captures the initializer before registering the UDP socket.
     *
     * @param original native bootstrap or initializer configured by the mod loader
     * @param client whether this initializer writes client-to-server packets
     */
    ForgePipeline(final ChannelHandler original, final boolean client) {
        this.original = original;
        this.client = client;
    }

    /**
     * Installs the frame codec before running the regular Forge network setup.
     *
     * @param channel active application channel associated with the Minecraft connection
     */
    @Override
    protected void initChannel(final Channel channel) {
        RakNetConnectionUtil.initChannel(channel);
        ChannelPipeline pipeline = channel.pipeline();
        RakNetSimpleMultiChannelCodec codec =
                new RakNetSimpleMultiChannelCodec(Constants.RAKNET_GAME_PACKET_ID);
        pipeline.addAfter(CompatibilityNegotiation.NAME, RakNetSimpleMultiChannelCodec.NAME, codec);
        pipeline.addLast(original);
        RakNetConnectionUtil.removeTcpOnlyHandlers(channel);
        for (String name : new String[] {"timeout", "splitter", "prepender"}) {
            pipeline.replace(name, name, new ChannelDuplexHandler());
        }
        if (pipeline.get("legacy_query") != null) {
            pipeline.remove("legacy_query");
        }
        ProtocolMultiChannelMappings.VersionMapping mapping =
                ProtocolMultiChannelMappings.INSTANCE.mappings.get(GamePackets.PROTOCOL);
        codec.addHandler(
                new PacketRouting(
                        channel,
                        new CustomPayloadChannel.PayloadClassifier(
                                id ->
                                        id
                                                == (client
                                                        ? GamePackets.SERVERBOUND_PAYLOAD
                                                        : GamePackets.CLIENTBOUND_PAYLOAD),
                                channel),
                        new RakNetSimpleMultiChannelCodec.PacketIdBasedOverrideHandler(
                                client ? mapping.c2s : mapping.s2c,
                                Integer.toString(GamePackets.PROTOCOL))));
        pipeline.addBefore("packet_handler", "raknetify-forge-packets", new PacketTransitions());
    }

    /** Observes decoded packets before the vanilla encoder writes their bytes. */
    private static final class PacketTransitions extends ChannelDuplexHandler {
        /**
         * Flushes a world transition and lets login compression change before the next write.
         *
         * @param ctx outbound position in the Minecraft pipeline
         * @param msg outbound Minecraft packet or pipeline message
         * @param promise completion notification for the outgoing packet
         * @throws Exception if a downstream handler fails to forward the packet
         */
        @Override
        public void write(
                final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
                throws Exception {
            if (GamePackets.worldBoundary(msg)) {
                ctx.write(SynchronizationLayer.SYNC_REQUEST_OBJECT)
                        .addListener(RakNet.INTERNAL_WRITE_LISTENER);
            }
            if (msg instanceof ClientboundCommandsPacket) {
                ctx.write(RakNetSimpleMultiChannelCodec.SIGNAL_START_MULTICHANNEL)
                        .addListener(RakNet.INTERNAL_WRITE_LISTENER);
            }
            if (msg instanceof ClientboundLoginCompressionPacket) {
                promise.trySuccess();
                ctx.write(msg).addListener(RakNet.INTERNAL_WRITE_LISTENER);
                return;
            }
            super.write(ctx, msg, promise);
        }
    }
}
