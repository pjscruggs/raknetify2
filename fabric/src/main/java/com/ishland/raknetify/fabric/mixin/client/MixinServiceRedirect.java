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

import com.ishland.raknetify.common.connection.RakNetServiceLookup;

import net.minecraft.client.network.RedirectResolver;
import net.minecraft.client.network.ServerAddress;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

import javax.naming.directory.DirContext;

/** Looks up RakNet service records on Minecraft's resolver thread before vanilla SRV fallback. */
@Mixin(RedirectResolver.class)
public interface MixinServiceRedirect {
    /**
     * Handles prefixed names before Minecraft attempts its ordinary redirect.
     *
     * @param context native DNS context
     * @param address original address approved by Minecraft
     * @param result callback receiving the selected service address
     */
    @Inject(method = "method_36911", at = @At("HEAD"), cancellable = true)
    private static void raknetify$redirect(
            final DirContext context,
            final ServerAddress address,
            final CallbackInfoReturnable<Optional<ServerAddress>> result) {
        var decision = RakNetServiceLookup.redirect(address, ServerAddress::new);
        if (decision.handled()) {
            result.setReturnValue(decision.destination());
        }
    }
}
