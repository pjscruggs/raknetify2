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

package com.ishland.raknetify.fabric.common.connection;

import com.ishland.raknetify.common.connection.MultiChannelingStreamingCompression;
import com.ishland.raknetify.common.connection.MultiChannellingEncryption;
import com.ishland.raknetify.fabric.mixin.access.IClientConnection;

import io.netty.channel.Channel;

import net.minecraft.network.Connection;

import network.ycc.raknet.RakNet;

import java.util.Objects;

import javax.crypto.SecretKey;

/** Installs frame ciphers from the snapshot login listener's independent traffic keys. */
public final class SnapshotFrameEncryption {
    /** Utility class for the two login listeners. */
    private SnapshotFrameEncryption() {}

    /**
     * Installs one negotiated direction at Minecraft's normal activation point.
     *
     * @param connection connection applying its login options
     * @param key actual stream secret for this direction, independent of its IV
     * @param inbound whether Minecraft is enabling inbound decryption
     * @return true when the native stream handler should run for a TCP connection
     */
    public static boolean useStreamCipher(
            final Connection connection, final SecretKey key, final boolean inbound) {
        Channel channel = Objects.requireNonNull(((IClientConnection) connection).getChannel());
        if (!(channel.config() instanceof RakNet.Config)) {
            return true;
        }
        channel.pipeline()
                .addBefore(
                        MultiChannelingStreamingCompression.NAME,
                        MultiChannellingEncryption.NAME + (inbound ? "-inbound" : "-outbound"),
                        inbound
                                ? MultiChannellingEncryption.inbound(key)
                                : MultiChannellingEncryption.outbound(key));
        return false;
    }
}
