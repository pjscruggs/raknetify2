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

package com.ishland.raknetify.common.util;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.channel.embedded.EmbeddedChannel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/** Covers resource packs, bare-client fallback, and live changes to the selected language. */
class HudLanguageTest {

    /** A bare client loads the packaged translation with arguments in their original positions. */
    @Test
    void readsBundledCatalogWithoutGameResourceIntegration() {
        final HudLanguage language = new HudLanguage((key, arguments) -> key);
        language.select("en_us");
        assertEquals(
                "Active · MTU 1400 · RTT 2.50 ± 0.25 ms",
                language.translate("latency", 1400, "2.50", "0.25"));
        language.select("unknown_custom");
        assertEquals("Inactive", language.translate("inactive"));
    }

    /** Unicode resources survive language switches in clients that load catalogs from the JAR. */
    @Test
    void switchesPackagedLanguagesWithoutLosingTextOrArguments() {
        final HudLanguage language = new HudLanguage((key, arguments) -> key);
        language.select("zh_cn");
        assertEquals("客户端", language.translate("client"));
        language.select("ar_sa");
        assertEquals("العميل", language.translate("client"));
        language.select("got_de");
        final String gothic = language.translate("traffic", "111", "222");
        assertTrue(
                gothic.codePoints()
                        .anyMatch(point ->
                                Character.UnicodeScript.of(point)
                                        == Character.UnicodeScript.GOTHIC));
        assertTrue(gothic.contains("111") && gothic.contains("222"));
        language.select("en_us");
        assertEquals("Client", language.translate("client"));
    }

    /** Resource packs can reorder variables and reloading a pack updates an already open HUD. */
    @Test
    void prefersLiveGameTranslationsAndAllowsReorderedArguments() {
        final AtomicReference<String> template = new AtomicReference<>("%3$s / %1$s / %2$s");
        final HudLanguage language =
                new HudLanguage(
                        (key, arguments) -> String.format(Locale.ROOT, template.get(), arguments));
        language.select("en_us");
        assertEquals("C / A / B", language.translate("latency", "A", "B", "C"));
        template.set("%2$s / %3$s / %1$s");
        assertEquals("B / C / A", language.translate("latency", "A", "B", "C"));
    }

    /** Precision and numeric punctuation track the client's selected language at runtime. */
    @Test
    void refreshesNumericFormatsWhenThePlayerChangesLanguages() {
        final HudLanguage language = new HudLanguage((key, arguments) -> key);
        language.select("en_us");
        assertEquals("1.25", language.decimal(1.25));
        assertEquals("0.0625%", language.ratio(0.000625, true));
        assertEquals("12.50%", language.ratio(0.125, false));
        language.select("de_de");
        assertEquals("1,25", language.decimal(1.25));
    }

    /** Both disconnected and vanilla TCP connections emit a single localized inactive line. */
    @Test
    void usesTheSameLocalizedInactiveMarkerAcrossConnectionTypes() {
        final HudLanguage language = new HudLanguage((key, arguments) -> key);
        language.select("en_us");
        final List<String> lines = new ArrayList<>();
        DebugHudFormatter.append(lines::add, null, language);
        final EmbeddedChannel tcp = new EmbeddedChannel();
        try {
            DebugHudFormatter.append(lines::add, tcp, language);
        } finally {
            tcp.finishAndReleaseAll();
        }
        assertEquals(List.of("[Raknetify2] Inactive", "[Raknetify2] Inactive"), lines);
    }
}
