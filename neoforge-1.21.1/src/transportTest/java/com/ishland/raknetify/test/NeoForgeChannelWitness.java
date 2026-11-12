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

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Exercises a required NeoForge payload after the native configuration handshake. */
@Mod("raknetify_transport_test")
public final class NeoForgeChannelWitness {
    /** Known challenge whose bitwise inverse is required in the reply. */
    private static final int CHALLENGE = 0x36c71211;

    /**
     * Registers the payload and subscribes to server-side player admission.
     *
     * @param bus native mod event bus for payload registration
     */
    public NeoForgeChannelWitness(final IEventBus bus) {
        bus.addListener(this::register);
        NeoForge.EVENT_BUS.addListener(this::login);
    }

    /**
     * Registers a bidirectional play payload using the game's integer codec.
     *
     * @param event registration phase before native handshake negotiation
     */
    private void register(final RegisterPayloadHandlersEvent event) {
        event.registrar(System.getProperty("raknetify.test.fmlVersion", "1"))
                .playBidirectional(Witness.TYPE, Witness.CODEC, NeoForgeChannelWitness::receive);
    }

    /**
     * Sends a challenge after the modded player has entered the world.
     *
     * @param event native player login event
     */
    private void login(final PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PacketDistributor.sendToPlayer(player, new Witness(CHALLENGE));
        }
    }

    /**
     * Verifies both packet directions and the payload value before recording success.
     *
     * @param packet native decoded payload
     * @param context negotiated connection and flow
     * @throws IllegalStateException if the value or packet direction differs from the challenge
     */
    private static void receive(final Witness packet, final IPayloadContext context) {
        if (context.flow() == PacketFlow.CLIENTBOUND && packet.value() == CHALLENGE) {
            context.reply(new Witness(~packet.value()));
        } else if (context.flow() == PacketFlow.SERVERBOUND && packet.value() == ~CHALLENGE) {
            System.out.println("PASS FML play channel round trip");
        } else {
            throw new IllegalStateException("Unexpected NeoForge witness value or direction");
        }
    }

    /**
     * Carries the challenge and its response over NeoForge's registered payload protocol.
     *
     * @param value native integer challenge or verified response
     */
    private record Witness(int value) implements CustomPacketPayload {
        /** Identifier included in NeoForge's negotiated channel inventory. */
        private static final Type<Witness> TYPE =
                new Type<>(
                        ResourceLocation.fromNamespaceAndPath(
                                "raknetify_transport_test", "witness"));

        /** Bidirectional integer serializer used by both endpoints. */
        private static final StreamCodec<ByteBuf, Witness> CODEC =
                ByteBufCodecs.INT.map(Witness::new, Witness::value);

        /** {@inheritDoc} */
        @Override
        public Type<Witness> type() {
            return TYPE;
        }
    }
}
