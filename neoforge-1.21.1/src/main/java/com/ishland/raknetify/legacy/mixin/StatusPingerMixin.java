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
import net.minecraft.util.debugchart.LocalSampleLogger;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.net.InetSocketAddress;

/** Opens a server-list connection using the same transport and SRV destination as joining. */
@Mixin(value = ServerStatusPinger.class, remap = false)
abstract class StatusPingerMixin {
    /**
     * Preserves the native status handshake on the selected transport.
     *
     * @param endpoint resolved destination approved by Minecraft
     * @param nativeIo whether Minecraft requests a native socket
     * @param logger optional native sample logger
     * @param entry saved server-list entry
     * @param update native status callback
     * @param save native persistence callback
     * @return initialized status connection
     */
    @Redirect(
            method = "pingServer",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/network/Connection;connectToServer(Ljava/net/InetSocketAddress;ZLnet/minecraft/util/debugchart/LocalSampleLogger;)Lnet/minecraft/network/Connection;"))
    private Connection openNamed(
            final InetSocketAddress endpoint,
            final boolean nativeIo,
            final @Nullable LocalSampleLogger logger,
            final ServerData entry,
            final Runnable update,
            final Runnable save) {
        return StatusTransport.open(
                entry.ip, () -> Connection.connectToServer(endpoint, nativeIo, logger));
    }
}
