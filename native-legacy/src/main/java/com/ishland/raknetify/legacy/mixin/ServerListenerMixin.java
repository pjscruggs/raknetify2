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

import com.ishland.raknetify.legacy.ForgeChannels;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;

import net.minecraft.server.network.ServerConnectionListener;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Shares Forge's network initializer between its TCP listener and the companion UDP listener. */
@Mixin(value = ServerConnectionListener.class, remap = false)
abstract class ServerListenerMixin {
    /**
     * Binds the TCP port first so an ephemeral port selects the same number for UDP.
     *
     * @param bootstrap configured native Minecraft bootstrap
     * @return completed TCP bind whose listener also owns the UDP endpoint
     */
    @Redirect(
            method = {"m_9711_", "startTcpServerListener"},
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lio/netty/bootstrap/ServerBootstrap;bind()Lio/netty/channel/ChannelFuture;"))
    private ChannelFuture bindCompanion(final ServerBootstrap bootstrap) {
        ChannelFuture tcp = bootstrap.bind().syncUninterruptibly();
        try {
            ForgeChannels.bind(bootstrap, tcp.channel());
        } catch (RuntimeException failure) {
            tcp.channel().close().syncUninterruptibly();
            throw failure;
        }
        return tcp;
    }
}
