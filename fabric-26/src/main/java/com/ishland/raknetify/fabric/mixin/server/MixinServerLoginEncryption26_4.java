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

package com.ishland.raknetify.fabric.mixin.server;

import com.ishland.raknetify.fabric.common.connection.SnapshotFrameEncryption;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.network.Connection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;

/**
 * Applies both snapshot server traffic keys when Minecraft accepts the client's options
 * acknowledgement.
 */
@Mixin(net.minecraft.server.network.ServerLoginPacketListenerImpl.class)
public class MixinServerLoginEncryption26_4 {
    /**
     * Preserves the native TCP installation and substitutes two directional frame handlers on UDP.
     *
     * @param connection native login connection
     * @param decrypt native decryption cipher
     * @param encrypt native encryption cipher
     * @param inbound negotiated client-to-server key
     * @param outbound negotiated server-to-client key
     * @return whether to install the native stream ciphers
     */
    @WrapWithCondition(
            method = "applyConnectionOptions",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/network/Connection;setEncryptionKey(Ljavax/crypto/Cipher;Ljavax/crypto/Cipher;)V"),
            remap = false)
    private boolean enableEncryption(
            final Connection connection,
            final Cipher decrypt,
            final Cipher encrypt,
            @Local(ordinal = 0) final SecretKey inbound,
            @Local(ordinal = 1) final SecretKey outbound) {
        if (SnapshotFrameEncryption.useStreamCipher(connection, inbound, true)) {
            return true;
        }
        return SnapshotFrameEncryption.useStreamCipher(connection, outbound, false);
    }
}
