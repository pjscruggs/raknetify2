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

package com.ishland.raknetify.legacy;

import com.ishland.raknetify.common.util.PrefixUtil;
import com.ishland.raknetify.common.util.ThreadLocalUtil;

import java.util.function.Supplier;

/** Scopes the native server-list probe to the transport chosen for the saved server. */
public final class StatusTransport {
    /** Prevents construction of the status probe scope. */
    private StatusTransport() {}

    /**
     * Restores both transport flags even if opening the socket fails on a pooled pinger thread.
     *
     * @param input saved server address with its original prefix
     * @param open original Minecraft connection factory
     * @param <T> native connection type
     * @return connection to the service approved by Minecraft
     */
    public static <T> T open(final String input, final Supplier<T> open) {
        PrefixUtil.Info info = PrefixUtil.getInfo(input);
        boolean previous = ThreadLocalUtil.isInitializingRaknet();
        boolean previousMtu = ThreadLocalUtil.isInitializingRaknetLargeMTU();
        try {
            ThreadLocalUtil.setInitializingRaknet(info.useRakNet());
            ThreadLocalUtil.setInitializingRaknetLargeMTU(info.largeMTU());
            return open.get();
        } finally {
            ThreadLocalUtil.setInitializingRaknet(previous);
            ThreadLocalUtil.setInitializingRaknetLargeMTU(previousMtu);
        }
    }
}
