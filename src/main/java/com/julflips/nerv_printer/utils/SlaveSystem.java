package com.julflips.nerv_printer.utils;

import com.julflips.nerv_printer.interfaces.MapPrinter;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.ChatMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
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
    // Bootstrap invite transport (server DMs, only used to hand out connection details)
    public static String directMessageCommand = "w";
    public static String senderPrefix = "";
    public static String senderSuffix = " whispers: ";
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
    private static int serverRetryTimer = 0;
    private static SlaveSocketClient client = null;
    // Master side: connection -> slave player name (learned from the first message)
    private static final HashMap<WebSocket, String> slaveConnections = new HashMap<>();
    private static int reconnectTimer = 0;
    private static int connectAttempts = 0;
    // One-shot invite DM queue (spaced out to survive anti-spam plugins)
    private static final ArrayList<String> toBeSentInvites = new ArrayList<>();
    private static int inviteTimer = 0;

    public static void setupSlaveSystem(MapPrinter module, int port, String address) {
        setupSlaveSystem(module, port, address, "w", "", " whispers: ");
    }

    public static void setupSlaveSystem(MapPrinter module, int port, String address, String dmCommand, String prefix, String suffix) {
        boolean sameModule = printerModule == module;
        if (!sameModule) {
            // A different module instance takes over - reset the hive completely
            slaves.clear();
            toBeConfirmedSlaves.clear();
            toBeSentInvites.clear();
            activeSlavesDict.clear();
            finishedSlavesDict.clear();
            master = null;
        }
        printerModule = module;
        boolean portChanged = masterPort != port;
        boolean addressChanged = !masterAddress.equals(address);
        masterPort = port;
        masterAddress = address;
        directMessageCommand = dmCommand;
        senderPrefix = prefix;
        senderSuffix = suffix;

        if (isMasterMode()) {
            // Re-activation of the same module must NOT wipe registered slaves:
            // only restart the server if the port actually changed.
            if (portChanged) stopServer();
            ensureServer();
        } else {
            if (portChanged || addressChanged) stopClient();
            ensureClient();
        }

        if (sameModule) healStaleRegistrations();
        printHivemindStatus();
    }

    /** Drops registered slaves whose socket connection is gone and re-splits the rows. */
    private static void healStaleRegistrations() {
        ArrayList<String> stale = new ArrayList<>();
        for (String slave : slaves) {
            if (!slaveConnections.containsValue(slave)) stale.add(slave);
        }
        if (stale.isEmpty()) return;
        for (String slave : stale) {
            slaves.removeIf(n -> n.equals(slave));
            activeSlavesDict.remove(slave);
            finishedSlavesDict.remove(slave);
            HiveLog.log("HEAL dropped stale registration: " + slave);
            ChatUtils.warning("Dropping stale slave registration: " + slave);
        }
        generateIntervals();
    }

    private static void stopClient() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
            client = null;
        }
        master = null;
    }

    /** Prints the current hivemind state to the local chat for quick diagnosis. */
    public static void printHivemindStatus() {
        if (printerModule == null) return;
        if (isMasterMode()) {
            if (!serverStarted) {
                ChatUtils.info("Hivemind: hosting mode, but the socket server is NOT running.");
            } else {
                ChatUtils.info("Hivemind: hosting on port " + masterPort + " - "
                    + slaveConnections.size() + " open connection(s), " + slaves.size() + " registered slave(s).");
            }
            for (String slave : slaves) {
                ChatUtils.info("  Slave " + slave + " - finished: " + finishedSlavesDict.get(slave));
            }
            for (String pending : toBeConfirmedSlaves) {
                ChatUtils.info("  Pending (connected, unregistered): " + pending);
            }
        } else {
            ChatUtils.info("Hivemind: slave mode - " + (master != null ? "registered to " + master
                : "not registered") + ", connection " + (client != null && client.isOpen() ? "open" : "closed")
                + " to " + masterAddress.trim() + ":" + masterPort + ".");
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
        if (masterAddress.trim().isEmpty()) return;
        connectAttempts++;
        ChatUtils.info("Connecting to master " + masterAddress.trim() + ":" + masterPort
            + (connectAttempts > 1 ? " (attempt " + connectAttempts + ")" : "") + "...");
        // Always create a fresh client object - closed WebSocketClients are not
        // reliably re-connectable after a failed handshake.
        try {
            client = new SlaveSocketClient(new java.net.URI("ws://" + masterAddress.trim() + ":" + masterPort));
        } catch (java.net.URISyntaxException e) {
            ChatUtils.error("Invalid master address: " + masterAddress);
            return;
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
        serverRetryTimer = 0;
        HiveLog.log("SOCKET server started on port " + masterPort);
    }

    /** Called from the WebSocket server thread when a server-level error occurs (e.g. bind failure). */
    public static void onServerError(String message) {
        serverStarted = false;
        HiveLog.log("SOCKET server FAILED: " + message + " (retrying every 5s)");
        mc.execute(() -> ChatUtils.warning("Socket server failed: " + message
            + " - is another instance still hosting on port " + masterPort + "? Retrying every 5s..."));
    }

    /** Called from the WebSocket server thread when a slave connection drops. */
    public static void onConnectionClosed(WebSocket conn) {
        mc.execute(() -> {
            String name = slaveConnections.remove(conn);
            if (name != null && slaves.contains(name)) {
                slaves.removeIf(n -> n.equals(name));
                activeSlavesDict.remove(name);
                finishedSlavesDict.remove(name);
                toBeConfirmedSlaves.remove(name);
                HiveLog.log("DISCONNECT " + name + " (registered slaves left: " + slaves.size() + ")");
                ChatUtils.info("Slave disconnected: " + name);
                generateIntervals();
                if (tableController != null) tableController.rebuild();
            } else if (name != null) {
                toBeConfirmedSlaves.remove(name);
            }
        });
    }

    /** Called from the WebSocket client thread when the handshake succeeds. */
    public static void onClientConnected() {
        mc.execute(() -> {
            connectAttempts = 0;
            ChatUtils.info("Connected to master at " + masterAddress.trim() + ":" + masterPort + ". Awaiting registration...");
        });
    }

    /** Called from the WebSocket client thread when a connection attempt errors out. */
    public static void onConnectError(String message) {
        mc.execute(() -> ChatUtils.warning("Connection to master " + masterAddress.trim() + ":" + masterPort
            + " failed: " + message + " - retrying in 5s."));
    }

    /** Called from the WebSocket client thread when the handshake closed without ever opening. */
    public static void onConnectNeverEstablished(String reason) {
        mc.execute(() -> ChatUtils.warning("No connection detected to master " + masterAddress.trim() + ":"
            + masterPort + (reason == null || reason.isEmpty() ? "" : " (" + reason + ")") + " - retrying in 5s."));
    }

    /** Called from the WebSocket client thread when the master connection drops. */
    public static void onClientDisconnected() {
        mc.execute(() -> {
            if (master != null) {
                master = null;
                ChatUtils.warning("Lost connection to master - reconnecting in 5s.");
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
        if (conn != null) {
            // A slave is talking to us - this (and only this) activates the log file
            HiveLog.enable();
        }
        // Log every slave -> master wire message (payload truncated for map/config transfers)
        HiveLog.logMessage("IN ", sender, content);
        mc.execute(() -> {
            if (printerModule == null) return;
            if (conn != null && !slaveConnections.containsKey(conn)) {
                slaveConnections.put(conn, sender);
                toBeConfirmedSlaves.add(sender);
                ChatUtils.info("New connection: " + sender);
                if (tableController != null) tableController.rebuild();
                // Auto-register: the slave deliberately connected to this master's
                // socket, so send the register handshake right away. The Register
                // button remains available as a manual fallback.
                sendToSocket(conn, "register");
            }
            handleMessage(content, sender);
        });
    }

    private static void sendToSocket(WebSocket conn, String message) {
        // Single funnel for ALL master -> slave traffic: log every command here
        HiveLog.logMessage("OUT", slaveConnections.get(conn), message);
        // The game can be shutting down (player already gone) while a socket
        // close event still triggers sends - drop them instead of crashing.
        if (mc.player == null) return;
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
        // Master-side send to a slave we no longer have a socket for - likely lost
        HiveLog.log("WARN send to " + recipient + " failed: no open connection (msg: " + message + ")");
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

    /** True while a printer module is using the hivemind system (used by HiveLog). */
    public static boolean isHiveActive() {
        return printerModule != null;
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

    /** Pauses the whole hivemind: the master pauses in place, every slave receives "pause". */
    public static void pauseHive() {
        if (printerModule == null) return;
        HiveLog.log("HIVE PAUSE requested (slaves: " + slaves.size() + ")");
        printerModule.pause();
        sendToAllSlaves("pause");
        for (String slave : activeSlavesDict.keySet()) {
            activeSlavesDict.put(slave, false);
        }
    }

    /** Resumes the whole hivemind: the master resumes its paused state, slaves receive "start". */
    public static void resumeHive() {
        if (printerModule == null) return;
        HiveLog.log("HIVE RESUME requested (slaves: " + slaves.size() + ")");
        printerModule.start();
        startAllSlaves();
    }

    public static void skipNextBuilding() {
        sendToAllSlaves("skip");
        if (printerModule != null) printerModule.skipBuilding();
    }

    public static void broadcastSetup() {
        if (printerModule != null) printerModule.broadcastSetup();
    }

    public static void generateIntervals() {
        int sectionSize = (int) Math.ceil((float) 128 / (float) (slaves.size() + 1));
        ArrayList<Pair<Integer, Integer>> intervals = new ArrayList<>();
        for (int end = 127; end >= 0; end -= sectionSize) {
            int start = Math.max(0, end - sectionSize + 1);
            intervals.add(new Pair<>(start, end));
        }
        Collections.reverse(intervals);

        Pair<Integer, Integer> printerModuleInterval = intervals.remove((intervals.size() - 1) / 2);
        printerModule.setInterval(printerModuleInterval);

        // Sort slaves deterministically
        ArrayList<String> sortedSlaves = new ArrayList<>(slaves);
        Collections.sort(sortedSlaves, String.CASE_INSENSITIVE_ORDER);

        StringBuilder assignment = new StringBuilder("INTERVALS reassigned -> master: rows "
            + printerModuleInterval.getLeft() + "-" + printerModuleInterval.getRight());
        for (int i = 0; i < intervals.size(); i++) {
            String slave = sortedSlaves.get(i);
            assignment.append(", ").append(slave).append(": rows ")
                .append(intervals.get(i).getLeft()).append("-").append(intervals.get(i).getRight());
            SlaveSystem.queueDM(slave, "interval:" + intervals.get(i).getLeft() + ":" + intervals.get(i).getRight());
        }
        HiveLog.log(assignment.toString());

        // Hivemind: parked (already finished) slaves may have received new rows -
        // re-activate them so the re-split rows actually get built.
        printerModule.onIntervalsReassigned();
    }

    // ------------------------------------------------------------------
    // Bootstrap invite (server DM transport, discovery only)
    // ------------------------------------------------------------------

    /** Resolves this machine's LAN IPv4 address so invites reach bots on other PCs. */
    public static String resolveLocalIp() {
        try {
            for (java.net.NetworkInterface nic : Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) continue;
                for (java.net.InetAddress addr : Collections.list(nic.getInetAddresses())) {
                    if (addr instanceof java.net.Inet4Address && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
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

    /** Master side: DM the WebSocket connection details to every player in render distance. */
    public static void invitePlayersInRange() {
        if (printerModule == null) {
            ChatUtils.warning("The module needs to be enabled to invite new slaves.");
            return;
        }
        if (!isMasterMode()) {
            ChatUtils.warning("You are not hosting - master-address is set. Clear it on the master bot.");
            return;
        }
        if (!serverStarted) {
            ChatUtils.warning("Socket server is not running yet - re-enable the module to start hosting.");
            return;
        }
        String ip = resolveLocalIp();
        ArrayList<String> foundPlayers = new ArrayList<>();
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof PlayerEntity player && !mc.player.equals(player)) {
                foundPlayers.add(player.getName().getString());
            }
        }
        if (foundPlayers.isEmpty()) {
            ChatUtils.warning("No players found in render distance.");
            return;
        }
        int invited = 0;
        for (String player : foundPlayers) {
            if (slaves.contains(player)) continue;
            toBeSentInvites.add(directMessageCommand + " " + player + " hivemind:" + ip + ":" + masterPort);
            invited++;
        }
        if (invited > 0) {
            ChatUtils.info("Invite sent to " + invited + " player(s): hivemind:" + ip + ":" + masterPort);
        }
    }

    /** True if a player with this name is currently visible in render distance. */
    private static boolean canSeePlayer(String playerName) {
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof PlayerEntity player && player.getName().getString().equals(playerName)) {
                return true;
            }
        }
        return false;
    }

    /** Slave side: parse a received invite and join the master's socket server. */
    private static void handleInvite(String sender, String ip, String port) {
        if (serverStarted) return;                                  // We host ourselves, ignore invites
        if (client != null && client.isOpen()) return;              // Already connected
        if (master != null) return;                                 // Already part of a hive
        if (!canSeePlayer(sender)) return;                          // Anti-spoof: sender must be in render distance
        int parsedPort;
        try {
            parsedPort = Integer.parseInt(port);
        } catch (NumberFormatException e) {
            return;
        }
        masterAddress = ip;
        masterPort = parsedPort;
        connectAttempts = 0;
        ChatUtils.info("Joining hivemind of §a" + sender + "§7 at " + ip + ":" + parsedPort + "...");
        ensureClient();
    }

    /** Master side: confirmation DM from a slave whose socket connection + registration succeeded. */
    private static void handleInviteAccept(String sender) {
        if (slaves.contains(sender)) {
            ChatUtils.info("Slave §a" + sender + "§7 joined the hivemind via invite.");
        } else {
            ChatUtils.info(sender + " confirmed the invite (registration pending) - retrying registration...");
            // Self-heal: re-send the register handshake to this connection
            for (Map.Entry<WebSocket, String> entry : slaveConnections.entrySet()) {
                if (entry.getValue().equals(sender)) {
                    sendToSocket(entry.getKey(), "register");
                    break;
                }
            }
        }
    }

    /** Parses chat/system messages looking ONLY for hivemind bootstrap commands. */
    private static void handleBootstrapMessage(String rawMessage, @Nullable String senderName) {
        if (printerModule == null) return;
        String content;
        if (senderName != null) {
            content = rawMessage;
        } else {
            int prefixIndex = rawMessage.indexOf(senderPrefix);
            int suffixIndex = rawMessage.indexOf(senderSuffix);
            if (prefixIndex == -1 || suffixIndex == -1 || suffixIndex < prefixIndex) return;
            senderName = rawMessage.substring(prefixIndex + senderPrefix.length(), suffixIndex);
            if (senderName.equals(mc.player.getName().getString())) return;
            content = rawMessage.substring(suffixIndex + senderSuffix.length());
        }
        if (senderName == null || senderName.isEmpty()) return;
        if (senderName.equals(mc.player.getName().getString())) return;

        String compact = content.replace(" ", "").replace("\n", "");
        if (compact.startsWith("hivemindaccept")) {
            handleInviteAccept(senderName);
            return;
        }
        String[] split = compact.split(":");
        if (split.length >= 3 && split[0].equals("hivemind")) {
            handleInvite(senderName, split[1], split[2]);
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

        // Bulk payload commands (JSON setup broadcast / base64 NBT map transfer)
        // are only meaningful from the master to a slave.
        if (sender.equals(master)) {
            if (content.startsWith("config:")) {
                printerModule.applySetup(content.substring("config:".length()));
                return;
            }
            if (content.startsWith("map:")) {
                printerModule.applyMapData(content.substring("map:".length()));
                return;
            }
        }

        String[] colonSplit = content.replace(" ", "").split(":");
        String command = colonSplit[0];
        // Register (received by a slave from the master; the socket connection
        // itself proves the master is reachable, so no render distance check)
        if (command.equals("register") && master == null && toBeConfirmedSlaves.isEmpty()
            && slaves.isEmpty()) {
            master = sender;
            SlaveSystem.queueMasterDM("accept");
            // Bootstrap confirmation back over the server DM channel
            toBeSentInvites.add(directMessageCommand + " " + master + " hivemindaccept");
        }
        // Master to Client message
        if (sender.equals(master)) {
            switch (command) {
                case "interval":
                    if (colonSplit.length < 3) break;
                    Pair<Integer, Integer> interval = new Pair<>(Integer.valueOf(colonSplit[1]), Integer.valueOf(colonSplit[2]));
                    ChatUtils.info("Received rows " + colonSplit[1] + "-" + colonSplit[2] + " from master.");
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
                case "remap":
                    // Slave's map transfer was incomplete/corrupt - re-send it
                    printerModule.resendMap(sender);
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
                    if (slaves.contains(sender)) {
                        // Idempotent: a reconnecting slave may re-accept; adding it
                        // twice would duplicate rows and corrupt interval assignment
                        ChatUtils.info("Ignored duplicate registration from: " + sender);
                        toBeConfirmedSlaves.remove(sender);
                        break;
                    }
                    slaves.add(sender);
                    finishedSlavesDict.put(sender, false);
                    activeSlavesDict.put(sender, false);
                    toBeConfirmedSlaves.remove(sender);
                    ChatUtils.info("Registered slave: " + sender + " Total slaves: " + slaves.size());
                    HiveLog.log("REGISTER " + sender + " (total slaves: " + slaves.size() + ")");
                    generateIntervals();
                    printerModule.slaveRegistered(sender);
                    if (tableController != null) tableController.rebuild();
                    break;
                case "finished":
                    finishedSlavesDict.put(sender, true);
                    activeSlavesDict.put(sender, false);
                    HiveLog.log("FINISHED " + sender + " (finished: "
                        + Collections.frequency(finishedSlavesDict.values(), true) + "/" + finishedSlavesDict.size() + ")");
                    printerModule.slaveFinished(sender);
                    if (tableController != null) tableController.rebuild();
                    break;
                case "error":
                    if (colonSplit.length < 3) break;
                    BlockPos relativeErrorPos = new BlockPos(Integer.valueOf(colonSplit[1]), 0, Integer.valueOf(colonSplit[2]));
                    HiveLog.log("ERROR " + sender + " reported failed block at rel " + relativeErrorPos.getX() + "," + relativeErrorPos.getZ());
                    printerModule.addError(relativeErrorPos);
                    break;
            }
        }
    }

    @EventHandler
    private static void onReceivePacket(PacketEvent.Receive event) {
        if (printerModule == null) return;

        // Bootstrap invite detection (one-time DM transport)
        if (event.packet instanceof ChatMessageS2CPacket packet) {
            handleBootstrapMessage(packet.body().content(), packet.serializedParameters().name().getString());
        }
        if (event.packet instanceof GameMessageS2CPacket packet) {
            handleBootstrapMessage(packet.content().getString(), null);
        }
    }

    @EventHandler
    private static void onTick(TickEvent.Pre event) {
        if (printerModule == null) return;
        if (mc.getNetworkHandler() == null) return;

        // Send queued hivemind invites, spaced out to survive anti-spam plugins
        if (!toBeSentInvites.isEmpty()) {
            if (inviteTimer > 0) inviteTimer--;
            if (inviteTimer == 0) {
                String message = toBeSentInvites.remove(0);
                mc.getNetworkHandler().sendChatCommand(message);
                inviteTimer = 40;
            }
        }

        // Slave side: reconnect to the master when the connection drops
        if (!isMasterMode()) {
            if (reconnectTimer > 0) reconnectTimer--;
            if (reconnectTimer == 0 && (client == null || client.isClosed())) {
                ensureClient();
            }
        } else {
            // Master side: watchdog - retry the server bind every 5s until it succeeds
            // (covers bind failures from port conflicts and zombie sockets after crashes)
            if (!serverStarted) {
                if (serverRetryTimer > 0) serverRetryTimer--;
                if (serverRetryTimer == 0) {
                    ensureServer();
                    serverRetryTimer = 100;
                }
            }
        }
    }
}

