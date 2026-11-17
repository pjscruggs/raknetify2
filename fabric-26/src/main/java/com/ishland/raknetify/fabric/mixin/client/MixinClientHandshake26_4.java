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

package com.ishland.raknetify.fabric.mixin.client;

import com.ishland.raknetify.fabric.common.connection.SnapshotFrameEncryption;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.network.Connection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;

/**
 * Applies the snapshot client's inbound and outbound options at their distinct handshake
 * boundaries.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl")
public class MixinClientHandshake26_4 {
    /**
     * Substitutes the RakNet inbound frame handler when this connection uses UDP.
     *
     * @param connection login connection
     * @param cipher vanilla stream cipher
     * @param key negotiated inbound secret
     * @return whether to install the vanilla cipher
     */
    @WrapWithCondition(
            method = "applyInboundConnectionOptions",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/network/Connection;setInboundEncryptionKey(Ljavax/crypto/Cipher;)V"),
            remap = false)
    private boolean enableInbound(
            final Connection connection, final Cipher cipher, @Local final SecretKey key) {
        return SnapshotFrameEncryption.useStreamCipher(connection, key, true);
    }

    /**
     * Substitutes the RakNet outbound frame handler when this connection uses UDP.
     *
     * @param connection login connection
     * @param cipher vanilla stream cipher
     * @param key negotiated outbound secret
     * @return whether to install the vanilla cipher
     */
    @WrapWithCondition(
            method = "applyOutboundConnectionOptions",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/network/Connection;setOutboundEncryptionKey(Ljavax/crypto/Cipher;)V"),
            remap = false)
    private boolean enableOutbound(
            final Connection connection, final Cipher cipher, @Local final SecretKey key) {
        return SnapshotFrameEncryption.useStreamCipher(connection, key, false);
    }
}
