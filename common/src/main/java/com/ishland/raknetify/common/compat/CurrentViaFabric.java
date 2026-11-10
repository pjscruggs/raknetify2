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

package com.ishland.raknetify.common.compat;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * Adapts ViaFabric's current protocol and shared ViaVersion codecs without linking optional mods.
 */
public final class CurrentViaFabric {
    /** Prevents creating the stateless optional compatibility adapter. */
    private CurrentViaFabric() {}

    /**
     * Uses the current ViaFabric API when present, preserving the caller's older adapter otherwise.
     *
     * @param channel initialized Minecraft application channel
     * @param client whether the channel belongs to the connecting client
     * @param encoder native encoder or outbound configuration handler
     * @param decoder native decoder or inbound configuration handler
     * @return true when the current API handled this connection, false for an older ViaFabric
     *     release
     * @throws ReflectiveOperationException if the detected current API cannot initialize correctly
     */
    public static boolean inject(
            final Channel channel, final boolean client, final String encoder, final String decoder)
            throws ReflectiveOperationException {
        Object protocol = currentProtocol();
        if (protocol == null) {
            return false;
        }
        Class<?> userType =
                Class.forName("com.viaversion.viaversion.api.connection.UserConnection");
        Object user =
                Class.forName("com.viaversion.viaversion.connection.UserConnectionImpl")
                        .getConstructor(Channel.class, boolean.class)
                        .newInstance(channel, client);
        Object protocols =
                Class.forName("com.viaversion.viaversion.protocol.ProtocolPipelineImpl")
                        .getConstructor(userType)
                        .newInstance(user);
        Class.forName("com.viaversion.viaversion.api.protocol.ProtocolPipeline")
                .getMethod("add", Class.forName("com.viaversion.viaversion.api.protocol.Protocol"))
                .invoke(protocols, protocol);
        addCodec(
                channel.pipeline(),
                encoder,
                "com.viaversion.viaversion.platform.ViaEncodeHandler",
                userType,
                user);
        addCodec(
                channel.pipeline(),
                decoder,
                "com.viaversion.fabric.common.handler.FabricDecodeHandler",
                userType,
                user);
        return true;
    }

    /**
     * Obtains the selected ViaFabric runtime's protocol instead of guessing its version-specific
     * package.
     *
     * @return active protocol, or null when the older platform has no current protocol accessor
     * @throws ReflectiveOperationException if the detected accessor cannot supply its protocol
     */
    private static @Nullable Object currentProtocol() throws ReflectiveOperationException {
        final Method accessor;
        try {
            accessor =
                    Class.forName("com.viaversion.fabric.common.provider.AbstractFabricPlatform")
                            .getDeclaredMethod("customProtocol");
        } catch (ClassNotFoundException | NoSuchMethodException olderRelease) {
            return null;
        }
        Object platform =
                Class.forName("com.viaversion.viaversion.api.Via")
                        .getMethod("getPlatform")
                        .invoke(null);
        accessor.setAccessible(true);
        return accessor.invoke(platform);
    }

    /**
     * Constructs the installed handler and uses its declared pipeline name, including inherited
     * names.
     *
     * @param pipeline connection pipeline receiving the codec
     * @param before existing native handler that the translation codec must precede
     * @param className installed translation handler class
     * @param userType optional ViaVersion connection interface
     * @param user connection-local translation state
     * @throws ReflectiveOperationException if the handler API does not match its supported contract
     */
    private static void addCodec(
            final ChannelPipeline pipeline,
            final String before,
            final String className,
            final Class<?> userType,
            final Object user)
            throws ReflectiveOperationException {
        Class<?> handlerType = Class.forName(className);
        String name = (String) handlerType.getField("NAME").get(null);
        ChannelHandler handler =
                (ChannelHandler) handlerType.getConstructor(userType).newInstance(user);
        pipeline.addBefore(before, name, handler);
    }
}
