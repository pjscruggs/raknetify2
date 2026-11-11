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

import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Reads bundled Minecraft translations on clients, servers and standalone proxies. */
final class LanguageCatalog {
    /** Restricts classpath lookups to single locale filenames. */
    private static final Pattern CODE = Pattern.compile("[a-z0-9_]+");

    /** Uses only packaged, read-only resources. */
    private LanguageCatalog() {}

    /**
     * Loads a language over the English defaults used by Minecraft for missing keys.
     *
     * @param code Minecraft resource language identifier
     * @return complete translations for this lookup
     */
    static Map<String, String> load(final String code) {
        final Map<String, String> messages = new HashMap<>();
        readCatalog("en_us", messages);
        if (!"en_us".equals(code) && CODE.matcher(code).matches()) {
            readCatalog(code, messages);
        }
        return Map.copyOf(messages);
    }

    /**
     * Reads a UTF-8 catalog from the mod JAR without a game resource manager.
     *
     * @param language exact Minecraft locale identifier
     * @param messages destination for overriding existing English defaults
     */
    private static void readCatalog(final String language, final Map<String, String> messages) {
        try (var stream =
                LanguageCatalog.class.getResourceAsStream(
                        "/assets/raknetify/lang/" + language + ".json")) {
            if (stream != null) {
                try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    JsonParser.parseReader(reader)
                            .getAsJsonObject()
                            .entrySet()
                            .forEach(entry ->
                                    messages.put(entry.getKey(), entry.getValue().getAsString()));
                }
            }
        } catch (final IOException exception) {
            System.getLogger(LanguageCatalog.class.getName())
                    .log(
                            System.Logger.Level.WARNING,
                            "Unable to read Raknetify2 language " + language,
                            exception);
        }
    }
}
