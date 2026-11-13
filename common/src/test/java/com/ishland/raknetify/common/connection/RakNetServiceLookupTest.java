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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.SRVRecord;

/** Checks SRV preference independently of the resolver used by a player's operating system. */
class RakNetServiceLookupTest {
    /**
     * Constructs a deterministic local service endpoint with a chosen priority and weight.
     *
     * @param priority relative priority
     * @param weight weight within the same priority
     * @return record that needs no network access
     */
    private static SRVRecord entry(final int priority, final int weight) {
        return new SRVRecord(Name.root, DClass.IN, 30, priority, weight, 25565, Name.root);
    }

    /** A high weight must never promote a lower-priority alternative over the preferred service. */
    @Test
    void priorityOverridesWeight() {
        SRVRecord preferred = entry(0, 0);
        assertSame(
                preferred, RakNetServiceLookup.select(new Record[] {entry(10, 65535), preferred}));
    }

    /** Missing records allow vanilla fallback, and a single zero-weight service remains usable. */
    @Test
    void absentAndZeroWeight() {
        assertNull(RakNetServiceLookup.select(new Record[0]));
        SRVRecord zero = entry(1, 0);
        assertSame(zero, RakNetServiceLookup.select(new Record[] {zero}));
    }
}
