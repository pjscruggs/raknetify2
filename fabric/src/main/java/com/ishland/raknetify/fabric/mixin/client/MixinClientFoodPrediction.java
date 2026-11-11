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

import com.ishland.raknetify.common.connection.CompatibilityPolicy;
import com.ishland.raknetify.fabric.common.util.MultiVersionUtil;
import com.ishland.raknetify.fabric.mixin.access.IClientConnection;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.network.ClientConnection;

import network.ycc.raknet.RakNet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Defers hunger prediction to the server for the active RakNet player. */
@Mixin(HungerManager.class)
public abstract class MixinClientFoodPrediction {
    /**
     * Suppresses a local consumption increment while its authoritative update can travel on an
     * independent RakNet ordering channel. Server processing and unrelated food data pass through.
     *
     * @param ci consumption callback cancelled only for the connected RakNet player
     */
    @Inject(
            method = {"add(IF)V", "eat(Lnet/minecraft/component/type/FoodComponent;)V"},
            at = @At("HEAD"),
            cancellable = true)
    private void deferFoodPrediction(final CallbackInfo ci) {
        final var client = MinecraftClient.getInstance();
        if (!client.isOnThread()
                || client.player == null
                || !com.google.common.base.Equivalence.identity()
                        .equivalent(client.player.getHungerManager(), this)) {
            return;
        }
        final var listener = client.getNetworkHandler();
        if (listener == null) {
            return;
        }
        final ClientConnection connection =
                (ClientConnection)
                        MultiVersionUtil.requireCompatible(
                                        MultiVersionUtil.ClientPlayNetworkHandler$connection,
                                        "ClientPlayNetworkHandler$connection")
                                .get(listener);
        final var channel = ((IClientConnection) connection).getChannel();
        if (channel != null
                && channel.config() instanceof RakNet.Config
                && !CompatibilityPolicy.isOrdered(channel)) {
            ci.cancel();
        }
    }
}
