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

import com.ishland.raknetify.common.connection.RakNetServiceLookup;

import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.client.multiplayer.resolver.ServerRedirectHandler;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.reflect.InvocationTargetException;
import java.util.Optional;

/** Chooses the UDP service inside Minecraft's native host and IP block-list checks. */
@Mixin(value = ServerNameResolver.class, remap = false)
abstract class MixinServiceRedirect {
    /**
     * Resolves an optional UDP service before the ordinary Minecraft redirect.
     *
     * @param original native TCP service resolver
     * @param input address retaining the user's port selection
     * @return selected service or the normal Minecraft fallback
     * @throws IllegalStateException if the pinned Forge resolver cannot be invoked
     */
    @Group(name = "redirectService", min = 1, max = 1)
    @Redirect(
            method = {"m_171890_", "resolveAddress"},
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/multiplayer/resolver/ServerRedirectHandler;m_171901_(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Ljava/util/Optional;"),
            require = 0)
    private Optional<ServerAddress> selectProduction(
            final ServerRedirectHandler original, final ServerAddress input) {
        var decision = RakNetServiceLookup.redirect(input, ServerAddress::new);
        if (decision.handled()) {
            return decision.destination();
        }
        try {
            var method =
                    ObfuscationReflectionHelper.findMethod(
                            ServerRedirectHandler.class, "m_171901_", ServerAddress.class);
            return ((Optional<?>) method.invoke(original, input)).map(ServerAddress.class::cast);
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw new IllegalStateException("Cannot call Forge service resolver", failure);
        }
    }

    /**
     * Resolves an optional UDP service before the ordinary Minecraft redirect.
     *
     * @param original native TCP service resolver
     * @param input address retaining the user's port selection
     * @return selected service or the normal Minecraft fallback
     */
    @Group(name = "redirectService", min = 1, max = 1)
    @Redirect(
            method = {"m_171890_", "resolveAddress"},
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/multiplayer/resolver/ServerRedirectHandler;lookupRedirect(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Ljava/util/Optional;"),
            require = 0)
    private Optional<ServerAddress> selectNamed(
            final ServerRedirectHandler original, final ServerAddress input) {
        var decision = RakNetServiceLookup.redirect(input, ServerAddress::new);
        return decision.handled() ? decision.destination() : original.lookupRedirect(input);
    }
}
