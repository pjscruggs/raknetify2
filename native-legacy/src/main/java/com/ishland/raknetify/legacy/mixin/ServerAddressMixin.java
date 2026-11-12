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

import com.google.common.net.HostAndPort;
import com.ishland.raknetify.common.util.PrefixUtil;
import com.ishland.raknetify.legacy.TransportAddress;

import net.minecraft.client.multiplayer.resolver.ServerAddress;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps transport selection alongside a normal address that Minecraft can resolve and validate. */
@Mixin(value = ServerAddress.class, remap = false)
abstract class ServerAddressMixin implements TransportAddress {
    /** Optional selection for this address instance. */
    @Unique private PrefixUtil.@Nullable Info raknetify$info;

    /**
     * Validates the hostname and port without the transport prefix.
     *
     * @param input multiplayer address entered by the player
     * @return address that the native parser should validate
     */
    @ModifyVariable(
            method = {"m_171867_", "isValidAddress"},
            at = @At("HEAD"),
            argsOnly = true)
    private static @Nullable String strip(final @Nullable String input) {
        return input == null ? null : PrefixUtil.getInfo(input).stripped();
    }

    /**
     * Parses IPv4, hostnames, SRV targets and bracketed IPv6 with the game's host parser.
     *
     * @param input multiplayer address entered by the player
     * @param result callback that can replace the parsed server address
     */
    @Inject(
            method = {"m_171864_", "parseString"},
            at = @At("HEAD"),
            cancellable = true)
    private static void parse(
            final String input, final CallbackInfoReturnable<ServerAddress> result) {
        if (input == null) {
            return;
        }
        PrefixUtil.Info info = PrefixUtil.getInfo(input);
        if (!info.useRakNet()) {
            return;
        }
        try {
            HostAndPort host = HostAndPort.fromString(info.stripped()).withDefaultPort(25565);
            if (!host.getHost().isEmpty()) {
                ServerAddress address = new ServerAddress(host.getHost(), host.getPort());
                ((TransportAddress) (Object) address).raknetify$selection(info);
                result.setReturnValue(address);
            }
        } catch (IllegalArgumentException invalid) {
            // Vanilla handles invalid addresses through its normal fallback.
        }
    }

    /** {@inheritDoc} */
    @Override
    public PrefixUtil.@Nullable Info raknetify$selection() {
        return raknetify$info;
    }

    /** {@inheritDoc} */
    @Override
    public void raknetify$selection(final PrefixUtil.Info selection) {
        raknetify$info = selection;
    }
}
