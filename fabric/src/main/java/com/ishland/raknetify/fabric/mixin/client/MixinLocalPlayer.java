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

package com.ishland.raknetify.fabric.mixin.client;

import com.ishland.raknetify.fabric.common.util.MultiVersionUtil;
import com.ishland.raknetify.fabric.mixin.access.IClientConnection;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.world.chunk.EmptyChunk;

import network.ycc.raknet.RakNet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps local physics from running ahead of terrain delivered on the RakNet chunk lane. */
@Mixin(ClientPlayerEntity.class)
abstract class MixinLocalPlayer {
    /**
     * Skips local simulation while the current chunk lacks collision data.
     *
     * @param callback Minecraft player-tick callback
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void waitForCollisionChunk(final CallbackInfo callback) {
        var player = (ClientPlayerEntity) (Object) this;
        var world = MinecraftClient.getInstance().world;
        var connection =
                (IClientConnection)
                        MultiVersionUtil.requireCompatible(
                                        MultiVersionUtil.ClientPlayNetworkHandler$connection,
                                        "ClientPlayNetworkHandler$connection")
                                .get(player.networkHandler);
        var channel = connection.getChannel();
        if (world == null || channel == null || !(channel.config() instanceof RakNet.Config)) {
            return;
        }
        var pos = player.getChunkPos();
        var chunk = world.getChunkManager().getWorldChunk(pos.x, pos.z);
        if (chunk == null || chunk instanceof EmptyChunk) {
            callback.cancel();
        }
    }
}
