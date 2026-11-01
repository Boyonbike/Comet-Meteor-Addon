/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2
 * server classes; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest.server;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * A server-side connection that accepts and discards every outbound packet. It lets a
 * {@link net.minecraft.server.level.ServerPlayer} exist in the world and be ticked by
 * the server without a real client socket.
 */
public final class NullConnection extends Connection {
    public NullConnection() {
        super(PacketFlow.SERVERBOUND);
    }

    @Override
    public void send(Packet<?> packet) {
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener) {
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T listener) {
        // No channel exists, so skip the protocol-switch packet that vanilla writes here.
    }

    @Override
    public void flushChannel() {
    }

    @Override
    public void tick() {
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public boolean isMemoryConnection() {
        return true;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return new InetSocketAddress("127.0.0.1", 0);
    }

    @Override
    public String getLoggableAddress(boolean logIPs) {
        return "harness-fake";
    }

    @Override
    public void disconnect(Component reason) {
    }

    @Override
    public void disconnect(DisconnectionDetails details) {
    }
}
