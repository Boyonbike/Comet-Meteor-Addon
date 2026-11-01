/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against Netty and the pinned
 * Minecraft 26.2 connection pipeline; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest;

import dev.tntcoating.gametest.mixin.ConnectionAccessor;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.network.Connection;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * In-process one-way delay and jitter on the client's Netty pipeline. It is inserted
 * before Minecraft's packet_handler, so it delays decoded packet objects in both
 * directions. Each direction keeps FIFO order even when jitter is non-zero.
 */
public final class LatencyHandler extends ChannelDuplexHandler {
    public static final String NAME = "comet_latency";

    private final int oneWayMillis;
    private final int jitterMillis;
    private final Random random;
    private long nextInboundNanos;
    private long nextOutboundNanos;

    private LatencyHandler(int oneWayMillis, int jitterMillis, long seed) {
        this.oneWayMillis = oneWayMillis;
        this.jitterMillis = jitterMillis;
        this.random = new Random(seed);
    }

    public static void install(ClientGameTestContext context, int oneWayMillis, int jitterMillis,
                               long seed) {
        if (oneWayMillis <= 0 && jitterMillis <= 0) return;
        context.runOnClient(client -> {
            Connection connection = client.getConnection().getConnection();
            var channel = ((ConnectionAccessor) connection).comet$channel();
            if (channel == null) return;
            var pipeline = channel.pipeline();
            if (pipeline.get(NAME) == null) {
                pipeline.addBefore("packet_handler", NAME,
                    new LatencyHandler(oneWayMillis, Math.max(0, jitterMillis), seed));
            }
        });
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        long due = reserve(nextInboundNanos);
        nextInboundNanos = due;
        ReferenceCountUtil.retain(message);
        schedule(context, due, () -> context.fireChannelRead(message));
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
        long due = reserve(nextOutboundNanos);
        nextOutboundNanos = due;
        ReferenceCountUtil.retain(message);
        schedule(context, due, () -> context.write(message, promise));
    }

    private long reserve(long previousDue) {
        long now = System.nanoTime();
        int extra = jitterMillis == 0 ? 0 : random.nextInt(jitterMillis + 1);
        long due = now + TimeUnit.MILLISECONDS.toNanos((long) oneWayMillis + extra);
        return Math.max(due, previousDue);
    }

    private static void schedule(ChannelHandlerContext context, long dueNanos, Runnable task) {
        long delay = Math.max(0L, dueNanos - System.nanoTime());
        context.executor().schedule(task, delay, TimeUnit.NANOSECONDS);
    }
}
