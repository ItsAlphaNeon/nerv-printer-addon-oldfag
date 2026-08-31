package com.julflips.nerv_printer.utils;

import meteordevelopment.meteorclient.utils.player.ChatUtils;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * WebSocket server hosted by the master bot. Every slave bot connects to this
 * server with a WebSocket client; messages are exchanged instead of server DMs.
 */
public final class MasterSocketServer extends WebSocketServer {
    private static final Logger LOG = LoggerFactory.getLogger(MasterSocketServer.class);

    public MasterSocketServer(int port) {
        super(new InetSocketAddress(port));
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        SlaveSystem.onConnectionClosed(conn);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        SlaveSystem.onSocketMessage(conn, message);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn == null) {
            // Server-level error (e.g. the bind failed) - nothing is listening
            SlaveSystem.onServerError(ex.toString());
        }
        LOG.error("Master socket error", ex);
    }

    @Override
    public void onStart() {
        SlaveSystem.onServerStarted();
        // ChatUtils is not thread-safe outside of the game thread, use execute to marshal
        meteordevelopment.meteorclient.MeteorClient.mc.execute(() ->
            ChatUtils.info("Multi-user socket server started on port " + getPort()));
    }
}
