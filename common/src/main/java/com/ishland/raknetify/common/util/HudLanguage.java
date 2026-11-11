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

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/** Resolves HUD messages and numeric conventions in the language selected by the client. */
public final class HudLanguage {

    /** Limits packaged catalog lookups to Minecraft language codes. */
    private static final Pattern CODE = Pattern.compile("[a-z0-9_]+");

    /** Minecraft's translator includes any resource packs enabled by the player. */
    private final BiFunction<String, Object[], String> game;

    /** Current game language, including Minecraft-specific regional and novelty identifiers. */
    private String code = "";

    /** Java locale used for formatting the current language's numeric values. */
    private Locale locale = Locale.ROOT;

    /** Formatter for queued bytes in MiB and latency in milliseconds. */
    private NumberFormat decimals = numberFormat(Locale.ROOT, false, 2);

    /** Formatter for compression ratios. */
    private NumberFormat percent = numberFormat(Locale.ROOT, true, 2);

    /** Formatter for small retransmission ratios. */
    private NumberFormat precisePercent = numberFormat(Locale.ROOT, true, 4);

    /** Packaged fallback needed by clients without a mod resource-pack loader. */
    private @Nullable Map<String, String> catalog;

    /**
     * Binds this render-thread formatter to the game's live resource-pack lookup.
     *
     * @param game translation lookup accepting a key and its format arguments
     */
    public HudLanguage(final BiFunction<String, Object[], String> game) {
        this.game = game;
    }

    /**
     * Refreshes cached formats when the player switches language.
     *
     * @param selected exact locale identifier from the game options
     */
    public void select(final String selected) {
        if (!selected.equals(code)) {
            code = selected;
            locale = Locale.forLanguageTag(code.replace('_', '-'));
            decimals = numberFormat(locale, false, 2);
            percent = numberFormat(locale, true, 2);
            precisePercent = numberFormat(locale, true, 4);
            catalog = null;
        }
    }

    /**
     * Looks up a HUD fragment, allowing resource packs to override packaged text.
     *
     * @param suffix key below the mod's HUD namespace
     * @param arguments display values that translators may reorder
     * @return formatted text in the selected language
     */
    String translate(final String suffix, final Object... arguments) {
        final String key = "raknetify.hud." + suffix;
        final String translated = game.apply(key, arguments);
        if (!key.equals(translated)) {
            return translated;
        }
        if (catalog == null) {
            final Map<String, String> messages = new HashMap<>();
            readCatalog("en_us", messages);
            if (!"en_us".equals(code) && CODE.matcher(code).matches()) {
                readCatalog(code, messages);
            }
            catalog = messages;
        }
        return String.format(locale, catalog.getOrDefault(key, key), arguments);
    }

    /**
     * Loads packaged text for clients that do not mount mod assets as game resource packs.
     *
     * @param language exact Minecraft locale identifier
     * @param messages destination for overriding existing English defaults
     */
    private static void readCatalog(final String language, final Map<String, String> messages) {
        try (var stream =
                HudLanguage.class.getResourceAsStream(
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
            System.getLogger(HudLanguage.class.getName())
                    .log(
                            System.Logger.Level.WARNING,
                            "Unable to read HUD language " + language,
                            exception);
        }
    }

    /**
     * Constructs the game's numeric precision using the selected language's punctuation.
     *
     * @param locale numeric locale
     * @param ratio whether to scale a fraction and include the local percent sign
     * @param digits required number of fractional digits
     * @return ungrouped fixed-precision formatter
     */
    private static NumberFormat numberFormat(
            final Locale locale, final boolean ratio, final int digits) {
        final NumberFormat result =
                ratio
                        ? NumberFormat.getPercentInstance(locale)
                        : NumberFormat.getNumberInstance(locale);
        result.setGroupingUsed(false);
        result.setMinimumFractionDigits(digits);
        result.setMaximumFractionDigits(digits);
        return result;
    }

    /**
     * Formats a fixed-precision numeric measurement.
     *
     * @param value latency or queue measurement
     * @return localized measurement with two fractional digits
     */
    String decimal(final double value) {
        return decimals.format(value);
    }

    /**
     * Formats a compression or retransmission ratio.
     *
     * @param fraction unscaled transport ratio
     * @param precise whether to retain four fractional digits for small retransmission rates
     * @return localized percentage with its percent sign
     */
    String ratio(final double fraction, final boolean precise) {
        return (precise ? precisePercent : percent).format(fraction);
    }
}
