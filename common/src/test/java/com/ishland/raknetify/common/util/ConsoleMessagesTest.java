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

import org.junit.jupiter.api.Test;

import java.util.Locale;

/** Exercises operator language selection without loading a Minecraft client. */
class ConsoleMessagesTest {

    /** System locale selection keeps script and region preferences and supports regionless JVMs. */
    @Test
    void selectsMinecraftLocales() {
        assertEquals("en_us", ConsoleMessages.defaultCode(Locale.ENGLISH));
        assertEquals("fr_ca", ConsoleMessages.defaultCode(Locale.CANADA_FRENCH));
        assertEquals("ja_jp", ConsoleMessages.defaultCode(Locale.JAPANESE));
        assertEquals("zh_tw", ConsoleMessages.defaultCode(Locale.forLanguageTag("zh-Hant")));
        assertEquals("zh_tw", ConsoleMessages.defaultCode(Locale.forLanguageTag("zh-Hant-CN")));
        assertEquals("zh_hk", ConsoleMessages.defaultCode(Locale.forLanguageTag("zh-Hant-HK")));
        assertEquals("zh_cn", ConsoleMessages.defaultCode(Locale.forLanguageTag("zh-Hans-TW")));
        assertEquals("zlm_arab", ConsoleMessages.defaultCode(Locale.forLanguageTag("ms-Arab-MY")));
        assertEquals("be_latn", ConsoleMessages.defaultCode(Locale.forLanguageTag("be-Latn")));
        assertEquals("no_no", ConsoleMessages.defaultCode(Locale.forLanguageTag("nb-NO")));
        assertEquals("en_us", ConsoleMessages.defaultCode(Locale.forLanguageTag("xx-ZZ")));
    }

    /**
     * Client transport errors respect resource-pack overrides and language changes before login.
     */
    @Test
    void localizesClientErrorsIndependentlyOfTheConsole() {
        var channel = new io.netty.channel.embedded.EmbeddedChannel();
        var selected = new java.util.concurrent.atomic.AtomicReference<>("ja_jp");
        try {
            ConsoleMessages.bindPlayer(channel, selected::get, (key, args) -> key);
            assertTrue(ConsoleMessages.transport(channel, "frame_limit", 42).contains("バイト"));
            selected.set("fr_fr");
            assertTrue(ConsoleMessages.transport(channel, "frame_limit", 42).contains("42 octets"));
            ConsoleMessages.bindPlayer(channel, selected::get, (key, args) -> "pack " + args[0]);
            assertEquals("pack 42", ConsoleMessages.transport(channel, "frame_limit", 42));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** A literal address containing format syntax remains data in a Unicode operator message. */
    @Test
    void formatsPackagedOperatorText() {
        var messages = LanguageCatalog.load("ja_jp");
        var text =
                String.format(
                        Locale.ROOT,
                        messages.get("raknetify.console.diagnostics_denied"),
                        "[::1]%s");
        assertTrue(text.contains("[::1]%s"));
        assertTrue(text.contains("統計"));
    }
}
