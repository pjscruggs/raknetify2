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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ishland.raknetify.common.util.ConsoleMessages;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Set;

/**
 * Local permissions for sharing connection measurements and enforcing a delivered refusal.
 *
 * @param share whether this endpoint may disclose its own measurements
 * @param disconnect whether repeated requests following a delivered refusal close the connection
 */
record DiagnosticsPolicy(boolean share, boolean disconnect) {
    /** A client can answer a diagnostic request from its server. */
    static final DiagnosticsPolicy CLIENT = new DiagnosticsPolicy(true, false);

    /** Servers keep measurements private and enforce delivered refusals by default. */
    static final DiagnosticsPolicy DEFAULT_SERVER = new DiagnosticsPolicy(false, true);

    /**
     * Returns the policy of the process accepting the connection.
     *
     * @return settings read once on the first server connection
     */
    static DiagnosticsPolicy server() {
        return Server.POLICY;
    }

    /**
     * Reads optional settings, preserving each omitted default.
     *
     * @param path configuration in the accepting server's working directory
     * @return validated local policy
     */
    static DiagnosticsPolicy load(final Path path) {
        try (var file = Files.newInputStream(path)) {
            byte[] bytes = file.readNBytes(4097);
            if (bytes.length > 4096) {
                throw new IllegalArgumentException(ConsoleMessages.text("maximum", path, "4 KiB"));
            }
            var root =
                    JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            for (String key : root.keySet()) {
                if (!Set.of("shareServerMetrics", "disconnectOnRejectedRequests").contains(key)) {
                    throw new IllegalArgumentException(ConsoleMessages.text("unknown_option", key));
                }
            }
            return new DiagnosticsPolicy(
                    setting(root, "shareServerMetrics", DEFAULT_SERVER.share()),
                    setting(root, "disconnectOnRejectedRequests", DEFAULT_SERVER.disconnect()));
        } catch (NoSuchFileException absent) {
            return DEFAULT_SERVER;
        } catch (IOException failure) {
            throw new UncheckedIOException(ConsoleMessages.text("read", path), failure);
        }
    }

    /**
     * Reads a JSON boolean without accepting strings or numbers as permissions.
     *
     * @param root validated settings object
     * @param key optional field name
     * @param fallback value used for an omitted field
     * @return explicit boolean or its default
     */
    private static boolean setting(
            final JsonObject root, final String key, final boolean fallback) {
        var value = root.get(key);
        if (value == null) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(
                    ConsoleMessages.text("expected", key, "true | false"));
        }
        return value.getAsBoolean();
    }

    /** A connecting client never loads the accepting server's configuration. */
    private static final class Server {
        /** Stable for the lifetime of this server process. */
        private static final DiagnosticsPolicy POLICY =
                load(Path.of("config", "raknetify2-diagnostics.json"));
    }
}
