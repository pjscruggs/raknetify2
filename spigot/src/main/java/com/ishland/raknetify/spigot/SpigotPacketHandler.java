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

package com.ishland.raknetify.spigot;

import com.ishland.raknetify.common.connection.RakNetSimpleMultiChannelCodec;
import com.ishland.raknetify.common.connection.SynchronizationLayer;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/**
 * Observes native packets at login and world boundaries while retaining the original packet
 * listener.
 */
final class SpigotPacketHandler extends ChannelDuplexHandler {
    /** Candidate encryption state, committed only after native challenge verification succeeds. */
    private final SpigotCipherState cipher;

    /** Shared pipeline reconciler for encryption and negotiated compression. */
    private final SpigotTransportGuard guard;

    /** Whether outgoing packet IDs currently belong to the play protocol. */
    private boolean play;

    /**
     * Creates a packet observer for one connection without retaining player or world state.
     *
     * @param cipher key exchange state owned by this connection
     * @param guard inbound guard sharing compression and encryption decisions
     */
    SpigotPacketHandler(final SpigotCipherState cipher, final SpigotTransportGuard guard) {
        this.cipher = cipher;
        this.guard = guard;
    }

    /**
     * Identifies the protocol used by the native outbound encoder.
     *
     * @return whether play packet IDs and custom payload identifiers can be interpreted
     */
    boolean isPlaying() {
        return play;
    }

    /**
     * Captures the shared secret before native processing and adapts ciphers only after native
     * validation.
     *
     * @param ctx context immediately before the native packet listener
     * @param msg decoded Minecraft packet
     * @throws Exception if key extraction, native processing, or handler reconciliation fails
     */
    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if ("net.minecraft.network.protocol.login.ServerboundKeyPacket"
                .equals(msg.getClass().getName())) {
            cipher.capture(msg);
        }
        ctx.fireChannelRead(msg);
        guard.refresh(ctx.pipeline());
    }

    /**
     * Sends ordering barriers at world transitions and prevents redundant negotiated packet
     * compression.
     *
     * @param ctx outbound packet context before serialization
     * @param msg native packet or protocol-configuration task
     * @param promise completion notification retained by the server
     * @throws Exception if pipeline reconciliation or downstream serialization fails
     */
    @Override
    public void write(
            final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
            throws Exception {
        guard.refresh(ctx.pipeline());
        String name = msg.getClass().getName();
        if ("net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket".equals(name)
                && guard.suppressCompression(ctx.pipeline())) {
            promise.trySuccess();
            guard.refresh(ctx.pipeline());
            return;
        }
        transition(ctx, name);
        super.write(ctx, msg, promise);
        guard.refresh(ctx.pipeline());
    }

    /**
     * Flushes previous ordering domains before a world or protocol reset and starts routing at the
     * command tree.
     *
     * @param ctx outbound context through which control markers precede their corresponding packet
     * @param name complete native packet class name
     */
    private void transition(final ChannelHandlerContext ctx, final String name) {
        if ("net.minecraft.network.protocol.game.ClientboundLoginPacket".equals(name)
                || "net.minecraft.network.protocol.game.ClientboundCommandsPacket".equals(name)) {
            play = true;
        }
        if ("net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket"
                .equals(name)) {
            play = false;
        }
        switch (name) {
            case "net.minecraft.network.protocol.game.ClientboundLoginPacket",
                    "net.minecraft.network.protocol.game.ClientboundRespawnPacket",
                    "net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket" ->
                    ctx.write(SynchronizationLayer.SYNC_REQUEST_OBJECT)
                            .addListener(network.ycc.raknet.RakNet.INTERNAL_WRITE_LISTENER);
            case "net.minecraft.network.protocol.game.ClientboundCommandsPacket" ->
                    ctx.write(RakNetSimpleMultiChannelCodec.SIGNAL_START_MULTICHANNEL)
                            .addListener(network.ycc.raknet.RakNet.INTERNAL_WRITE_LISTENER);
            default -> {}
        }
    }
}
