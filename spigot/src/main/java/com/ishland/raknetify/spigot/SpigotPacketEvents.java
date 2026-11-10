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

import io.netty.channel.Channel;

import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * Initializes optional PacketEvents handlers without applying its TCP acceptor to UDP datagrams.
 */
final class SpigotPacketEvents {
    /** PacketEvents initializer accepting a fully constructed Minecraft channel. */
    private final Method initializer;

    /** Handshake state ensures PacketEvents observes login and configuration before play. */
    private final Object handshaking;

    /** Protocol registry used to avoid installing duplicate handlers. */
    private final Object protocolManager;

    /** Lookup preserving a user already installed by another compatible integration. */
    private final Method getUser;

    /**
     * Resolves the active plugin's own classes without bundling or shading another PacketEvents
     * copy.
     *
     * @param loader class loader owned by the enabled PacketEvents plugin
     * @throws ReflectiveOperationException if the installed API is incompatible
     */
    private SpigotPacketEvents(final ClassLoader loader) throws ReflectiveOperationException {
        Class<?> state =
                Class.forName(
                        "com.github.retrooper.packetevents.protocol.ConnectionState", true, loader);
        handshaking = state.getField("HANDSHAKING").get(null);
        initializer =
                Class.forName(
                                "io.github.retrooper.packetevents.injector.connection.ServerConnectionInitializer",
                                true,
                                loader)
                        .getMethod("initChannel", Object.class, state);
        Object api =
                Class.forName("com.github.retrooper.packetevents.PacketEvents", true, loader)
                        .getMethod("getAPI")
                        .invoke(null);
        Class<?> apiType =
                Class.forName("com.github.retrooper.packetevents.PacketEventsAPI", true, loader);
        protocolManager = apiType.getMethod("getProtocolManager").invoke(api);
        getUser =
                Class.forName(
                                "com.github.retrooper.packetevents.manager.protocol.ProtocolManager",
                                true,
                                loader)
                        .getMethod("getUser", Object.class);
    }

    /**
     * Enables the adapter only when the optional PacketEvents plugin is enabled.
     *
     * @param plugin installed PacketEvents plugin, or null when absent
     * @return resolved adapter, or null when no active plugin needs initialization
     * @throws ReflectiveOperationException if an enabled plugin has an unsupported API
     */
    static @Nullable SpigotPacketEvents load(final @Nullable Plugin plugin)
            throws ReflectiveOperationException {
        return plugin == null || !plugin.isEnabled()
                ? null
                : new SpigotPacketEvents(plugin.getClass().getClassLoader());
    }

    /**
     * Installs the normal handshake observer before the first Minecraft packet is delivered.
     *
     * @param channel RakNet application channel with native Minecraft codecs installed
     * @throws ReflectiveOperationException if PacketEvents cannot initialize the channel
     */
    void initialize(final Channel channel) throws ReflectiveOperationException {
        if (getUser.invoke(protocolManager, channel) == null) {
            initializer.invoke(null, channel, handshaking);
        }
    }
}
