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

package com.ishland.raknetify.common.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ishland.raknetify.common.connection.multichannel.ModPayloadOrdering;
import com.ishland.raknetify.common.data.ProtocolMultiChannelMappings;

import io.netty.buffer.Unpooled;

import org.junit.jupiter.api.Test;

/** Checks runtime mod registration without linking optional game or mod classes. */
class ModPayloadOrderingTest {
    /** Installed versions may allocate different message IDs without changing dependencies. */
    @Test
    void bindsInstalledIdsAndKeepsUnknownMessagesUntouched() {
        ModPayloadOrdering.register(
                "test:alpha", 250, "net.minecraftforge.network.PlayMessages$SpawnEntity");
        ModPayloadOrdering.register("test:alpha", 251, "absent.mod.UnknownPacket");
        ModPayloadOrdering.register(
                "test:other", 9, "net.minecraftforge.network.PlayMessages$SpawnEntity");
        assertRoute("test:alpha", 250, false, 2);
        assertRoute("test:other", 9, false, 2);
        assertRoute("test:alpha", 9, false, 0);
        assertRoute("test:alpha", 251, false, 0);
    }

    /** Container inputs and contents each share their vanilla ordering lane. */
    @Test
    void followsVanillaInventoryInEachDirection() {
        String packet = "net.p3pp3rf1y.sophisticatedcore.network.SyncContainerStacksMessage";
        ModPayloadOrdering.register("test:menu", 300, packet);
        var mapping = ProtocolMultiChannelMappings.INSTANCE.mappings.get(763);
        assertRoute("test:menu", 300, false, mapping.s2c.get(18));
        assertRoute("test:menu", 300, true, mapping.c2s.get(11));
    }

    /** Container factories and split wrappers may reference entity, menu and chunk state. */
    @Test
    void mixedDependenciesRequireDeliveryBoundaries() {
        ModPayloadOrdering.register(
                "test:mixed", 100, "net.minecraftforge.network.PlayMessages$OpenContainer");
        assertRoute("test:mixed", 100, false, ModPayloadOrdering.FENCED);
        assertRoute("forge:split", 1, false, ModPayloadOrdering.FENCED);
        var mapping = ProtocolMultiChannelMappings.INSTANCE.mappings.get(763);
        assertEquals(ModPayloadOrdering.FENCED, mapping.s2c.get(3));
        assertEquals(ModPayloadOrdering.FENCED, mapping.s2c.get(32));
        assertEquals(ModPayloadOrdering.FENCED, mapping.s2c.get(48));
    }

    /** Raw inventory channels must precede subsequent held-item changes and menu clicks. */
    @Test
    void eventChannelsRetainDirectionAndUnknownFallback() {
        assertRoute("jei:channel", 0, true, 2);
        assertRoute("jei:channel", 0, false, 3);
        assertRoute("trade_cycling:cycle_trades", 0, true, 2);
        assertRoute("unknown:channel", 0, false, 0);
    }

    /** Health flags and hunger metadata share the ordering lane used by vanilla health updates. */
    @Test
    void healthMetadataSharesHealthUpdateOrdering() {
        int health = ProtocolMultiChannelMappings.INSTANCE.mappings.get(763).s2c.get(87);
        for (String type :
                new String[] {
                    "tamaized.voidscape.network.client.ClientPacketNoFlashOnSetHealth",
                    "squeek.appleskin.network.MessageExhaustionSync",
                    "squeek.appleskin.network.MessageSaturationSync"
                }) {
            ModPayloadOrdering.register("test:health", 40, type);
            assertRoute("test:health", 40, false, health);
        }
    }

    /** Architectury's envelope retains the ordering of the concrete message within it. */
    @Test
    void unwrapsKnownArchitecturyPacketsWithoutChangingUnknownOnes() {
        String type = "org.patryk3211.powergrid.network.packets.UpdateComponentBiPacket";
        String hash =
                java.util
                        .UUID
                        .nameUUIDFromBytes(type.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString()
                        .replace("-", "");
        assertNamed("powergrid:main/" + hash, false, 7);
        assertNamed("powergrid:main/" + hash, true, 2);
        assertNamed("ftbessentials:update_tab_name", false, 1);
        assertNamed("other:packet", false, 0);
    }

    /** Power Grid batches with mixed keys need a boundary without serializing block-only ticks. */
    @Test
    void separatesPowerGridBlockBatchesFromUuidEndpoints() {
        String type = "org.patryk3211.powergrid.network.packets.StateS2CPacket";
        String inner =
                "powergrid:main/"
                        + java.util
                                .UUID
                                .nameUUIDFromBytes(
                                        type.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                .toString()
                                .replace("-", "");
        byte[] identifier = inner.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var bytes =
                Unpooled.buffer()
                        .writeByte(identifier.length)
                        .writeBytes(identifier)
                        .writeBoolean(false)
                        .writeInt(1)
                        .writeByte(0)
                        .writeLong(42);
        try {
            assertEquals(7, ModPayloadOrdering.channel("architectury:network", bytes, false));
            bytes.setByte(1 + identifier.length + 5, 1);
            assertEquals(
                    ModPayloadOrdering.FENCED,
                    ModPayloadOrdering.channel("architectury:network", bytes, false));
            assertEquals(0, bytes.readerIndex());
        } finally {
            bytes.release();
        }
    }

    /**
     * Verifies a namespaced envelope without consuming its bytes.
     *
     * @param identifier inner Architectury identifier
     * @param clientSender outgoing direction
     * @param expected ordering policy
     */
    private static void assertNamed(
            final String identifier, final boolean clientSender, final int expected) {
        byte[] text = identifier.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var bytes = Unpooled.buffer().writeByte(text.length).writeBytes(text);
        try {
            assertEquals(
                    expected,
                    ModPayloadOrdering.channel("architectury:network", bytes, clientSender));
            assertEquals(0, bytes.readerIndex());
        } finally {
            bytes.release();
        }
    }

    /**
     * Verifies classification preserves ownership and the reader position behind a prefix.
     *
     * @param identifier registered or raw mod channel
     * @param discriminator installed message ID
     * @param clientSender outgoing direction
     * @param expected required lane or boundary
     */
    private static void assertRoute(
            final String identifier,
            final int discriminator,
            final boolean clientSender,
            final int expected) {
        var bytes = Unpooled.buffer().writeByte(99);
        int value = discriminator;
        while (value > 127) {
            bytes.writeByte((value & 127) | 128);
            value >>>= 7;
        }
        bytes.writeByte(value);
        bytes.readerIndex(1);
        try {
            assertEquals(expected, ModPayloadOrdering.channel(identifier, bytes, clientSender));
            assertEquals(1, bytes.readerIndex());
            assertEquals(1, bytes.refCnt());
        } finally {
            bytes.release();
        }
    }
}
