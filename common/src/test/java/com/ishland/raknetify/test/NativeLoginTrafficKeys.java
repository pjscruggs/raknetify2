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

import io.netty.buffer.ByteBuf;

import java.security.PublicKey;

import javax.crypto.SecretKey;

/**
 * Directional session secrets negotiated by the snapshot's own packet codecs and key exchange.
 *
 * @param inbound server-to-client traffic secret
 * @param outbound client-to-server traffic secret
 */
public record NativeLoginTrafficKeys(SecretKey inbound, SecretKey outbound) {
    /**
     * Reads the native request and constructs the same key confirmation as the game client.
     *
     * @param request remaining key request payload
     * @param response destination after the serverbound key packet ID
     * @param protocol snapshot wire protocol included in the session transcript
     * @param rejectConfirmation whether this loopback control expects native key confirmation to
     *     fail
     * @return traffic keys for the subsequent encrypted round trip
     * @throws ReflectiveOperationException if the installed snapshot's native API changes
     */
    public static NativeLoginTrafficKeys negotiate(
            final ByteBuf request,
            final ByteBuf response,
            final int protocol,
            final boolean rejectConfirmation)
            throws ReflectiveOperationException {
        Class<?> hello = Class.forName("net.minecraft.network.protocol.login.ClientboundKeyPacket");
        Class<?> codec = Class.forName("net.minecraft.network.codec.StreamCodec");
        Object packet =
                codec.getMethod("decode", Object.class)
                        .invoke(hello.getField("STREAM_CODEC").get(null), request);
        Class<?> exchange = Class.forName("net.minecraft.util.crypto.SessionKeyExchange");
        Object client =
                exchange.getMethod(
                                "createClientSide",
                                String.class,
                                int.class,
                                long.class,
                                boolean.class)
                        .invoke(
                                null,
                                "EncryptionProbe",
                                protocol,
                                hello.getMethod("salt").invoke(packet),
                                hello.getMethod("shouldAuthenticate").invoke(packet));
        Class<?> peer = Class.forName("net.minecraft.util.crypto.PublicPeerInfo");
        Object server =
                peer.getConstructor(PublicKey.class, String.class)
                        .newInstance(
                                hello.getMethod("serverKey").invoke(packet),
                                hello.getMethod("serverId").invoke(packet));
        Object keys = exchange.getMethod("createSessionKeys", peer).invoke(client, server);
        Class<?> reply = Class.forName("net.minecraft.network.protocol.login.ServerboundKeyPacket");
        byte[] signature =
                (byte[]) keys.getClass().getMethod("computeIntegrityDigest").invoke(keys);
        if (rejectConfirmation) {
            signature[0] ^= 1;
        }
        Object confirmation =
                reply.getConstructor(PublicKey.class, byte[].class)
                        .newInstance(exchange.getMethod("publicKey").invoke(client), signature);
        codec.getMethod("encode", Object.class, Object.class)
                .invoke(reply.getField("STREAM_CODEC").get(null), response, confirmation);
        Object side = exchange.getField("DEFAULT_CLIENT_SIDE").get(null);
        return new NativeLoginTrafficKeys(
                (SecretKey)
                        keys.getClass()
                                .getMethod("inboundStreamKey", side.getClass())
                                .invoke(keys, side),
                (SecretKey)
                        keys.getClass()
                                .getMethod("outboundStreamKey", side.getClass())
                                .invoke(keys, side));
    }
}
