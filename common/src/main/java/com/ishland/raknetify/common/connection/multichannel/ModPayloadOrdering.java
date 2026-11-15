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

package com.ishland.raknetify.common.connection.multichannel;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.ishland.raknetify.common.util.MathUtil;

import io.netty.buffer.ByteBuf;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Binds audited mod packet classes to the identifiers chosen by the installed mod versions. */
public final class ModPayloadOrdering {
    /** Delivery boundary for a packet whose handler joins independent vanilla ordering domains. */
    public static final int FENCED = -3;

    /** Packet classes whose handlers depend on vanilla entity, menu or chunk state. */
    private static final Map<String, Policy> CLASSES = load();

    /** Architectury's stable class-name identifiers for audited Power Grid messages. */
    private static final Map<String, Policy> NAMED = namedRules();

    /** State messages can combine block keys with independently initialized UUID endpoints. */
    private static final String POWERGRID_STATE =
            architecturyIdentifier("org.patryk3211.powergrid.network.packets.StateS2CPacket");

    /** Immutable channel tables published as optional mods register their messages. */
    private static final Map<String, Map<Integer, Policy>> CHANNELS = new ConcurrentHashMap<>();

    /** Prevents constructing the shared registration adapter. */
    private ModPayloadOrdering() {}

    /**
     * Records an installed mod's message discriminator without loading the optional packet class.
     *
     * @param identifier namespaced Forge channel identifier
     * @param discriminator registration identifier, narrowed to the unsigned Forge 47 wire byte
     * @param packetClass fully qualified packet class captured during registration
     */
    public static void register(
            final String identifier, final int discriminator, final String packetClass) {
        Policy route = CLASSES.get(packetClass);
        if (route == null && !CHANNELS.containsKey(identifier)) {
            return;
        }
        int wireId = discriminator & 0xFF;
        CHANNELS.compute(
                identifier,
                (key, previous) -> {
                    Map<Integer, Policy> next =
                            new HashMap<>(previous == null ? Map.of() : previous);
                    if (route == null) {
                        next.remove(wireId);
                    } else {
                        next.put(wireId, route);
                    }
                    return Map.copyOf(next);
                });
    }

    /**
     * Examines the mod's message identifier while preserving the caller's bytes and ownership.
     *
     * @param identifier namespaced custom payload identifier
     * @param payload readable payload immediately after that identifier
     * @return audited ordering lane, or zero when this message has no installed rule
     */
    public static int channel(final String identifier, final ByteBuf payload) {
        return channel(identifier, payload, false);
    }

    /**
     * Selects the direction-specific policy without advancing or retaining the payload.
     *
     * @param identifier namespaced custom payload identifier
     * @param payload readable bytes after the identifier
     * @param clientSender true for serverbound traffic
     * @return audited lane, or zero for an unregistered message
     */
    public static int channel(
            final String identifier, final ByteBuf payload, final boolean clientSender) {
        int raw = rawChannel(identifier, payload, clientSender);
        if (raw != 0) {
            return raw;
        }
        Map<Integer, Policy> routes = CHANNELS.get(identifier);
        if (routes == null || !payload.isReadable()) {
            return 0;
        }
        int discriminator = payload.getUnsignedByte(payload.readerIndex());
        Policy policy = routes.get(discriminator);
        return policy == null ? 0 : policy.lane(clientSender);
    }

    /**
     * Routes audited event-channel envelopes which do not register indexed message classes.
     *
     * @param identifier outer Forge payload name
     * @param payload readable envelope after that name
     * @param clientSender whether the client is sending
     * @return known policy or zero for an unrelated envelope
     */
    private static int rawChannel(
            final String identifier, final ByteBuf payload, final boolean clientSender) {
        return switch (identifier) {
            case "forge:split", "supermartijn642configlib:sync_configs" -> FENCED;
            case "jei:channel",
                    "jei:recipe_transfer_result_channel",
                    "trade_cycling:cycle_trades" ->
                    clientSender ? 2 : 3;
            case "architectury:network" -> architecturyChannel(payload, clientSender);
            default -> 0;
        };
    }

    /**
     * Unwraps Architectury's namespaced envelope without guessing dynamically allocated opcodes.
     *
     * @param payload envelope beginning with its inner identifier
     * @param clientSender true for serverbound traffic
     * @return audited boundary, or zero for an unrelated inner payload
     */
    private static int architecturyChannel(final ByteBuf payload, final boolean clientSender) {
        if (!payload.isReadable()) {
            return 0;
        }
        ByteBuf envelope = payload.slice();
        String inner = MathUtil.readString(envelope);
        if (POWERGRID_STATE.equals(inner)) {
            return clientSender ? 2 : powerGridState(envelope);
        }
        Policy policy = NAMED.get(inner);
        if (policy != null) {
            return policy.lane(clientSender);
        }
        return switch (inner) {
            case "ftblibrary:edit_nbt",
                    "ftblibrary:edit_nbt_response",
                    "ftblibrary:edit_config",
                    "ftblibrary:sync_known_server_registries" ->
                    FENCED;
            case "ftbessentials:update_tab_name" -> 1;
            default -> 0;
        };
    }

    /**
     * Keeps frequent block-only state batches concurrent, fencing batches containing UUID
     * endpoints.
     *
     * @param payload state body after the Architectury inner identifier
     * @return chunk lane for a known block-only batch, otherwise a cross-domain boundary
     */
    private static int powerGridState(final ByteBuf payload) {
        if (payload.readableBytes() < 5) {
            return FENCED;
        }
        payload.skipBytes(1);
        int keys = payload.readInt();
        if (keys < 0 || keys > 4096) {
            return FENCED;
        }
        for (int index = 0; index < keys; index++) {
            if (payload.readableBytes() < 9 || payload.readByte() != 0) {
                return FENCED;
            }
            payload.skipBytes(8);
        }
        return 7;
    }

    /**
     * Computes the exact inner identifier allocated by Architectury NetworkChannel.
     *
     * @param type fully qualified Power Grid message class
     * @return namespaced class-name hash
     */
    private static String architecturyIdentifier(final String type) {
        return "powergrid:main/"
                + UUID.nameUUIDFromBytes(type.getBytes(StandardCharsets.UTF_8))
                        .toString()
                        .replace("-", "");
    }

    /**
     * Recreates Architectury's class-name hash without optional class loading.
     *
     * @return immutable rules keyed by the envelope's namespaced inner identifier
     */
    private static Map<String, Policy> namedRules() {
        Map<String, Policy> result = new HashMap<>();
        CLASSES.forEach((type, policy) -> {
            if (type.startsWith("org.patryk3211.powergrid.")) {
                result.put(architecturyIdentifier(type), policy);
            }
        });
        return Map.copyOf(result);
    }

    /**
     * Independent policies for the two vanilla protocol directions.
     *
     * @param clientbound lane used by a server sender
     * @param serverbound lane used by a client sender
     */
    private record Policy(int clientbound, int serverbound) {
        /**
         * Selects the outgoing protocol direction.
         *
         * @param clientSender whether the client is sending
         * @return selected lane
         */
        int lane(final boolean clientSender) {
            return clientSender ? serverbound : clientbound;
        }
    }

    /**
     * Decodes and validates one lane.
     *
     * @param value lane from the bundled policy file
     * @return supported lane
     * @throws IllegalArgumentException if the lane is unsupported
     */
    private static int lane(final JsonElement value) {
        int lane = value.getAsInt();
        if (lane != FENCED && lane != 1 && lane != 2 && lane != 3 && lane != 4 && lane != 7) {
            throw new IllegalArgumentException("Invalid mod packet ordering lane");
        }
        return lane;
    }

    /**
     * Reads a shared or direction-specific policy.
     *
     * @param value JSON policy
     * @return validated policy
     */
    private static Policy policy(final JsonElement value) {
        if (value.isJsonPrimitive()) {
            int lane = lane(value);
            return new Policy(lane, lane);
        }
        var object = value.getAsJsonObject();
        return new Policy(lane(object.get("clientbound")), lane(object.get("serverbound")));
    }

    /**
     * Reads explicit compatibility rules and rejects unsupported delivery policies.
     *
     * @return immutable rules without optional mod class references
     * @throws IllegalStateException if the bundled rules cannot be read
     * @throws IllegalArgumentException if a rule uses an unsupported ordering policy
     */
    private static Map<String, Policy> load() {
        var stream =
                Objects.requireNonNull(
                        ModPayloadOrdering.class.getResourceAsStream("/mod-packet-ordering.json"));
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            Map<String, Policy> rules = new HashMap<>();
            JsonParser.parseReader(reader)
                    .getAsJsonObject()
                    .entrySet()
                    .forEach(entry -> {
                        rules.put(entry.getKey(), policy(entry.getValue()));
                    });
            return Map.copyOf(rules);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot read mod packet ordering rules", failure);
        }
    }
}
