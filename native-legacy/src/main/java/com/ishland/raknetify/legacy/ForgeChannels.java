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
import com.ishland.raknetify.common.connection.RakNetConnectionUtil;
import com.ishland.raknetify.common.connection.RaknetifyEventLoops;
import com.ishland.raknetify.common.proxy.ProxyProtocol;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;

import network.ycc.raknet.RakNet;
import network.ycc.raknet.client.channel.RakNetClientThreadedChannel;
import network.ycc.raknet.server.channel.RakNetServerChannel;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/** Adapts the bootstrap that FML configured, retaining its login handlers on the UDP channel. */
public final class ForgeChannels {
    /** Only static transport factories are needed. */
    private ForgeChannels() {}

    /**
     * Creates the socket on the same I/O backend as the original Forge bootstrap.
     *
     * @param epoll whether the game selected Linux epoll for socket I/O
     * @param mtu negotiated or requested maximum datagram size
     * @return unregistered datagram channel configured for the requested transport
     */
    private static DatagramChannel socket(final boolean epoll, final int mtu) {
        DatagramChannel channel = epoll ? new EpollDatagramChannel() : new NioDatagramChannel();
        channel.config().setOption(ChannelOption.IP_TOS, RakNetConnectionUtil.DEFAULT_IP_TOS);
        channel.config()
                .setRecvByteBufAllocator(
                        new FixedRecvByteBufAllocator(mtu + 512).maxMessagesPerRead(128));
        return channel;
    }

    /**
     * Closes the UDP listener with the TCP listener, including during server shutdown.
     *
     * @param original native bootstrap or initializer configured by the mod loader
     * @param tcp bound native TCP listener whose lifetime owns the UDP endpoint
     */
    public static void bind(final ServerBootstrap original, final Channel tcp) {
        boolean epoll = original.config().group() instanceof EpollEventLoopGroup;
        InetSocketAddress address = (InetSocketAddress) tcp.localAddress();
        int port = Integer.getInteger("raknetify.fabric.portOverride", address.getPort());
        Channel udp =
                new ServerBootstrap()
                        .group(
                                epoll
                                        ? RaknetifyEventLoops.EPOLL_EVENT_LOOP_GROUP.get()
                                        : RaknetifyEventLoops.NIO_EVENT_LOOP_GROUP.get())
                        .channelFactory(() -> {
                            RakNetServerChannel channel =
                                    ProxyProtocol.create(() -> socket(epoll, Constants.LARGE_MTU));
                            channel.setProvidedApplicationEventLoop(
                                    RaknetifyEventLoops.DEFAULT_EVENT_LOOP_GROUP.get().next());
                            return channel;
                        })
                        .childHandler(new ForgePipeline(original.config().childHandler(), false))
                        .bind(address.getAddress(), port)
                        .syncUninterruptibly()
                        .channel();
        tcp.closeFuture()
                .addListener(ignored -> udp.close().addListener(RakNet.INTERNAL_WRITE_LISTENER));
    }

    /**
     * Connects the approved Minecraft endpoint using the original FML client initializer.
     *
     * @param original native bootstrap or initializer configured by the mod loader
     * @param address resolved or parsed destination for the pending connection
     * @param port Minecraft destination port
     * @param large whether the player selected the large MTU prefix
     * @return unregistered datagram channel configured for the requested transport
     */
    public static ChannelFuture connect(
            final Bootstrap original,
            final InetAddress address,
            final int port,
            final boolean large) {
        boolean epoll = original.config().group() instanceof EpollEventLoopGroup;
        int mtu = large ? Constants.LARGE_MTU : Constants.DEFAULT_MTU;
        return new Bootstrap()
                .group(RaknetifyEventLoops.DEFAULT_CLIENT_EVENT_LOOP_GROUP.get())
                .channelFactory(() -> {
                    RakNetClientThreadedChannel channel =
                            new RakNetClientThreadedChannel(() -> socket(epoll, mtu));
                    RakNet.config(channel).setMTU(mtu);
                    channel.setProvidedParentEventLoop(original.config().group().next());
                    return channel;
                })
                .handler(new ForgePipeline(original.config().handler(), true))
                .connect(address, port);
    }
}
