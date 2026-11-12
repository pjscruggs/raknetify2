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

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * Requires an FML play channel and witnesses a bidirectional exchange after the real client joins.
 */
@Mod("raknetify_transport_test")
public final class ForgeChannelWitness {
    /** Distinct values catch a dropped, echoed or incorrectly decoded payload. */
    private static final int CHALLENGE = 0x36c71201;

    /** Strict channel version participates in Forge's native mod negotiation. */
    private static final String VERSION = System.getProperty("raknetify.test.fmlVersion", "1");

    /** Standard Forge play channel through which the two independently decoded packets travel. */
    private static final SimpleChannel CHANNEL =
            NetworkRegistry.newSimpleChannel(
                    ResourceLocation.fromNamespaceAndPath("raknetify_transport_test", "witness"),
                    () -> VERSION,
                    VERSION::equals,
                    VERSION::equals);

    /** Registers the mod packet with Forge before FML starts its handshake. */
    public ForgeChannelWitness() {
        CHANNEL.registerMessage(
                0,
                Integer.class,
                (value, buf) -> buf.writeInt(value),
                buf -> buf.readInt(),
                ForgeChannelWitness::receive);
        MinecraftForge.EVENT_BUS.addListener(this::login);
    }

    /**
     * Challenges the joining player only after Forge has accepted the mod channel.
     *
     * @param event loader event after the server places a player in the world
     */
    private void login(final PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), CHALLENGE);
        }
    }

    /**
     * Replies on the receiving client and checks the changed value on the originating server.
     *
     * @param value integer decoded from the native Forge payload
     * @param supplier network context indicating which logical side owns this packet
     * @throws IllegalStateException if the value or packet direction differs from the challenge
     */
    private static void receive(final int value, final Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        if (context.getDirection() == NetworkDirection.PLAY_TO_CLIENT && value == CHALLENGE) {
            CHANNEL.reply(~value, context);
        } else if (context.getDirection() == NetworkDirection.PLAY_TO_SERVER
                && value == ~CHALLENGE) {
            System.out.println("PASS FML play channel round trip");
        } else {
            throw new IllegalStateException("Unexpected Forge witness value or direction");
        }
        context.setPacketHandled(true);
    }
}
