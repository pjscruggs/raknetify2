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

import com.google.gson.JsonParser;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Set;

/** Connection-local ordering policy shared with the underlying transport event loop. */
public final class CompatibilityPolicy {
    /** Shared by the application channel and its transport parent, never by different players. */
    private static final AttributeKey<CompatibilityPolicy> KEY =
            AttributeKey.valueOf("raknetify-compatibility");

    /** Published to the transport event loop before ordered gameplay can begin. */
    private volatile boolean ordered;

    /**
     * Captures a listener's requirement before accepting any application traffic.
     *
     * @param ordered whether the server requires a single reliable ordered stream
     */
    public CompatibilityPolicy(final boolean ordered) {
        this.ordered = ordered;
    }

    /**
     * Publishes the same policy to an application channel and its optional transport parent.
     *
     * @param channel application channel
     * @param transport underlying transport, or the application channel itself
     */
    public void install(final Channel channel, final Channel transport) {
        channel.attr(KEY).set(this);
        transport.attr(KEY).set(this);
    }

    /**
     * Tests the effective connection policy without changing unconfigured TCP channels.
     *
     * @param channel application or transport channel
     * @return whether all Minecraft traffic must retain stream order
     */
    public static boolean isOrdered(final Channel channel) {
        CompatibilityPolicy policy = channel.attr(KEY).get();
        return policy != null && policy.ordered;
    }

    /** Adopts the server's requirement before the first inbound Minecraft packet. */
    void requireOrdered() {
        ordered = true;
    }

    /**
     * Reads the server setting once. Clients follow their individual server's declaration.
     *
     * @return whether the server requires ordered compatibility
     */
    public static boolean serverRequiresOrdered() {
        return ServerSetting.ORDERED;
    }

    /**
     * Loads a bounded configuration, with an absent file preserving multichannel behavior.
     *
     * @param path server configuration file
     * @return whether the explicitly selected mode is ordered
     * @throws IOException if the file is unreadable or too large
     */
    public static boolean load(final Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(4097);
            if (bytes.length > 4096) {
                throw new IOException("Compatibility configuration exceeds 4 KiB");
            }
            return parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (NoSuchFileException absent) {
            return false;
        }
    }

    /**
     * Rejects misspelled settings rather than silently enabling the wrong delivery policy.
     *
     * @param json document containing only a mode string
     * @return whether ordered mode was selected
     * @throws IllegalArgumentException if the setting is malformed or unknown
     */
    static boolean parse(final String json) {
        var root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.keySet().equals(Set.of("mode"))
                || !root.get("mode").getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(
                    "Expected compatibility mode: ordered or multichannel");
        }
        return switch (root.get("mode").getAsString()) {
            case "ordered" -> true;
            case "multichannel" -> false;
            default -> throw new IllegalArgumentException("Unknown Raknetify2 compatibility mode");
        };
    }

    /** Loads the server-only configuration lazily, without applying a client's local file. */
    private static final class ServerSetting {
        /** Immutable setting retained until process restart. */
        private static final boolean ORDERED = read();

        /** Prevents constructing the configuration holder. */
        private ServerSetting() {}

        /**
         * Reads and reports the selected policy before the first server connection is admitted.
         *
         * @return whether ordered compatibility is required
         * @throws IllegalStateException if the configuration cannot be read
         */
        private static boolean read() {
            try {
                boolean ordered = load(Path.of("config", "raknetify2-compatibility.json"));
                System.out.println(
                        "Raknetify2 compatibility: "
                                + (ordered
                                        ? "ordered (updated clients required, genuine"
                                                + " acknowledgements)"
                                        : "multichannel"));
                return ordered;
            } catch (IOException failure) {
                throw new IllegalStateException(
                        "Cannot read Raknetify2 compatibility configuration", failure);
            }
        }
    }
}
