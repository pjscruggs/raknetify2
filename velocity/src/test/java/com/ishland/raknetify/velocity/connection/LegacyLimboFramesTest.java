/*
 * This file is a part of the Velocity implementation of the Raknetify
 * project, licensed under GPLv3.
 *
 * Copyright (c) 2022-2025 ishland
 * Copyright (c) 2026 pjscruggs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ishland.raknetify.velocity.connection;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/** Exercises batched packet framing and ownership without loading any proxy plugin. */
class LegacyLimboFramesTest {
    /**
     * A precomputed cache entry stays reusable for another player after both packet bodies are
     * released.
     */
    @Test
    void preservesCachedBatch() {
        ByteBuf cached = Unpooled.buffer().writeBytes(new byte[] {2, 8, 9, 1, 4});
        try {
            for (int pass = 0; pass < 2; pass++) {
                List<Object> bodies = new ArrayList<>();
                LegacyLimboFrames.split(cached, bodies);
                assertEquals(2, bodies.size());
                ByteBuf first = (ByteBuf) bodies.get(0);
                ByteBuf second = (ByteBuf) bodies.get(1);
                try {
                    assertEquals(0x0809, first.readUnsignedShort());
                    assertEquals(4, second.readUnsignedByte());
                } finally {
                    first.release();
                    second.release();
                }
                assertEquals(0, cached.readerIndex());
                assertEquals(1, cached.refCnt());
            }
        } finally {
            cached.release();
        }
    }

    /** An incomplete second packet must not emit or retain the valid first one. */
    @Test
    void rejectsIncompleteBatchAtomically() {
        ByteBuf cached = Unpooled.wrappedBuffer(new byte[] {1, 0, 3, 7});
        try {
            List<Object> bodies = new ArrayList<>();
            assertThrows(
                    IllegalArgumentException.class, () -> LegacyLimboFrames.split(cached, bodies));
            assertTrue(bodies.isEmpty());
            assertEquals(1, cached.refCnt());
        } finally {
            cached.release();
        }
    }
}
