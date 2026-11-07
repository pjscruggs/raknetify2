/*
 * This file is a part of the Raknetify project, licensed under MIT.
 *
 * Copyright (c) 2022-2025 ishland
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

package com.ishland.raknetify.common.connection;

import com.google.common.net.InetAddresses;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Expands a resolved hostname without changing its SRV-selected port or bypassing address policy.
 */
public final class RakNetAddressCandidates {
    /** Bounds handshake fan-out even when a hostname has a very large address set. */
    private static final int MAX_CANDIDATES = 8;

    /** Prevents construction of the client DNS adapter. */
    private RakNetAddressCandidates() {}

    /**
     * Resolves alternates on the caller's connection thread, before a Netty event loop owns the
     * socket. Literal addresses keep their exact target. A failed second DNS lookup preserves the
     * already resolved endpoint instead of losing a connection that vanilla could still establish.
     *
     * @param original endpoint already accepted by Minecraft's hostname and SRV resolution
     * @param allowed platform block-list predicate applied to every additional IP address
     * @return immutable, bounded candidates with the original endpoint first
     */
    public static List<InetSocketAddress> resolve(
            final InetSocketAddress original, final Predicate<InetSocketAddress> allowed) {
        if (InetAddresses.isInetAddress(original.getHostString())) {
            return List.of(original);
        }
        try {
            return order(original, InetAddress.getAllByName(original.getHostString()), allowed);
        } catch (UnknownHostException unavailable) {
            return List.of(original);
        }
    }

    /**
     * Gives the other address family the first fallback slot, then retains resolver order within
     * each family. Duplicate and disallowed endpoints cannot consume the bounded candidate budget.
     *
     * @param original previously resolved and authorized endpoint
     * @param resolved DNS result supplied independently so policy and ordering are testable
     * @param allowed policy for additional destinations, including their original service port
     * @return immutable candidates suitable for one offline handshake race
     */
    static List<InetSocketAddress> order(
            final InetSocketAddress original,
            final InetAddress[] resolved,
            final Predicate<InetSocketAddress> allowed) {
        List<InetSocketAddress> candidates = new ArrayList<>();
        candidates.add(original);
        append(candidates, original, resolved, allowed, false);
        append(candidates, original, resolved, allowed, true);
        return List.copyOf(candidates);
    }

    /**
     * Adds one address family while retaining the service port selected by the original resolver.
     *
     * @param candidates mutable list owned solely by the current DNS expansion
     * @param original endpoint defining the original address family and service port
     * @param resolved DNS records to inspect in their received order
     * @param allowed application destination policy applied before adding an endpoint
     * @param sameFamily whether this pass appends the original or alternate IP family
     */
    private static void append(
            final List<InetSocketAddress> candidates,
            final InetSocketAddress original,
            final InetAddress[] resolved,
            final Predicate<InetSocketAddress> allowed,
            final boolean sameFamily) {
        for (InetAddress address : resolved) {
            if (candidates.size() == MAX_CANDIDATES) {
                return;
            }
            if (address.getClass().equals(original.getAddress().getClass()) != sameFamily) {
                continue;
            }
            InetSocketAddress endpoint = new InetSocketAddress(address, original.getPort());
            if (!candidates.contains(endpoint) && allowed.test(endpoint)) {
                candidates.add(endpoint);
            }
        }
    }
}
