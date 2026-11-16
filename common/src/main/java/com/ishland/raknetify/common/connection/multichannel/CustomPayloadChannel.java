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

package com.ishland.raknetify.common.connection.multichannel;

import com.ishland.raknetify.api.PacketOrdering;
import com.ishland.raknetify.common.Constants;
import com.ishland.raknetify.common.connection.PayloadOrderingNegotiation;
import com.ishland.raknetify.common.connection.RakNetSimpleMultiChannelCodec;
import com.ishland.raknetify.common.util.MathUtil;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

import network.ycc.raknet.client.channel.RakNetClientChannel;
import network.ycc.raknet.client.channel.RakNetClientThreadedChannel;

import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * Routes declared payloads with their vanilla dependencies and preserves send boundaries around
 * payloads whose dependencies are unknown.
 */
public class CustomPayloadChannel {

    /**
     * Recognizes a protocol's custom-payload packets before looking up their namespaced
     * identifiers.
     */
    public static class PayloadClassifier implements RakNetSimpleMultiChannelCodec.OverrideHandler {

        /** Protocol-specific predicate identifying custom-payload packet IDs. */
        private final IntPredicate isCustomPayload;

        /** Connection-local peer declarations, absent for classifiers used without a transport. */
        private final @Nullable Channel channel;

        /** Whether this classifier routes outgoing client traffic. */
        private final boolean clientSender;

        /**
         * Local declarations take precedence over peer preferences and stay fixed for this
         * classifier.
         */
        private final Map<String, Integer> local;

        /**
         * Creates a classifier for the custom-payload IDs used by one protocol direction.
         *
         * @param isCustomPayload predicate recognizing custom-payload packet IDs
         */
        public PayloadClassifier(final IntPredicate isCustomPayload) {
            this(isCustomPayload, null);
        }

        /**
         * Creates a classifier using local rules and declarations from this connection's peer.
         *
         * @param isCustomPayload predicate recognizing custom-payload packet IDs
         * @param channel owning connection, or null to use local registrations only
         */
        public PayloadClassifier(
                final IntPredicate isCustomPayload, final @Nullable Channel channel) {
            this(
                    isCustomPayload,
                    channel,
                    channel instanceof RakNetClientChannel
                            || channel instanceof RakNetClientThreadedChannel);
        }

        /**
         * Creates a classifier for one connection and outgoing direction.
         *
         * @param isCustomPayload predicate recognizing payload packet IDs
         * @param channel owning connection, or null for local registrations
         * @param clientSender true when routing serverbound packets
         */
        public PayloadClassifier(
                final IntPredicate isCustomPayload,
                final @Nullable Channel channel,
                final boolean clientSender) {
            this.clientSender = clientSender;
            this.isCustomPayload = Objects.requireNonNull(isCustomPayload);
            this.channel = channel;
            this.local = PacketOrdering.snapshot();
        }

        /**
         * Inspects modern identifier-prefixed custom payloads without consuming the original
         * buffer.
         *
         * @param origBuf Minecraft payload beginning with its packet ID
         * @param suppressWarning unused because unregistered payloads need no diagnostic
         * @return declared route or a delivery fence for a custom payload, zero for other packets
         */
        @Override
        public int getChannelOverride(final ByteBuf origBuf, final boolean suppressWarning) {
            ByteBuf buf = origBuf.slice();
            final int packetId = MathUtil.readVarInt(buf);
            if (isCustomPayload.test(packetId)) {
                final String identifier =
                        MathUtil.readString(buf); // we assume modern custom payloads
                if (Constants.DEBUG) {
                    System.out.println("Raknetify: Handling custom payload: " + identifier);
                }
                int installed = ModPayloadOrdering.channel(identifier, buf, clientSender);
                if (installed != 0) {
                    return installed;
                }
                // Forge multiplexes entity spawns and container openings on this identifier.
                if ("fml:play".equals(identifier) && buf.isReadable()) {
                    int discriminator = buf.readUnsignedByte();
                    if (discriminator == 0) {
                        return 2;
                    }
                    if (discriminator == 1) {
                        return ModPayloadOrdering.FENCED;
                    }
                }
                Integer declared = local.get(identifier);
                if (declared != null) {
                    return declared;
                }
                int remote = PayloadOrderingNegotiation.remoteChannel(channel, identifier);
                return remote == 0 ? ModPayloadOrdering.FENCED : remote;
            } else {
                return 0;
            }
        }
    }
}
