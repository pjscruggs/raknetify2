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

package com.ishland.raknetify.legacy;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;

/** Identifies the play protocol and the ordering boundaries specific to this game version. */
final class GamePackets {
    /** Wire protocol used by the bundled routing table. */
    static final int PROTOCOL = 763;

    /** Serverbound play custom-payload discriminator. */
    static final int SERVERBOUND_PAYLOAD = 13;

    /** Clientbound play custom-payload discriminator. */
    static final int CLIENTBOUND_PAYLOAD = 23;

    /** All protocol checks operate on the live application channel. */
    private GamePackets() {}

    /**
     * Enables packet classification only after the outgoing protocol enters play.
     *
     * @param channel active application channel associated with the Minecraft connection
     * @return whether the outbound encoder is configured for play packets
     */
    static boolean playing(final Channel channel) {
        return channel.attr(AttributeKey.<ConnectionProtocol>valueOf("protocol")).get()
                == ConnectionProtocol.PLAY;
    }

    /**
     * Drains pending frames before entering a world or reconfiguring the connection.
     *
     * @param msg outbound Minecraft packet or pipeline message
     * @return whether queued writes need synchronization before forwarding this packet
     */
    static boolean worldBoundary(final Object msg) {
        return msg instanceof ClientboundRespawnPacket || msg instanceof ClientboundLoginPacket;
    }
}
