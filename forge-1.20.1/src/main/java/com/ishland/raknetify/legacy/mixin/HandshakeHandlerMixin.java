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

import net.minecraftforge.network.HandshakeHandler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.List;

/** Protects Forge login reply tracking across the server tick and the network event loop. */
@Mixin(value = HandshakeHandler.class, remap = false)
public abstract class HandshakeHandlerMixin {
    /**
     * Login message indexes registered by the tick thread and acknowledged by the network thread.
     */
    @Shadow private List<Integer> sentMessages;

    /**
     * Shares one lock for registration, acknowledgement removal and the final completion check.
     *
     * @param ci constructor callback before Forge publishes the handler to its channel
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void synchronizeLoginReplies(final CallbackInfo ci) {
        sentMessages = Collections.synchronizedList(sentMessages);
    }
}
