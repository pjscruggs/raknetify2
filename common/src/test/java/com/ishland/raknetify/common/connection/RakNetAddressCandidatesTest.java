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

package com.ishland.raknetify.common.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.net.InetAddresses;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

/** Checks fallback ordering and destination restrictions without consulting external DNS. */
class RakNetAddressCandidatesTest {
    /**
     * The other IP family gets the first fallback slot, and every endpoint retains the SRV-selected
     * port.
     */
    @Test
    void alternateFamilyPrecedesMoreAddressesOfTheFirstFamily() {
        InetAddress first = InetAddresses.forString("127.0.0.1");
        InetAddress second = InetAddresses.forString("127.0.0.2");
        InetAddress ipv6 = InetAddresses.forString("::1");
        InetSocketAddress original = new InetSocketAddress(first, 25570);
        List<InetSocketAddress> result =
                RakNetAddressCandidates.order(
                        original, new InetAddress[] {first, second, ipv6, first}, address -> true);
        assertEquals(
                List.of(
                        original,
                        new InetSocketAddress(ipv6, 25570),
                        new InetSocketAddress(second, 25570)),
                result);
        assertThrows(UnsupportedOperationException.class, () -> result.add(original));
    }

    /**
     * An alternate denied by Minecraft's policy never reaches the transport's probe candidate list.
     */
    @Test
    void blockedAlternatesCannotBypassPlatformPolicy() {
        InetSocketAddress original =
                new InetSocketAddress(InetAddresses.forString("127.0.0.1"), 25565);
        InetAddress alternate = InetAddresses.forString("::1");
        assertEquals(
                List.of(original),
                RakNetAddressCandidates.order(
                        original, new InetAddress[] {alternate}, address -> false));
    }

    /**
     * Literal targets retain the explicit endpoint and never invoke an alternate-address policy
     * callback.
     */
    @Test
    void literalAddressDoesNotExpandIntoOtherDestinations() {
        InetSocketAddress original =
                new InetSocketAddress(InetAddresses.forString("127.0.0.1"), 25570);
        assertEquals(
                List.of(original),
                RakNetAddressCandidates.resolve(
                        original,
                        address -> {
                            throw new AssertionError(
                                    "A numeric endpoint must not trigger DNS expansion");
                        }));
    }
}
