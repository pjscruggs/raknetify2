/*
 * This file is a part of the Velocity implementation of the Raknetify
 * project, licensed under GPLv3.
 *
 * Copyright (c) 2022-2025 ishland
 * Copyright (c) 2026 pjscruggs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ishland.raknetify.velocity.connection;

import com.ishland.raknetify.common.util.MathUtil;
import com.ishland.raknetify.velocity.RaknetifyVelocityPlugin;
import com.velocitypowered.proxy.network.Connections;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.util.ArrayList;
import java.util.List;

/** Splits the TCP-framed buffers used by LimboAPI 1.1.26 into separate Minecraft packet bodies. */
final class LegacyLimboFrames extends MessageToMessageEncoder<ByteBuf> {
    /** Name used by the old plugin when it inserts its pre-encoding handler. */
    private static final String PREPARED = "fastprepare-encoder";

    /** Distinguishes this adapter from the one used by FastMOTD on the same proxy. */
    private static final String NAME = "raknetify-limbo-frames";

    /** Only this plugin's encoder produces the legacy byte representation this adapter accepts. */
    private final ChannelHandler encoder;

    /**
     * Captures the exact encoder instance so unrelated or subsequent writes remain untouched.
     *
     * @param encoder old LimboAPI packet encoder
     */
    private LegacyLimboFrames(final ChannelHandler encoder) {
        this.encoder = encoder;
    }

    /**
     * Requests uncompressed prepared packets during the legacy plugin's login notification.
     *
     * @param ctx RakNet connection receiving the login hook
     * @throws ReflectiveOperationException if the detected legacy encoder has no uncompressed mode
     */
    static void install(final ChannelHandlerContext ctx) throws ReflectiveOperationException {
        var plugin = RaknetifyVelocityPlugin.proxy().getPluginManager().getPlugin("limboapi");
        if (plugin.isEmpty()
                || plugin.get().getDescription().getVersion().filter("1.1.26"::equals).isEmpty()) {
            return;
        }
        var enc = ctx.pipeline().get(PREPARED);
        if (enc == null
                || !"net.elytrium.limboapi.thirdparty.fastprepare.handler.PreparedPacketEncoder"
                        .equals(enc.getClass().getName())) {
            return;
        }
        enc.getClass().getMethod("setShouldSendUncompressed", boolean.class).invoke(enc, true);
        if (ctx.pipeline().get(NAME) != null) {
            ctx.pipeline().remove(NAME);
        }
        ctx.pipeline().addBefore(PREPARED, NAME, new LegacyLimboFrames(enc));
    }

    /**
     * Restores the transport before the first backend write, including decoded Minecraft objects.
     *
     * @param ctx outbound context on this player's RakNet channel
     * @param message the next outgoing proxy message
     * @param promise notification for the original write
     * @throws Exception if the downstream write fails
     */
    @Override
    public void write(
            final ChannelHandlerContext ctx, final Object message, final ChannelPromise promise)
            throws Exception {
        if (!encoder.equals(ctx.pipeline().get(PREPARED))) {
            restore(ctx.pipeline());
            ctx.pipeline().remove(this);
        }
        super.write(ctx, message, promise);
    }

    /**
     * Leaves buffers not produced during this plugin session alone. Never changes the reader
     * indices of LimboAPI's shared prepared packet cache.
     *
     * @param ctx outbound context immediately before the prepared encoder
     * @param buffer outgoing Minecraft frames belonging to the plugin
     * @param output separate packet bodies for the RakNet encoder
     * @throws IllegalArgumentException if a plugin frame is truncated or empty
     */
    @Override
    protected void encode(
            final ChannelHandlerContext ctx, final ByteBuf buffer, final List<Object> output) {
        if (encoder.equals(ctx.pipeline().get(PREPARED))) {
            split(buffer, output);
        } else {
            output.add(buffer.retain());
        }
    }

    /**
     * Reinstates RakNet framing after the plugin restores Velocity's TCP codec during handoff.
     *
     * @param pipeline handlers belonging to this one RakNet connection
     */
    private static void restore(final ChannelPipeline pipeline) {
        replaceTcpHandler(
                pipeline,
                Connections.FRAME_ENCODER,
                "com.velocitypowered.proxy.protocol.netty.MinecraftVarintLengthEncoder");
        replaceTcpHandler(
                pipeline,
                Connections.COMPRESSION_ENCODER,
                "com.velocitypowered.proxy.protocol.netty.MinecraftCompressorAndLengthEncoder");
    }

    /**
     * Removes only the concrete TCP encoder restored by the legacy plugin.
     *
     * @param pipeline player's transport pipeline
     * @param name original Velocity pipeline slot
     * @param type expected upstream TCP encoder class
     */
    private static void replaceTcpHandler(
            final ChannelPipeline pipeline, final String name, final String type) {
        ChannelHandler handler = pipeline.get(name);
        if (handler != null && type.equals(handler.getClass().getName())) {
            pipeline.replace(name, name, new ChannelDuplexHandler());
        }
    }

    /**
     * Validates the full batch before transferring any slices to the transport.
     *
     * @param buffer original reusable prepared buffer
     * @param output complete packet bodies
     * @throws IllegalArgumentException if a packet is empty or exceeds the remaining bytes
     */
    static void split(final ByteBuf buffer, final List<Object> output) {
        ByteBuf view = buffer.duplicate();
        List<ByteBuf> slices = new ArrayList<>();
        while (view.isReadable()) {
            int length = MathUtil.readVarInt(view);
            if (length <= 0 || length > view.readableBytes()) {
                throw new IllegalArgumentException("Invalid prepared Limbo packet length");
            }
            slices.add(view.readSlice(length));
        }
        for (ByteBuf slice : slices) {
            output.add(slice.retain());
        }
    }
}
