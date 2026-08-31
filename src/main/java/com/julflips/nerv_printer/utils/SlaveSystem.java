package com.julflips.nerv_printer.utils;

import com.julflips.nerv_printer.interfaces.MapPrinter;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;
import org.java_websocket.WebSocket;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class SlaveSystem {

    public static int masterPort = 8080;
    public static String masterAddress = "";
    public static ArrayList<String> slaves = new ArrayList<>();
    public static HashMap<String, Boolean> activeSlavesDict = new HashMap<>();
    public static HashMap<String, Boolean> finishedSlavesDict = new HashMap<>();
    public static SlaveTableController tableController = null;

    private static MapPrinter printerModule = null;
    private static ArrayList<String> toBeConfirmedSlaves = new ArrayList<>();
    private static String master = null;

    // WebSocket transport
    private static MasterSocketServer server = null;
    private static volatile boolean serverStarted = false;
    private static SlaveSocketClient client = null;
    // Master side: connection -> slave player name (learned from the first message)
    private static final HashMap<WebSocket, String> slaveConnections = new HashMap<>();
    private static int reconnectTimer = 0;

    public static void setupSlaveSystem(MapPrinter module, int port, String address) {
        printerModule = module;
        masterPort = port;
        masterAddress = address;
        slaves.clear();
        toBeConfirmedSlaves.clear();
        activeSlavesDict.clear();
        finishedSlavesDict.clear();
        master = null;

        // Role selection: an empty master-address means we host as the master,
        // otherwise we connect to the configured master as a slave.
        if (isMasterMode()) {
            ensureServer();
        } else {
            ensureClient();
        }
    }

    public static boolean isMasterMode() {
        return masterAddress.trim().isEmpty();
    }

    // ------------------------------------------------------------------
    // WebSocket transport
    // ------------------------------------------------------------------

    private static void ensureServer() {
        if (serverStarted) return;
        stopServer();
        try {
            server = new MasterSocketServer(masterPort);
            server.start(); // runs on its own thread
        } catch (Exception e) {
            ChatUtils.error("Failed to start multi-user socket server on port " + masterPort + ": " + e.getMessage());
        }
    }

    private static void stopServer() {
        serverStarted = false;
        if (server != null) {
            try {
                server.stop(0);
            } catch (Exception ignored) {
            }
            server = null;
        }
        slaveConnections.clear();
    }

    private static void ensureClient() {
        stopServer(); // never host while acting as a slave
        if (client != null && client.isOpen()) return;
        if (client == null || client.isClosed()) {
            try {
                client = new SlaveSocketClient(new java.net.URI("ws://" + masterAddress.trim() + ":" + masterPort));
            } catch (java.net.URISyntaxException e) {
                ChatUtils.error("Invalid master address: " + masterAddress);
                return;
            }
        }
        // connect() blocks, so run it on its own thread
        new Thread(() -> {
            try {
                client.connect();
            } catch (Exception ignored) {
            }
        }).start();
        reconnectTimer = 100;
    }

    public static void restartServer(int port) {
        masterPort = port;
        if (isMasterMode() && printerModule != null) ensureServer();
    }

    /** Called from the WebSocket server thread once the server socket is bound. */
    public static void onServerStarted() {
        serverStarted = true;
    }

    /** Called from the WebSocket server thread when a slave connection drops. */
    public static void onConnectionClosed(WebSocket conn) {
        mc.execute(() -> {
            String name = slaveConnections.remove(conn);
            if (name != null && slaves.contains(name)) {
                slaves.remove(name);
                activeSlavesDict.remove(name);
                finishedSlavesDict.remove(name);
                toBeConfirmedSlaves.remove(name);
                ChatUtils.info("Slave disconnected: " + name);
                generateIntervals();
                if (tableController != null) tableController.rebuild();
            } else if (name != null) {
                toBeConfirmedSlaves.remove(name);
            }
        });
    }

    /** Called from the WebSocket client thread when the master connection drops. */
    public static void onClientDisconnected() {
        mc.execute(() -> {
            if (master != null) {
                master = null;
                ChatUtils.warning("Lost connection to master.");
                if (tableController != null) tableController.rebuild();
            }
        });
    }

    /**
     * Entry point for every incoming WebSocket message.
     * Wire format: ("s" | "m") + ":" + senderName + ":" + command[:args...]
     * "s" messages come from slaves (conn != null, master side),
     * "m" messages come from the master (conn == null, slave side).
     */
    public static void onSocketMessage(@Nullable WebSocket conn, String raw) {
        String[] split = raw.split(":", 3);
        if (split.length < 3) return;
        String sender = split[1];
        String content = split[2];
        mc.execute(() -> {
            if (printerModule == null) return;
            if (conn != null && !slaveConnections.containsKey(conn)) {
                slaveConnections.put(conn, sender);
                toBeConfirmedSlaves.add(sender);
                ChatUtils.info("New connection: " + sender);
                if (tableController != null) tableController.rebuild();
            }
            handleMessage(content, sender);
        });
    }

    private static void sendToSocket(WebSocket conn, String message) {
        conn.send("m:" + mc.player.getName().getString() + ":" + message);
    }

    // ------------------------------------------------------------------
    // Sending (direct, no chat queue / rate limiting needed anymore)
    // ------------------------------------------------------------------

    public static void queueMasterDM(String message) {
        if (master != null) {
            queueDM(master, message);
        }
    }

    public static void queueDM(String recipient, String message) {
        if (printerModule == null) return;
        if (recipient.equals(master)) {
            if (client != null && client.isOpen()) {
                client.send("s:" + mc.player.getName().getString() + ":" + message);
            }
            return;
        }
        for (Map.Entry<WebSocket, String> entry : slaveConnections.entrySet()) {
            if (entry.getValue().equals(recipient)) {
                sendToSocket(entry.getKey(), message);
                return;
            }
        }
    }

    public static boolean allSlavesFinished() {
        for (String slave : finishedSlavesDict.keySet()) {
            if (!finishedSlavesDict.get(slave)) return false;
        }
        return true;
    }

    public static void setAllSlavesUnfinished() {
        for (String slave : finishedSlavesDict.keySet()) {
            finishedSlavesDict.put(slave, false);
        }
    }

    public static boolean isSlave() {
        return master != null;
    }

    public static void sendToAllSlaves(String message) {
        for (String slave : slaves) {
            SlaveSystem.queueDM(slave, message);
        }
    }

    public static void startAllSlaves() {
        for (String slave : activeSlavesDict.keySet()) {
            if (!activeSlavesDict.get(slave)) {
                queueDM(slave, "start");
                activeSlavesDict.put(slave, true);
            }
        }
        if (printerModule != null && !printerModule.isActive() && !printerModule.getActivationReset())
            printerModule.toggle();
    }

    public static void pauseAllSlaves() {
        sendToAllSlaves("pause");
        for (String slave : activeSlavesDict.keySet()) {
            activeSlavesDict.put(slave, false);
        }
        if (printerModule != null && printerModule.isActive() && !printerModule.getActivationReset())
            printerModule.toggle();
    }

    public static void skipNextBuilding() {
        sendToAllSlaves("skip");
        if (printerModule != null) printerModule.skipBuilding();
    }

    public static void generateIntervals() {
        int sectionSize = (int) Math.ceil((float) 128 / (float) (slaves.size() + 1));
        ArrayList<Pair<Integer, Integer>> intervals = new ArrayList<>();
        for (int end = 127; end >= 0; end -= sectionSize) {
            int start = Math.max(0, end - sectionSize + 1);
            intervals.add(new Pair<>(start, end));
        }
        Collections.reverse(intervals);

        printerModule.setInterval(intervals.remove((intervals.size() - 1) / 2));

        // Sort slaves deterministically
        ArrayList<String> sortedSlaves = new ArrayList<>(slaves);
        Collections.sort(sortedSlaves, String.CASE_INSENSITIVE_ORDER);

        for (int i = 0; i < intervals.size(); i++) {
            String slave = sortedSlaves.get(i);
            SlaveSystem.queueDM(slave, "interval:" + intervals.get(i).getLeft() + ":" + intervals.get(i).getRight());
        }
    }

    public static void registerSlaves() {
        if (printerModule == null) {
            ChatUtils.warning("The module needs to be enabled to register new slaves.");
            return;
        }
        if (slaveConnections.isEmpty()) {
            ChatUtils.warning("No slaves connected to the socket server.");
        }
        for (Map.Entry<WebSocket, String> entry : slaveConnections.entrySet()) {
            if (slaves.contains(entry.getValue())) continue;
            sendToSocket(entry.getKey(), "register");
        }
    }

    public static void removeSlave(String slave) {
        slaves.remove(slave);
        activeSlavesDict.remove(slave);
        finishedSlavesDict.remove(slave);
        toBeConfirmedSlaves.remove(slave);
        queueDM(slave, "remove");
        generateIntervals();
    }

    // ------------------------------------------------------------------
    // Message handling (command logic unchanged from the DM system)
    // ------------------------------------------------------------------

    private static void handleMessage(String content, String sender) {
        if (sender.equals(mc.player.getName().getString())) return;

        String[] colonSplit = content.replace(" ", "").split(":");
        String command = colonSplit[0];
        // Register (received by a slave from the master; the socket connection
        // itself proves the master is reachable, so no render distance check)
        if (command.equals("register") && master == null && toBeConfirmedSlaves.isEmpty()
            && slaves.isEmpty()) {
            master = sender;
            SlaveSystem.queueMasterDM("accept");
        }
        // Master to Client message
        if (sender.equals(master)) {
            switch (command) {
                case "interval":
                    if (colonSplit.length < 3) break;
                    Pair<Integer, Integer> interval = new Pair<>(Integer.valueOf(colonSplit[1]), Integer.valueOf(colonSplit[2]));
                    printerModule.setInterval(interval);
                    break;
                case "pause":
                    printerModule.pause();
                    break;
                case "start":
                    printerModule.start();
                    break;
                case "remove":
                    master = null;
                    printerModule.toggle();
                    break;
                case "skip":
                    printerModule.skipBuilding();
                    break;
                case "mine":
                    if (colonSplit.length < 2) break;
                    printerModule.mineLine(Integer.valueOf(colonSplit[1]));
            }
        }
        // Client to Master message
        if (slaves.contains(sender) || toBeConfirmedSlaves.contains(sender)) {
            switch (command) {
                case "accept":
                    slaves.add(sender);
                    finishedSlavesDict.put(sender, false);
                    activeSlavesDict.put(sender, false);
                    toBeConfirmedSlaves.remove(sender);
                    ChatUtils.info("Registered slave: " + sender + " Total slaves: " + slaves.size());
                    generateIntervals();
                    if (tableController != null) tableController.rebuild();
                    break;
                case "finished":
                    finishedSlavesDict.put(sender, true);
                    activeSlavesDict.put(sender, false);
                    printerModule.slaveFinished(sender);
                    if (tableController != null) tableController.rebuild();
                    break;
                case "error":
                    if (colonSplit.length < 3) break;
                    BlockPos relativeErrorPos = new BlockPos(Integer.valueOf(colonSplit[1]), 0, Integer.valueOf(colonSplit[2]));
                    printerModule.addError(relativeErrorPos);
                    break;
            }
        }
    }

    @EventHandler
    private static void onTick(TickEvent.Pre event) {
        if (printerModule == null) return;
        if (mc.getNetworkHandler() == null) return;

        // Slave side: reconnect to the master when the connection drops
        if (!isMasterMode()) {
            if (reconnectTimer > 0) reconnectTimer--;
            if (reconnectTimer == 0 && (client == null || client.isClosed())) {
                ensureClient();
            }
        }
    }
}

