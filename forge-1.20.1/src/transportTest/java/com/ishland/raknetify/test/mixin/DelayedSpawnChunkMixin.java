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

package com.ishland.raknetify.test.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.level.block.Blocks;

import network.ycc.raknet.RakNet;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Holds the spawn column while a native client handles the initial teleport and nearby terrain. */
@Mixin(value = ClientPacketListener.class, remap = false)
abstract class DelayedSpawnChunkMixin {
    /** Opts a single real client run into the artificial chunk delay. */
    @Unique private static final boolean DELAY = Boolean.getBoolean("raknetify.test.delayedSpawn");

    /** Y on top of the server fixture's stone platform in chunk (0, 0). */
    @Unique private static final double SPAWN_Y = 201.0;

    /** Spawn packet retained only on the game thread before it reaches the real chunk cache. */
    @Unique private @Nullable ClientboundLevelChunkWithLightPacket heldChunk;

    /** Confirms the initial authoritative position was applied before measuring movement. */
    @Unique private boolean teleported;

    /** Counts the client ticks for which the current chunk was deliberately absent. */
    @Unique private int ticksWithoutTerrain;

    /** Allows the original chunk to be replayed and every later chunk to pass through. */
    @Unique private boolean released;

    /** Stops assertions after the client has collided with the installed platform. */
    @Unique private boolean landed;

    /**
     * Captures the initial teleport after Minecraft has moved the local player and acknowledged it.
     *
     * @param packet received authoritative position
     * @param callback injection callback for the position handler
     * @throws IllegalStateException if the server does not place the RakNet client on the platform
     */
    @Inject(
            method = {"m_5682_", "handleMovePlayer"},
            at = @At("RETURN"))
    private void observeInitialPosition(
            final ClientboundPlayerPositionPacket packet, final CallbackInfo callback) {
        if (!DELAY || teleported) {
            return;
        }
        var player = Minecraft.getInstance().player;
        if (player == null
                || !(player.connection.getConnection().channel().config() instanceof RakNet.Config)
                || player.chunkPosition().x != 0
                || player.chunkPosition().z != 0
                || player.getY() != SPAWN_Y) {
            throw new IllegalStateException("Fixture did not apply the expected RakNet spawn");
        }
        teleported = true;
        System.out.println("Delayed spawn test applied RakNet position at Y=" + player.getY());
    }

    /**
     * Delays only the target column and preserves Minecraft's normal game-thread handoff.
     *
     * @param packet inbound chunk and light data
     * @param callback injection callback for the chunk handler
     * @throws IllegalStateException if the server sends the target column twice during the hold
     */
    @Inject(
            method = {"m_183388_", "handleLevelChunkWithLight"},
            at = @At("HEAD"),
            cancellable = true)
    private void holdSpawnChunk(
            final ClientboundLevelChunkWithLightPacket packet, final CallbackInfo callback) {
        if (DELAY
                && !released
                && packet.getX() == 0
                && packet.getZ() == 0
                && Minecraft.getInstance().isSameThread()) {
            if (heldChunk != null) {
                throw new IllegalStateException("Duplicate delayed spawn chunk in fixture");
            }
            heldChunk = packet;
            callback.cancel();
        }
    }

    /**
     * Verifies the player does not drift with the chunk absent and lands once the hold ends.
     *
     * @param callback injection callback for the connection tick
     * @throws IllegalStateException if the local player is lost while its terrain is held
     */
    @Inject(
            method = {"m_9933_", "tick"},
            at = @At("TAIL"))
    private void checkSpawn(final CallbackInfo callback) {
        if (!teleported || landed) {
            return;
        }
        var player = Minecraft.getInstance().player;
        if (player == null) {
            throw new IllegalStateException("Fixture lost the local player during spawn");
        }
        if (released) {
            if (player.onGround() && player.getY() == SPAWN_Y) {
                landed = true;
                System.out.println("PASS delayed RakNet spawn terrain");
            }
        } else {
            checkHeldPosition(player);
        }
    }

    /**
     * Keeps the column absent for forty ticks and checks the authoritative height throughout.
     *
     * @param player real Minecraft client under test
     * @throws IllegalStateException if the player falls or the terrain hold does not work
     */
    @Unique
    private void checkHeldPosition(final LocalPlayer player) {
        if (player.getY() != SPAWN_Y) {
            throw new IllegalStateException(
                    "Player fell before spawn terrain arrived: Y=" + player.getY());
        }
        if (player.level().getChunkSource().getChunkNow(0, 0) != null) {
            throw new IllegalStateException("Fixture failed to hold the spawn terrain");
        }
        var packet = heldChunk;
        if (packet != null && ++ticksWithoutTerrain == 40) {
            released = true;
            heldChunk = null;
            ((ClientPacketListener) (Object) this).handleLevelChunkWithLight(packet);
            if (!player.level().getBlockState(player.blockPosition().below()).is(Blocks.STONE)) {
                throw new IllegalStateException(
                        "The delayed chunk did not install the spawn platform");
            }
            System.out.println("Delayed spawn test installed terrain after 40 client ticks");
        }
    }
}
