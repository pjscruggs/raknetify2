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

import com.ishland.raknetify.legacy.StatusTransport;

import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.network.Connection;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;

/** Opens a server-list connection using the same transport and SRV destination as joining. */
@Mixin(value = ServerStatusPinger.class, remap = false)
abstract class StatusPingerMixin {
    /**
     * Preserves the native status handshake on the selected transport.
     *
     * @param endpoint resolved destination approved by Minecraft
     * @param nativeIo whether Minecraft requests a native socket
     * @param entry saved server-list entry
     * @param update native status callback
     * @return initialized status connection
     */
    @Group(name = "statusFactory", min = 1, max = 1)
    @Redirect(
            method = {"m_105459_", "pingServer"},
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/network/Connection;m_178300_(Ljava/net/InetSocketAddress;Z)Lnet/minecraft/network/Connection;"),
            require = 0)
    private Connection openProduction(
            final InetSocketAddress endpoint,
            final boolean nativeIo,
            final ServerData entry,
            final Runnable update) {
        String ip =
                ObfuscationReflectionHelper.getPrivateValue(ServerData.class, entry, "f_105363_");
        return StatusTransport.open(ip, () -> production(endpoint, nativeIo));
    }

    /**
     * Preserves the native status handshake on the selected transport.
     *
     * @param endpoint resolved destination approved by Minecraft
     * @param nativeIo whether Minecraft requests a native socket
     * @param entry saved server-list entry
     * @param update native status callback
     * @return initialized status connection
     */
    @Group(name = "statusFactory", min = 1, max = 1)
    @Redirect(
            method = {"m_105459_", "pingServer"},
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/network/Connection;connectToServer(Ljava/net/InetSocketAddress;Z)Lnet/minecraft/network/Connection;"),
            require = 0)
    private Connection openNamed(
            final InetSocketAddress endpoint,
            final boolean nativeIo,
            final ServerData entry,
            final Runnable update) {
        return StatusTransport.open(entry.ip, () -> Connection.connectToServer(endpoint, nativeIo));
    }

    /**
     * Calls Forge's SRG-named static factory in its production namespace.
     *
     * @param endpoint resolved destination
     * @param nativeIo Minecraft's transport preference
     * @return native connection
     * @throws IllegalStateException if the pinned Forge status factory cannot be called
     */
    private static Connection production(final InetSocketAddress endpoint, final boolean nativeIo) {
        Method method =
                ObfuscationReflectionHelper.findMethod(
                        Connection.class, "m_178300_", InetSocketAddress.class, boolean.class);
        try {
            return (Connection) method.invoke(null, endpoint, nativeIo);
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw new IllegalStateException("Cannot open Forge status connection", failure);
        }
    }
}
