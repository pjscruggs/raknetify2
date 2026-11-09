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

package com.ishland.raknetify.test;

import com.ishland.raknetify.common.util.ThreadLocalUtil;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;

import network.ycc.raknet.server.channel.RakNetServerChannel;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Exercises transformed Minecraft connection bootstrap against a silent DNS answer and a real
 * alternate.
 */
final class AddressFallbackNativeTest {
    /** Prevents construction of this process-owned native connectivity witness. */
    private AddressFallbackNativeTest() {}

    /**
     * Runs on transfer-capable versions without opening graphics, sending credentials, or
     * contacting the Internet.
     *
     * @throws Exception if the transformed client cannot finish a real handshake through its
     *     alternate address
     */
    static void verify() throws Exception {
        if (!FixtureRuntime.modern() && !FixtureRuntime.atLeast("1.20.5")) {
            return;
        }
        NioEventLoopGroup loops = new NioEventLoopGroup(1);
        Channel server = null;
        Channel client = null;
        try {
            server =
                    new ServerBootstrap()
                            .group(loops)
                            .channel(RakNetServerChannel.class)
                            .childHandler(new EmptyChild())
                            .bind(
                                    new InetSocketAddress(
                                            InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0))
                            .sync()
                            .channel();
            int port = ((InetSocketAddress) server.localAddress()).getPort();
            try (DatagramSocket blackhole =
                    new DatagramSocket(port, InetAddress.getByAddress(new byte[] {127, 0, 0, 2}))) {
                InetSocketAddress destination =
                        new InetSocketAddress(
                                InetAddress.getAllByName("fallback.fixture.test")[0], port);
                require(
                        destination.getAddress().equals(blackhole.getLocalAddress()),
                        "Fixture must prefer the silent address");
                ChannelFuture connection = connect(destination);
                client = connection.channel();
                require(
                        connection.await(5, TimeUnit.SECONDS),
                        "Native fallback handshake timed out");
                require(connection.isSuccess(), "Native fallback failed: " + connection.cause());
                require(
                        server.localAddress().equals(client.remoteAddress()),
                        "Native connection selected the wrong peer");
            }
            System.out.println(
                    "PASS native multi-address RakNet handshake with unreachable first address");
        } finally {
            close(client);
            close(server);
            loops.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        }
    }

    /**
     * Calls the actual native factory under the same scoped flags used by the connection-screen
     * hook.
     *
     * @param destination hostname whose first address is intentionally silent
     * @return native application's handshake future after all production mixins have run
     * @throws ReflectiveOperationException if a version's native bootstrap contract changed
     */
    private static ChannelFuture connect(final InetSocketAddress destination)
            throws ReflectiveOperationException {
        Class<?> type =
                Class.forName(
                        FixtureRuntime.modern()
                                ? "net.minecraft.network.Connection"
                                : "net.minecraft.class_2535");
        Method factory =
                Arrays.stream(type.getDeclaredMethods())
                        .filter(AddressFallbackNativeTest::isFactory)
                        .findFirst()
                        .orElseThrow();
        Constructor<?> constructor =
                Arrays.stream(type.getConstructors())
                        .filter(item -> item.getParameterCount() == 1)
                        .findFirst()
                        .orElseThrow();
        Object connection =
                constructor.newInstance(constructor.getParameterTypes()[0].getEnumConstants()[0]);
        Object backend = backend(factory.getParameterTypes()[1]);
        try {
            ThreadLocalUtil.setInitializingRaknet(true);
            return (ChannelFuture) factory.invoke(null, destination, backend, connection);
        } finally {
            ThreadLocalUtil.setInitializingRaknet(false);
        }
    }

    /**
     * Recognizes the asynchronous native socket factory by its stable structural contract.
     *
     * @param method declared Minecraft method considered by this version-independent fixture
     * @return whether it accepts an endpoint, transport selection, and existing connection
     */
    private static boolean isFactory(final Method method) {
        return Modifier.isStatic(method.getModifiers())
                && method.getReturnType() == ChannelFuture.class
                && method.getParameterCount() == 3
                && method.getParameterTypes()[0] == InetSocketAddress.class;
    }

    /**
     * Requests the NIO client backend through each native generation's ordinary factory.
     *
     * @param type boolean on older versions, otherwise the native backend holder type
     * @return backend argument accepted by Minecraft's asynchronous connection factory
     * @throws ReflectiveOperationException if the native backend factory is unavailable
     */
    private static Object backend(final Class<?> type) throws ReflectiveOperationException {
        if (type == boolean.class) {
            return false;
        }
        Method factory =
                Arrays.stream(type.getDeclaredMethods())
                        .filter(method ->
                                Modifier.isStatic(method.getModifiers())
                                        && method.getReturnType() == type
                                        && Arrays.equals(
                                                method.getParameterTypes(),
                                                new Class<?>[] {boolean.class}))
                        .findFirst()
                        .orElseThrow();
        return factory.invoke(null, false);
    }

    /**
     * Closes every created endpoint even when its handshake or an assertion failed.
     *
     * @param channel owned channel, or null if its bootstrap never produced one
     */
    private static void close(final @org.jspecify.annotations.Nullable Channel channel) {
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
    }

    /**
     * Fails the fixture without relying on JVM assertion settings.
     *
     * @param condition property required for this connectivity witness
     * @param message failure context retained in the CI log
     * @throws IllegalStateException if the required connectivity property does not hold
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * Leaves accepted transport children free of application traffic until the fixture closes them.
     */
    private static final class EmptyChild extends ChannelInitializer<Channel> {
        /**
         * Retains only the transport's built-in handshake handlers for the isolated server.
         *
         * @param channel accepted application channel that receives no Minecraft login packets
         */
        @Override
        protected void initChannel(final Channel channel) {}
    }
}
