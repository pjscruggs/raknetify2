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

import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.station.StationBlockEntity;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.class_2338;
import net.minecraft.class_238;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Operates a Create station and train on the server; neither observer receives fixture packets. */
public final class TrainDriver implements ModInitializer {
    /** Station next to the east-west test track. */
    private static final class_2338 STATION = new class_2338(1, 81, 1);

    /** {@inheritDoc} */
    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(TrainDriver::tick);
    }

    /**
     * Runs a fixture action on the server's tick thread.
     *
     * @param server dedicated server with the unmodified Create jar installed
     * @throws IllegalStateException when the fixture cannot consume its command
     */
    private static void tick(final MinecraftServer server) {
        Path input = Path.of("train-action");
        if (!Files.exists(input)) {
            return;
        }
        try {
            String action = Files.readString(input).trim();
            Files.delete(input);
            var station = (StationBlockEntity) server.method_30002().method_8321(STATION);
            switch (action) {
                case "stage" -> stage(server, station);
                case "assemble" -> station.assemble(UUID.randomUUID());
                case "move", "stop" -> drive(server, "move".equals(action));
                default -> throw new IllegalStateException(action);
            }
            System.out.println("TRAIN ACTION " + action);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * Places and glues a bogey and controls at the assembly position chosen by Create.
     *
     * @param server world containing the station
     * @param station station tracking the test rail
     * @throws IllegalStateException when the test track cannot enter assembly mode
     */
    private static void stage(final MinecraftServer server, final StationBlockEntity station) {
        if (!station.enterAssemblyMode(null)) {
            throw new IllegalStateException("Station refused assembly");
        }
        var direction = station.getAssemblyDirection();
        var bogey = station.edgePoint.getGlobalPosition().method_10093(direction).method_10084();
        var controls = bogey.method_10084();
        var commands = server.method_3734();
        var source = server.method_3739();
        commands.method_44252(source, "setblock " + xyz(bogey) + " create:small_bogey[axis=x]");
        commands.method_44252(
                source,
                "setblock "
                        + xyz(controls)
                        + " create:controls[facing="
                        + direction.method_10151()
                        + "]");
        var world = server.method_30002();
        world.method_8649(new SuperGlueEntity(world, SuperGlueEntity.span(bogey, controls)));
    }

    /**
     * Formats a position for a vanilla block command.
     *
     * @param pos block coordinate
     * @return space-separated integer coordinates
     */
    private static String xyz(final class_2338 pos) {
        return pos.method_10263() + " " + pos.method_10264() + " " + pos.method_10260();
    }

    /**
     * Lets Create simulate movement or stop and reports the authoritative carriage identity.
     *
     * @param server world containing the assembled train
     * @param moving whether to leave the station
     */
    private static void drive(final MinecraftServer server, final boolean moving) {
        var car =
                server.method_30002()
                        .method_8390(
                                CarriageContraptionEntity.class,
                                new class_238(-100, 65, -30, 100, 110, 30),
                                entity -> true)
                        .get(0);
        var train = car.getCarriage().train;
        if (moving) {
            train.leaveStation();
        }
        train.speed = moving ? 0.24 : 0;
        train.targetSpeed = train.speed;
        System.out.println(
                "SERVER TRAIN " + car.trainId + " " + car.method_23317() + " " + train.derailed);
    }
}
