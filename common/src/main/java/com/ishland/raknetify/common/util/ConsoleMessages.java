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

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** Translates messages for the administrator of the process that emits them. */
public final class ConsoleMessages {
    /**
     * Present only on a Minecraft player's application channel, including integrated-server
     * sessions.
     */
    private static final AttributeKey<BiFunction<String, Object[], String>> PLAYER =
            AttributeKey.valueOf("raknetify.playerLanguage");

    /** Administrator-selected Minecraft locale or the process display language. */
    private static final String CODE =
            System.getProperty(
                            "raknetify.language",
                            defaultCode(Locale.getDefault(Locale.Category.DISPLAY)))
                    .toLowerCase(Locale.ROOT)
                    .replace('-', '_');

    /** Complete packaged catalog, shared read-only across event loops. */
    private static final Map<String, String> CATALOG = LanguageCatalog.load(CODE);

    /** No per-connection state is needed to format console messages. */
    private ConsoleMessages() {}

    /**
     * Maps a system display locale to an installed Minecraft language.
     *
     * @param locale process display locale
     * @return matching Minecraft code or English when the language is unsupported
     */
    static String defaultCode(final Locale locale) {
        try (var stream =
                ConsoleMessages.class.getResourceAsStream(
                        "/assets/raknetify/minecraft-locales.json")) {
            if (stream == null) {
                return "en_us";
            }
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                var codes =
                        JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("locales");
                var candidates = new java.util.ArrayList<String>();
                codes.forEach(code -> candidates.add(code.getAsString().replace('_', '-')));
                return match(locale, candidates);
            }
        } catch (IOException exception) {
            return "en_us";
        }
    }

    /**
     * Chooses a matching region or script before falling back to a representative region.
     *
     * @param locale system locale, possibly without a region
     * @param codes supported Minecraft codes using hyphens
     * @return selected resource name
     */
    private static String match(final Locale locale, final List<String> codes) {
        var language = "nb".equals(locale.getLanguage()) ? "no" : locale.getLanguage();
        if (language.isEmpty()) {
            return "en_us";
        }
        var preferred =
                Map.of(
                        "en",
                        "en-us",
                        "fr",
                        "fr-fr",
                        "es",
                        "es-es",
                        "de",
                        "de-de",
                        "zh",
                        "Hant".equals(locale.getScript()) ? "zh-tw" : "zh-cn",
                        "pt",
                        "pt-pt",
                        "nl",
                        "nl-nl",
                        "sr",
                        "Latn".equals(locale.getScript()) ? "sr-cs" : "sr-sp",
                        "be",
                        "be-by");
        var candidates =
                List.of(
                        scriptCode(language, locale),
                        language + "-" + locale.getCountry(),
                        preferred.getOrDefault(language, language));
        for (String candidate : candidates) {
            if (codes.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate.toLowerCase(Locale.ROOT).replace('-', '_');
            }
        }
        return codes.stream()
                .filter(code -> code.startsWith(language + "-"))
                .findFirst()
                .orElse("en-us")
                .replace('-', '_');
    }

    /**
     * Maps explicit writing systems to Minecraft's regional and historical language identifiers.
     *
     * @param language normalized JVM language
     * @param locale administrator's display locale
     * @return catalog candidate for this script, or the ordinary language-script tag
     */
    private static String scriptCode(final String language, final Locale locale) {
        String tag = language + "-" + locale.getScript();
        return switch (tag) {
            case "zh-Hant" -> List.of("HK", "MO").contains(locale.getCountry()) ? "zh-hk" : "zh-tw";
            case "zh-Hans" -> "zh-cn";
            case "sr-Latn" -> "sr-cs";
            case "sr-Cyrl" -> "sr-sp";
            case "be-Cyrl" -> "be-by";
            case "ms-Arab", "zlm-Arab" -> "zlm-arab";
            default -> tag;
        };
    }

    /**
     * Binds player-visible transport errors to the running client's language and resource packs.
     *
     * @param channel player's application channel
     * @param language current Minecraft locale, read when an error occurs
     * @param game live Minecraft resource-pack lookup
     */
    public static void bindPlayer(
            final Channel channel,
            final Supplier<String> language,
            final BiFunction<String, Object[], String> game) {
        var formatter = new HudLanguage(game);
        channel.attr(PLAYER)
                .set((key, args) -> {
                    formatter.select(language.get());
                    return formatter.message(key, args);
                });
    }

    /**
     * Formats a transport error in the player's language or the accepting process's language.
     *
     * @param channel application channel handling the failed operation
     * @param key operator message key that can also surface on the disconnect screen
     * @param arguments literal error details
     * @return error detail in the language of this endpoint
     */
    public static String transport(
            final Channel channel, final String key, final Object... arguments) {
        var player = channel.attr(PLAYER).get();
        return player == null
                ? text(key, arguments)
                : player.apply("raknetify.console." + key, arguments);
    }

    /**
     * Creates numeric and HUD formatters for one connection report without sharing mutable state.
     *
     * @return formatter for the administrator's locale
     */
    static HudLanguage measurements() {
        return HudLanguage.packaged(CODE, CATALOG);
    }

    /**
     * Formats an operator-facing message without passing data back through a logging formatter.
     *
     * @param key message name in the operator namespace
     * @param arguments literal values whose positions the translation controls
     * @return localized message for this process
     */
    public static String text(final String key, final Object... arguments) {
        var fullKey = "raknetify.console." + key;
        return String.format(Locale.ROOT, CATALOG.getOrDefault(fullKey, fullKey), arguments);
    }
}
