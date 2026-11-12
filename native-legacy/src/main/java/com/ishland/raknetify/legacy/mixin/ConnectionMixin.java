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

package com.ishland.raknetify.legacy.mixin;

import com.ishland.raknetify.common.connection.MultiChannelingStreamingCompression;
import com.ishland.raknetify.common.connection.MultiChannellingEncryption;
import com.ishland.raknetify.common.util.ThreadLocalUtil;
import com.ishland.raknetify.legacy.ForgeChannels;
import com.ishland.raknetify.legacy.PacketRouting;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPipeline;

import net.minecraft.network.Connection;

import network.ycc.raknet.RakNet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.InetAddress;

import javax.crypto.Cipher;

/** Substitutes RakNet after Forge sets up its own activation and encryption state. */
@Mixin(value = Connection.class, remap = false)
abstract class ConnectionMixin {
    /**
     * Keeps Forge's normal TCP bootstrap for unprefixed addresses.
     *
     * @param bootstrap configured native Minecraft bootstrap
     * @param host destination approved by the game resolver
     * @param port Minecraft destination port
     * @return connection attempt on the selected TCP or RakNet transport
     */
    @Redirect(
            method = {"m_290025_", "connect"},
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lio/netty/bootstrap/Bootstrap;connect(Ljava/net/InetAddress;I)Lio/netty/channel/ChannelFuture;"))
    private static ChannelFuture connect(
            final Bootstrap bootstrap, final InetAddress host, final int port) {
        return ThreadLocalUtil.isInitializingRaknet()
                ? ForgeChannels.connect(
                        bootstrap, host, port, ThreadLocalUtil.isInitializingRaknetLargeMTU())
                : bootstrap.connect(host, port);
    }

    /**
     * Installs per-frame encryption after Minecraft has marked the connection encrypted.
     *
     * @param inbound initialized inbound Minecraft cipher
     * @param outbound initialized outbound Minecraft cipher
     * @param callback callback allowing the stream-cipher setup to be replaced
     */
    @Inject(
            method = {"m_129495_", "setEncryptionKey"},
            cancellable = true,
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lio/netty/channel/Channel;pipeline()Lio/netty/channel/ChannelPipeline;",
                            ordinal = 0))
    private void selectFrameCipher(
            final Cipher inbound, final Cipher outbound, final CallbackInfo callback) {
        Channel channel = ((Connection) (Object) this).channel();
        if (channel.config() instanceof RakNet.Config) {
            ChannelPipeline pipeline = channel.pipeline();
            MultiChannellingEncryption.removePreviousHandlers(pipeline);
            pipeline.addBefore(
                    MultiChannelingStreamingCompression.NAME,
                    MultiChannellingEncryption.NAME,
                    new MultiChannellingEncryption(inbound, outbound));
            callback.cancel();
        }
    }

    /**
     * Repositions the routing capture whenever Minecraft updates its compressor.
     *
     * @param threshold vanilla byte threshold or a negative value to remove compression
     * @param validate whether the decoder validates compressed packet lengths
     * @param ci callback after the application has installed its handlers
     */
    @Inject(
            method = {"m_129484_", "setupCompression"},
            at = @At("RETURN"))
    private void updateCompression(
            final int threshold, final boolean validate, final CallbackInfo ci) {
        PacketRouting.updateCompression(((Connection) (Object) this).channel().pipeline());
    }
}
