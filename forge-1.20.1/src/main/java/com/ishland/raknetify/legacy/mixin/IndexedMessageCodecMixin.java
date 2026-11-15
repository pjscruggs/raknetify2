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

package com.ishland.raknetify.legacy.mixin;

import com.ishland.raknetify.common.connection.multichannel.ModPayloadOrdering;

import net.minecraftforge.network.NetworkInstance;
import net.minecraftforge.network.simple.IndexedMessageCodec;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Captures message registrations without requiring any optional mod to integrate with Raknetify2.
 */
@Mixin(value = IndexedMessageCodec.class, remap = false)
abstract class IndexedMessageCodecMixin {
    /** Forge's channel owner, absent only for its legacy standalone codec constructor. */
    @Shadow @Final private NetworkInstance networkInstance;

    /**
     * Binds the installed mod's discriminator to its audited packet class.
     *
     * @param discriminator installed message identifier
     * @param type packet class registered by the mod
     * @param encoder Forge serializer, preserved without invocation
     * @param decoder Forge deserializer, preserved without invocation
     * @param consumer Forge packet handler, preserved without invocation
     * @param direction permitted packet direction, preserved without alteration
     * @param result original registration result, preserved without cancellation
     */
    @Inject(method = "addCodecIndex", at = @At("HEAD"), remap = false)
    private void bindOrdering(
            final int discriminator,
            final Class<?> type,
            final BiConsumer<?, ?> encoder,
            final Function<?, ?> decoder,
            final BiConsumer<?, ?> consumer,
            final Optional<?> direction,
            final CallbackInfoReturnable<?> result) {
        if (networkInstance != null) {
            ModPayloadOrdering.register(
                    networkInstance.getChannelName().toString(), discriminator, type.getName());
        }
    }
}
