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

import com.google.common.base.Preconditions;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;

import network.ycc.raknet.RakNet;

import java.util.Map;

/** Reports backend pipeline failures without intercepting native Velocity keepalives. */
public class RakNetVelocityServerChannelEventListener
        extends io.netty.channel.ChannelInboundHandlerAdapter {

    /** Pipeline name for the backend-facing connection diagnostic handler. */
    public static final String NAME = "raknetify-bungee-downstream-event-listener";

    /**
     * Associates a backend pipeline with the RakNet client it serves.
     *
     * @param clientChannel client-facing channel required to have a RakNet configuration
     */
    public RakNetVelocityServerChannelEventListener(final Channel clientChannel) {
        Preconditions.checkArgument(clientChannel.config() instanceof RakNet.Config);
    }

    /**
     * Propagates the failure and prints the backend pipeline layout alongside its stack trace.
     *
     * @param ctx backend context reporting the exception
     * @param cause original connection failure
     * @throws Exception if superclass exception forwarding fails
     */
    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause)
            throws Exception {
        super.exceptionCaught(ctx, cause);
        cause.printStackTrace();
        for (Map.Entry<String, ChannelHandler> entry :
                ctx.channel().pipeline().toMap().entrySet()) {
            System.out.println(
                    "%s: %s".formatted(entry.getKey(), entry.getValue().getClass().getName()));
        }
    }
}
