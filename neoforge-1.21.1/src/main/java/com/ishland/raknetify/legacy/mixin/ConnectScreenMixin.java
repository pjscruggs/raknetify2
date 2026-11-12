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

import com.ishland.raknetify.common.util.PrefixUtil;
import com.ishland.raknetify.common.util.ThreadLocalUtil;
import com.ishland.raknetify.legacy.TransportAddress;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Carries the selected transport onto the single-use Forge connector thread. */
@Mixin(targets = "net.minecraft.client.gui.screens.ConnectScreen$1", remap = false)
abstract class ConnectScreenMixin {
    /** Choice captured on the caller thread before DNS resolution. */
    @Unique private PrefixUtil.@Nullable Info raknetify$info;

    /**
     * Captures the parsed server address from the connection thread constructor.
     *
     * @param screen multiplayer connection screen that owns this one-shot worker
     * @param threadName thread name selected by Minecraft
     * @param address resolved or parsed destination for the pending connection
     * @param client client associated with this connection attempt
     * @param data server-list entry supplied by Minecraft
     * @param transfer server transfer state supplied by Minecraft
     * @param ci injection callback for the original Minecraft operation
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void remember(
            final ConnectScreen screen,
            final String threadName,
            final ServerAddress address,
            final Minecraft client,
            final ServerData data,
            final TransferState transfer,
            final CallbackInfo ci) {
        raknetify$info = ((TransportAddress) (Object) address).raknetify$selection();
    }

    /**
     * Selects UDP for the lifetime of this individual connection attempt.
     *
     * @param ci injection callback for the original Minecraft operation
     */
    @Inject(method = "run", at = @At("HEAD"))
    private void select(final CallbackInfo ci) {
        if (raknetify$info != null) {
            ThreadLocalUtil.setInitializingRaknet(true);
            ThreadLocalUtil.setInitializingRaknetLargeMTU(raknetify$info.largeMTU());
        }
    }
}
