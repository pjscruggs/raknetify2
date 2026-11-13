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

package com.ishland.raknetify.test.create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;

import io.netty.channel.Channel;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.class_2535;
import net.minecraft.class_310;

/** Samples the actual client-side carriage, including the state Create requires for rendering. */
public final class TrainObserver implements ClientModInitializer {
    /** Sampling interval in client ticks. */
    private int ticks;

    /** {@inheritDoc} */
    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::sample);
    }

    /**
     * Emits the same observations on the TCP control and RakNet client.
     *
     * @param client running game client
     */
    private void sample(final class_310 client) {
        if (client.field_1687 == null || client.field_1724 == null) {
            return;
        }
        if (++ticks == 1) {
            reportTransport(client);
        }
        if (ticks % 10 != 0) {
            return;
        }
        for (var entity : client.field_1687.method_18112()) {
            reportEntity(entity);
        }
    }

    /**
     * Records the observed assembled blocks and whether the train can be rendered.
     *
     * @param entity entity loaded in the client world
     */
    private static void reportEntity(final net.minecraft.class_1297 entity) {
        if (entity instanceof CarriageContraptionEntity train) {
            var carriage = train.getCarriage();
            int blocks =
                    train.getContraption() == null ? 0 : train.getContraption().getBlocks().size();
            System.out.println(
                    "TRAIN "
                            + train.trainId
                            + " "
                            + train.method_23317()
                            + " "
                            + train.validForRender
                            + " "
                            + (carriage != null && !carriage.train.derailed)
                            + " "
                            + blocks);
        } else if (entity instanceof AbstractContraptionEntity bearing
                && bearing.getContraption() != null) {
            System.out.println(
                    "BEARING "
                            + bearing.method_5628()
                            + " "
                            + bearing.getContraption().getBlocks().size());
        }
    }

    /**
     * Proves the prefixed client did not silently use TCP.
     *
     * @param client connected Minecraft client
     * @throws IllegalStateException if this fixture no longer matches the pinned game
     */
    private static void reportTransport(final class_310 client) {
        try {
            var field = class_2535.class.getDeclaredField("field_11651");
            field.setAccessible(true);
            Channel channel = (Channel) field.get(client.method_1562().method_48296());
            System.out.println("TRANSPORT " + channel.config().getClass().getName());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
