package com.julflips.nerv_printer.utils;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

/**
 * WebSocket client used by a slave bot to talk to the master bot's
 * MasterSocketServer. Replaces the old server direct-message transport.
 */
public final class SlaveSocketClient extends WebSocketClient {
    private static final Logger LOG = LoggerFactory.getLogger(SlaveSocketClient.class);

    public SlaveSocketClient(URI uri) {
        super(uri);
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        // Introduce ourselves so the master can map this connection to a player name
        send("s:" + meteordevelopment.meteorclient.MeteorClient.mc.player.getName().getString() + ":connect");
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        SlaveSystem.onClientDisconnected();
    }

    @Override
    public void onMessage(String message) {
        SlaveSystem.onSocketMessage(null, message);
    }

    @Override
    public void onError(Exception ex) {
        LOG.error("Slave socket error", ex);
    }
}
