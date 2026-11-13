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

import com.google.common.net.HostAndPort;
import com.google.common.net.InetAddresses;
import com.ishland.raknetify.common.util.PrefixUtil;

import org.jspecify.annotations.Nullable;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.SRVRecord;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.net.IDN;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;

/**
 * Looks up the optional RakNet UDP service inside Minecraft's normal redirect and block-list
 * checks.
 */
public final class RakNetServiceLookup {
    /** Prevents construction of this DNS adapter. */
    private RakNetServiceLookup() {}

    /**
     * Retains vanilla service discovery on an ordinary address or when the UDP record is absent.
     *
     * @param address native address carrying transport metadata
     * @param destination constructs a native address with the advertised host and port
     * @param <T> native address type
     * @return an explicit redirect decision or the instruction to use vanilla DNS
     */
    public static <T> Decision<T> redirect(
            final T address, final BiFunction<String, Integer, T> destination) {
        PrefixUtil.Info info = ((RakNetServiceAddress) address).raknetify$selection();
        if (info == null) {
            return new Decision<>(false, Optional.empty());
        }
        // Snapshot query properties follow the host and do not participate in DNS.
        String input = info.stripped().split("\\?", 2)[0];
        String hostPort = input.substring(input.indexOf('@') + 1);
        HostAndPort endpoint;
        try {
            endpoint = HostAndPort.fromString(hostPort);
        } catch (IllegalArgumentException invalid) {
            return new Decision<>(false, Optional.empty());
        }
        if (endpoint.hasPort() || InetAddresses.isInetAddress(endpoint.getHost())) {
            return new Decision<>(true, Optional.empty());
        }
        try {
            String host = IDN.toASCII(endpoint.getHost(), IDN.USE_STD3_ASCII_RULES);
            Name name = Name.fromString("_raknetify._udp." + host, Name.root);
            Record[] records = new Lookup(name, Type.SRV).run();
            if (records != null) {
                SRVRecord entry = select(records);
                if (entry != null) {
                    return new Decision<>(
                            true,
                            Optional.of(
                                    destination.apply(
                                            entry.getTarget().equals(Name.root)
                                                    ? "unavailable.invalid."
                                                    : entry.getTarget().toString(),
                                            entry.getPort())));
                }
            }
        } catch (TextParseException | IllegalArgumentException invalid) {
            // Continue through the native resolver when the name cannot form a service record.
        }
        return new Decision<>(false, Optional.empty());
    }

    /**
     * Distinguishes an absent UDP record from an explicit port that must bypass all SRV lookup.
     *
     * @param handled whether Minecraft should skip its normal TCP service lookup
     * @param destination chosen service, or empty to use the original port
     * @param <T> native Minecraft destination
     */
    public record Decision<T>(boolean handled, Optional<T> destination) {}

    /**
     * Draws a target by weight among the records sharing the lowest advertised priority.
     *
     * @param records SRV records from the DNS provider
     * @return selected record or null when there are no usable records
     * @throws AssertionError if the cumulative weights do not cover the draw
     */
    static @Nullable SRVRecord select(final Record[] records) {
        List<SRVRecord> entries = new ArrayList<>();
        for (Record record : records) {
            if (record instanceof SRVRecord srv) {
                entries.add(srv);
            }
        }
        if (entries.isEmpty()) {
            return null;
        }
        int priority = entries.stream().mapToInt(SRVRecord::getPriority).min().orElseThrow();
        entries.removeIf(entry -> entry.getPriority() != priority);
        java.util.Collections.shuffle(entries, ThreadLocalRandom.current());
        entries.sort(Comparator.comparingInt(SRVRecord::getWeight));
        long sum = entries.stream().mapToLong(SRVRecord::getWeight).sum();
        long draw = ThreadLocalRandom.current().nextLong(sum + 1);
        for (SRVRecord entry : entries) {
            draw -= entry.getWeight();
            if (draw <= 0) {
                return entry;
            }
        }
        throw new AssertionError("SRV weights did not cover the selected value");
    }
}
