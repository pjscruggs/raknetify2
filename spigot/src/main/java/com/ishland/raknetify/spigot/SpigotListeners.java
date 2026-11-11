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

import com.ishland.raknetify.common.Constants;
import com.ishland.raknetify.common.connection.RakNetConnectionUtil;
import com.ishland.raknetify.common.proxy.ProxyProtocol;
import com.ishland.raknetify.common.util.ConsoleMessages;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;

import network.ycc.raknet.server.channel.RakNetServerChannel;

import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/** Keeps UDP resources separate from server-owned TCP channels and shuts them down together. */
final class SpigotListeners implements AutoCloseable {
    /** Native listener snapshot and server login key used by each accepted connection. */
    private final ServerAccess access;

    /** Optional packet-plugin initializer applied after native codecs are present. */
    private final @Nullable SpigotPacketEvents packetEvents;

    /** Datagram I/O loop owned exclusively by this plugin instance. */
    private final EventLoopGroup transport;

    /** Application loop keeps Minecraft packet processing off the datagram I/O loop. */
    private final EventLoopGroup application;

    /** Explicit UDP listener port, or zero to retain each TCP listener's port number. */
    private final int portOverride;

    /** Bound or pending sockets retained immediately so a failed bind cannot leak a channel. */
    private final List<Channel> channels = new ArrayList<>();

    /**
     * Creates isolated loop ownership after the server layout has been validated.
     *
     * @param access tested server listener metadata
     */
    SpigotListeners(final ServerAccess access) {
        this(access, parsePortOverride(System.getProperty("raknetify.spigot.portOverride")), null);
    }

    /**
     * Selects the UDP port before allocating plugin-owned event loops.
     *
     * @param access native listener metadata whose bind addresses and login handlers are retained
     * @param portOverride UDP port from 1 through 65535, or zero to follow the native TCP ports
     * @throws IllegalArgumentException if the override is outside the usable port range
     */
    SpigotListeners(final ServerAccess access, final int portOverride) {
        this(access, portOverride, null);
    }

    /**
     * Creates listener ownership with optional packet interception for every accepted connection.
     *
     * @param access native listener metadata
     * @param portOverride UDP port, or zero to use the native TCP port
     * @param packetEvents resolved optional PacketEvents integration
     * @throws IllegalArgumentException if the override is outside the usable port range
     */
    SpigotListeners(
            final ServerAccess access,
            final int portOverride,
            final @Nullable SpigotPacketEvents packetEvents) {
        if (portOverride < 0 || portOverride > 65535) {
            throw new IllegalArgumentException(
                    ConsoleMessages.text("port_range", "raknetify.spigot.portOverride"));
        }
        this.access = access;
        this.packetEvents = packetEvents;
        this.portOverride = portOverride;
        transport = new NioEventLoopGroup(1);
        application = new DefaultEventLoopGroup(1);
    }

    /**
     * Reads an optional explicit port without silently replacing a malformed setting with TCP's
     * port.
     *
     * @param value raw system property, or null when the operator left the port unchanged
     * @return configured UDP port, or zero when the property is absent
     * @throws IllegalArgumentException if the supplied property is not a usable integer port
     */
    static int parsePortOverride(final @Nullable String value) {
        if (value == null) {
            return 0;
        }
        final int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(
                    ConsoleMessages.text("port_range", "raknetify.spigot.portOverride"), invalid);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(
                    ConsoleMessages.text("port_range", "raknetify.spigot.portOverride"));
        }
        return port;
    }

    /**
     * Binds a companion UDP socket for every ordinary IP-based Minecraft TCP listener.
     *
     * @throws IllegalStateException if no usable listener exists
     */
    void bind() {
        for (Channel listener : access.listeners()) {
            if (listener.localAddress() instanceof InetSocketAddress) {
                bind(listener);
            }
        }
        if (channels.isEmpty()) {
            throw new IllegalStateException(ConsoleMessages.text("no_tcp"));
        }
    }

    /**
     * Copies the original initializer while retaining ownership of the new socket even if binding
     * fails.
     *
     * @param listener existing TCP listener supplying the bind address and child configuration
     */
    private void bind(final Channel listener) {
        ChannelHandler acceptor = ServerAccess.acceptor(listener);
        InetSocketAddress original = (InetSocketAddress) listener.localAddress();
        InetSocketAddress address =
                portOverride == 0
                        ? original
                        : new InetSocketAddress(original.getAddress(), portOverride);
        var future =
                new ServerBootstrap()
                        .group(transport)
                        .channelFactory(this::newServerChannel)
                        .childHandler(new AcceptedChannelInitializer(acceptor))
                        .localAddress(address)
                        .bind();
        channels.add(future.channel());
        future.syncUninterruptibly();
    }

    /**
     * Reports actual bound endpoints so startup diagnostics also describe overridden UDP ports.
     *
     * @return snapshot of the plugin's bound IP endpoints
     */
    List<InetSocketAddress> localAddresses() {
        return channels.stream()
                .map(Channel::localAddress)
                .filter(InetSocketAddress.class::isInstance)
                .map(InetSocketAddress.class::cast)
                .toList();
    }

    /**
     * Constructs an NIO datagram endpoint and assigns its separate application loop before
     * registration.
     *
     * @return unregistered RakNet server channel
     */
    private RakNetServerChannel newServerChannel() {
        RakNetServerChannel channel =
                ProxyProtocol.create(() -> {
                    NioDatagramChannel datagram = new NioDatagramChannel();
                    datagram.config()
                            .setOption(ChannelOption.IP_TOS, RakNetConnectionUtil.DEFAULT_IP_TOS);
                    datagram.config()
                            .setRecvByteBufAllocator(
                                    new FixedRecvByteBufAllocator(Constants.LARGE_MTU + 512));
                    return datagram;
                });
        channel.setProvidedApplicationEventLoop(application.next());
        return channel;
    }

    /**
     * Waits for owned channels to close before terminating their event loops and leaves TCP
     * untouched.
     */
    @Override
    public void close() {
        for (Channel channel : channels) {
            channel.close().syncUninterruptibly();
        }
        channels.clear();
        transport.shutdownGracefully().syncUninterruptibly();
        application.shutdownGracefully().syncUninterruptibly();
    }

    /**
     * Adds RakNet framing around the server's existing authentication pipeline after child
     * registration.
     */
    private final class AcceptedChannelInitializer extends ChannelInitializer<Channel> {
        /** Native acceptor whose child initializer must run before transport reconciliation. */
        private final ChannelHandler acceptor;

        /**
         * Captures the listener-specific native initializer without changing its authentication
         * handlers.
         *
         * @param acceptor Minecraft TCP acceptor supplying the original child setup
         */
        private AcceptedChannelInitializer(final ChannelHandler acceptor) {
            this.acceptor = acceptor;
        }

        /**
         * Adds framing around the server's original initializer and preserves its authentication
         * handlers.
         *
         * @param channel newly accepted RakNet application channel
         * @throws Exception if either transport or native pipeline setup fails
         */
        @Override
        protected void initChannel(final Channel channel) throws Exception {
            SpigotConnection.initialize(channel);
            ServerAccess.initialize(acceptor, channel);
            SpigotConnection.finish(channel, access.privateKey());
            if (packetEvents != null) {
                packetEvents.initialize(channel);
            }
        }
    }
}
