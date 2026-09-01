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
    /** Row interval currently assigned to each slave (for work stealing). */
    public static final HashMap<String, Pair<Integer, Integer>> slaveIntervals = new HashMap<>();
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
            slaveHeartbeats.clear();
            hbStaleWarned.clear();
            pendingAcks.clear();
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
                // Invite reachability: a virtual adapter advertised here is the #1
                // "slaves can connect locally but not from other PCs" cause.
                SlaveSystem.LocalIp advertised = SlaveSystem.resolveAdvertisedIp();
                if (advertised.address.equals("127.0.0.1")) {
                    ChatUtils.warning("  Invite IP: auto-detect failed - invites would advertise 127.0.0.1."
                        + " Set the advertised-ip setting to this PC's real LAN IP.");
                } else {
                    ChatUtils.info("  Invite IP: " + advertised.address + " (" + advertised.nicName + ")"
                        + (SlaveSystem.advertisedIpOverride.trim().isEmpty() ? " - auto-detected" : " - manual override"));
                }
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
                slaveIntervals.remove(name);
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
        mc.execute(() -> {
            ChatUtils.warning("No connection detected to master " + masterAddress.trim() + ":"
                + masterPort + (reason == null || reason.isEmpty() ? "" : " (" + reason + ")") + " - retrying in 5s.");
            // Repeated failures are almost always a wrong advertised IP (e.g. the
            // master's virtual adapter) or a firewall block - make that obvious.
            if (connectAttempts >= 3 && connectAttempts % 3 == 0) {
                ChatUtils.warning("Still failing after " + connectAttempts + " attempts: the master may be advertising"
                    + " a wrong IP (virtual adapter) or port " + masterPort + " is blocked by a firewall."
                    + " Ask the master to check its Hivemind Status / advertised-ip setting.");
            }
        });
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
        // While the master is parked as the duper anchor it owns NO rows - any
        // re-split (slave join/disconnect) must keep it that way. Assigning the
        // parked master real rows would orphan them forever (it can never build
        // them, rowsHandedOff blocks a re-handoff and the wipe gate then holds).
        if (masterRowsHandedOff && !slaves.isEmpty()) {
            printerModule.setInterval(new Pair<>(0, -1));
            HiveLog.log("INTERVALS reassigned -> master: none (anchored)");
            repartitionAmongSlaves(null);
            return;
        }
        int botCount = slaves.size() + 1;
        // Work-weighted split: when the master has the map parsed, split rows 0-127
        // into contiguous sections with ~equal BLOCK counts (row counts alone are
        // wildly uneven on real maparts). Falls back to an equal-row split when no
        // map is loaded.
        int[] rowBlocks = printerModule.getRowBlocks();
        ArrayList<Pair<Integer, Integer>> intervals =
            (rowBlocks != null && rowBlocks.length == 128) ? weightedIntervals(rowBlocks, botCount) : equalIntervals(botCount);

        // AFK-anchor mode: the master builds the duper-adjacent rows so it ends up
        // right next to its parking spot; otherwise it takes the middle section.
        Pair<Integer, Integer> printerModuleInterval = intervals.remove(
            printerModule.usesAfkAnchorRows() ? 0 : (intervals.size() - 1) / 2);
        printerModule.setInterval(printerModuleInterval);

        // Sort slaves deterministically
        ArrayList<String> sortedSlaves = new ArrayList<>(slaves);
        Collections.sort(sortedSlaves, String.CASE_INSENSITIVE_ORDER);

        StringBuilder assignment = new StringBuilder("INTERVALS reassigned -> master: rows "
            + printerModuleInterval.getLeft() + "-" + printerModuleInterval.getRight()
            + (rowBlocks != null ? " (" + sumBlocks(rowBlocks, printerModuleInterval) + " blocks)" : ""));
        for (int i = 0; i < intervals.size(); i++) {
            String slave = sortedSlaves.get(i);
            Pair<Integer, Integer> interval = intervals.get(i);
            slaveIntervals.put(slave, interval);
            assignment.append(", ").append(slave).append(": rows ")
                .append(interval.getLeft()).append("-").append(interval.getRight())
                .append(rowBlocks != null ? " (" + sumBlocks(rowBlocks, interval) + " blocks)" : "");
            // Sequenced + ACKed: a lost interval command is retried instead of
            // leaving the slave on stale rows forever
            sendCommand(slave, HiveCommand.INTERVAL, interval.getLeft() + ":" + interval.getRight());
        }
        slaveIntervals.keySet().removeIf(s -> !slaves.contains(s));
        HiveLog.log(assignment.toString()
            + (printerModule.usesAfkAnchorRows() ? " [AFK-ANCHOR: master on duper-adjacent rows]" : " [no afk-anchor]"));

        // Hivemind: parked (already finished) slaves may have received new rows -
        // re-activate them so the re-split rows actually get built.
        printerModule.onIntervalsReassigned();
    }

    /**
     * True while the master handed ALL its rows to the slaves and is parked as
     * the duper anchor (AFK-anchor mode). Set by the printer module's handoff
     * and cleared at every map start; makes interval re-splits slaves-only.
     */
    public static boolean masterRowsHandedOff = false;

    /**
     * Re-partitions ALL rows 0-127 among the slaves only (the master keeps none).
     * Used when the master hands its rows off before going AFK. This REPLACES the
     * old union-merge, which created OVERLAPPING intervals (e.g. master rows 0-42
     * merged into slaves holding 43-85 and 86-127 produced 0-85 and 21-127 - rows
     * 21-85 double-owned, two bots building/repairing the same blocks). A partition
     * guarantees disjoint ownership; every bot re-scans its new range and builds
     * only the unfinished rows in it.
     *
     * @param exclude optional slave to leave OUT of the partition (its rows are
     *                distributed among the rest) - used by the stall watchdog so
     *                a wedged bot does not receive fresh work it cannot do.
     */
    public static void repartitionAmongSlaves() {
        repartitionAmongSlaves(null);
    }

    public static void repartitionAmongSlaves(String exclude) {
        ArrayList<String> participants = new ArrayList<>(slaves);
        if (exclude != null) participants.removeIf(s -> s.equals(exclude));
        if (participants.isEmpty()) return;
        int n = participants.size();
        int[] rowBlocks = printerModule.getRowBlocks();
        ArrayList<Pair<Integer, Integer>> intervals =
            (rowBlocks != null && rowBlocks.length == 128) ? weightedIntervals(rowBlocks, n) : equalIntervals(n);

        ArrayList<String> sortedSlaves = new ArrayList<>(participants);
        Collections.sort(sortedSlaves, String.CASE_INSENSITIVE_ORDER);
        StringBuilder log = new StringBuilder("HANDOFF re-partition -> master: none");
        for (int i = 0; i < n; i++) {
            String slave = sortedSlaves.get(i);
            Pair<Integer, Integer> interval = intervals.get(i);
            slaveIntervals.put(slave, interval);
            log.append(", ").append(slave).append(": rows ")
                .append(interval.getLeft()).append("-").append(interval.getRight());
            sendCommand(slave, HiveCommand.INTERVAL, interval.getLeft() + ":" + interval.getRight());
            sendCommand(slave, HiveCommand.START, null);
            finishedSlavesDict.put(slave, false);
            activeSlavesDict.put(slave, true);
        }
        // Disjointness sanity check - this must never fire, but if it does we need
        // to know immediately (double ownership = bots fighting over blocks)
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Pair<Integer, Integer> a = intervals.get(i), b = intervals.get(j);
                if (a.getLeft() <= b.getRight() && b.getLeft() <= a.getRight()) {
                    HiveLog.log("CRITICAL re-partition produced OVERLAP: "
                        + a.getLeft() + "-" + a.getRight() + " vs " + b.getLeft() + "-" + b.getRight());
                }
            }
        }
        HiveLog.log(log.toString());
        printerModule.onIntervalsReassigned();
    }

    /** Splits rows 0-127 into n contiguous sections with ~equal block counts. */
    private static ArrayList<Pair<Integer, Integer>> weightedIntervals(int[] rowBlocks, int n) {
        long total = 0;
        for (int b : rowBlocks) total += b;
        ArrayList<Pair<Integer, Integer>> out = new ArrayList<>();
        int start = 0;
        int section = 0;
        long cum = 0;
        for (int x = 0; x < 128; x++) {
            cum += rowBlocks[x];
            boolean last = x == 127;
            long target = Math.round((double) total * (section + 1) / n);
            // Cut a section when we reach its cumulative block target, but always
            // leave at least one row for every remaining section.
            if (!last && section < n - 1 && cum >= target && (127 - x) >= (n - 1 - section)) {
                out.add(new Pair<>(start, x));
                start = x + 1;
                section++;
            }
            if (last) out.add(new Pair<>(start, x));
        }
        return out;
    }

    /** Legacy equal-row split (fallback when no map is loaded). */
    private static ArrayList<Pair<Integer, Integer>> equalIntervals(int botCount) {
        int sectionSize = (int) Math.ceil((float) 128 / (float) botCount);
        ArrayList<Pair<Integer, Integer>> intervals = new ArrayList<>();
        for (int end = 127; end >= 0; end -= sectionSize) {
            int start = Math.max(0, end - sectionSize + 1);
            intervals.add(new Pair<>(start, end));
        }
        Collections.reverse(intervals);
        return intervals;
    }

    private static long sumBlocks(int[] rowBlocks, Pair<Integer, Integer> interval) {
        long sum = 0;
        for (int x = interval.getLeft(); x <= interval.getRight(); x++) sum += rowBlocks[x];
        return sum;
    }

    // ------------------------------------------------------------------
    // Bootstrap invite (server DM transport, discovery only)
    // ------------------------------------------------------------------

    /**
     * Master side: manual override for the IP advertised in invites. Empty =
     * auto-detect. Needed because auto-detection can pick a virtual adapter
     * (VirtualBox/Hyper-V/WSL/VPN) that other machines cannot reach - see
     * {@link #resolveLocalIpDetailed()}.
     */
    public static String advertisedIpOverride = "";

    /** Lowercase fragments that mark a NIC as virtual/tunnel - never advertise these. */
    private static final String[] VIRTUAL_NIC_HINTS = {
        "virtualbox", "vbox", "vmware", "vmnet", "hyper-v", "vethernet", "wsl",
        "docker", "tailscale", "zerotier", "hamachi", "nordvpn", "wireguard",
        "tunnel", "teredo", "isatap", "tap-", "tun-", "virtual", "loopback"
    };

    /** Resolved address + the interface name it came from (for diagnostics). */
    public static class LocalIp {
        public final String address;
        public final String nicName;

        LocalIp(String address, String nicName) {
            this.address = address;
            this.nicName = nicName;
        }
    }

    /**
     * Java's {@code NetworkInterface.isVirtual()} is UNRELIABLE - on Windows,
     * VirtualBox/Hyper-V adapters usually report {@code false}, which made the
     * master advertise its VirtualBox Host-Only IP (e.g. 192.168.56.1) in
     * invites. Slaves on other PCs (and some local network profiles) can never
     * reach that address, so they keep retrying forever. This resolver skips
     * known virtual/tunnel adapter names first; a suspect adapter is only used
     * as a last resort when nothing cleaner exists.
     */
    public static LocalIp resolveLocalIpDetailed() {
        LocalIp suspect = null;
        try {
            for (java.net.NetworkInterface nic : Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                boolean isSuspect = nic.isVirtual();
                String names = (nic.getName() + " " + nic.getDisplayName()).toLowerCase(java.util.Locale.ROOT);
                for (String hint : VIRTUAL_NIC_HINTS) {
                    if (names.contains(hint)) {
                        isSuspect = true;
                        break;
                    }
                }
                for (java.net.InetAddress addr : Collections.list(nic.getInetAddresses())) {
                    if (addr instanceof java.net.Inet4Address && addr.isSiteLocalAddress()) {
                        LocalIp candidate = new LocalIp(addr.getHostAddress(),
                            nic.getDisplayName() != null ? nic.getDisplayName() : nic.getName());
                        if (!isSuspect) return candidate;   // first clean adapter wins
                        if (suspect == null) suspect = candidate;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return suspect != null ? suspect : new LocalIp("127.0.0.1", "loopback");
    }

    /** Convenience wrapper - see {@link #resolveLocalIpDetailed()}. */
    public static String resolveLocalIp() {
        return resolveLocalIpDetailed().address;
    }

    /** The IP that invites advertise (manual override wins over auto-detection). */
    public static LocalIp resolveAdvertisedIp() {
        if (!advertisedIpOverride.trim().isEmpty()) {
            return new LocalIp(advertisedIpOverride.trim(), "manual override (advertised-ip)");
        }
        return resolveLocalIpDetailed();
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
        LocalIp advertised = resolveAdvertisedIp();
        String ip = advertised.address;
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
            // Diagnosability: a wrong (virtual) adapter here means slaves on
            // other PCs can never connect - make the choice visible immediately.
            ChatUtils.info("Advertised IP: §a" + ip + "§7 (interface: " + advertised.nicName
                + "). Slaves cannot connect? Set §2advertised-ip§7 to this PC's real LAN IP.");
            HiveLog.log("INVITE advertised " + ip + " (interface: " + advertised.nicName + ")");
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
        slaveIntervals.remove(slave);
        slaveHeartbeats.remove(slave);
        hbStaleWarned.remove(slave);
        toBeConfirmedSlaves.remove(slave);
        queueDM(slave, "remove");
        generateIntervals();
    }

    // ------------------------------------------------------------------
    // Message handling (command logic unchanged from the DM system)
    // ------------------------------------------------------------------

    // ACK + heartbeat bookkeeping (Phase 2 protocol hardening)
    private static int ackSeqCounter = 0;
    private static final HashMap<Integer, PendingAck> pendingAcks = new HashMap<>();
    /** name -> {lastHeartbeatMs, unfinishedRows, errorCount} */
    private static final HashMap<String, long[]> slaveHeartbeats = new HashMap<>();
    private static final ArrayList<String> hbStaleWarned = new ArrayList<>();
    private static int hbTimer = 0;

    private static class PendingAck {
        final String slave;
        final String command;
        final String wireMessage;
        int retries = 0;
        int waitTicks = 0;

        PendingAck(String slave, String command, String wireMessage) {
            this.slave = slave;
            this.command = command;
            this.wireMessage = wireMessage;
        }
    }

    /**
     * Sends a typed command. State-changing commands (needsAck) carry a sequence
     * number and are retried up to 3x by the tick watchdog until the receiver
     * ACKs them - a dropped command is now detected instead of silently lost.
     */
    public static void sendCommand(String recipient, HiveCommand cmd, String args) {
        if (printerModule == null) return;
        String payload = cmd.wire() + (args == null || args.isEmpty() ? "" : ":" + args);
        if (cmd.needsAck) {
            int seq = ++ackSeqCounter;
            pendingAcks.put(seq, new PendingAck(recipient, cmd.wire(), seq + ":" + payload));
            queueDM(recipient, seq + ":" + payload);
            HiveLog.log("CMD " + cmd.wire() + " -> " + recipient + " (seq " + seq + ")");
        } else {
            queueDM(recipient, payload);
        }
    }

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

        String[] t = content.replace(" ", "").split(":");
        if (t.length == 0) return;
        // Sequenced form: "<seq>:<command>[:args...]". Legacy commands never
        // start with a digit, so this is unambiguous.
        int seq = -1;
        int cmdIdx = 0;
        if (t.length >= 2 && t[0].matches("\\d+")) {
            try {
                seq = Integer.parseInt(t[0]);
                cmdIdx = 1;
            } catch (NumberFormatException ignored) {
            }
        }
        String command = t[cmdIdx];
        String[] args = new String[Math.max(0, t.length - cmdIdx - 1)];
        System.arraycopy(t, cmdIdx + 1, args, 0, args.length);

        // ACK clears the master's retry slot. The slave replies "ack:<seq>"
        // (and may also echo our "<seq>:ack" form) - accept both.
        if (command.equals("ack")) {
            int ackSeq = seq;
            if (ackSeq < 0 && args.length >= 1) {
                try {
                    ackSeq = Integer.parseInt(args[0]);
                } catch (NumberFormatException ignored) {
                }
            }
            if (ackSeq >= 0) {
                PendingAck p = pendingAcks.remove(ackSeq);
                if (p != null) HiveLog.log("ACK " + p.command + " <- " + sender + " (seq " + ackSeq + ")");
            }
            return;
        }

        HiveCommand cmd = HiveCommand.parseCompat(command);
        if (cmd == null) {
            HiveLog.log("WARN unknown command '" + command + "' from " + sender + " - dropped");
            return;
        }

        // Register (received by a slave from the master; the socket connection
        // itself proves the master is reachable, so no render distance check)
        if (cmd == HiveCommand.REGISTER && master == null && toBeConfirmedSlaves.isEmpty()
            && slaves.isEmpty()) {
            master = sender;
            queueMasterDM("accept");
            // Bootstrap confirmation back over the server DM channel
            toBeSentInvites.add(directMessageCommand + " " + master + " hivemindaccept");
        }

        // Direction-safe dispatch: a TO_SLAVE command is only applied by a slave
        // (sender must be the master) and vice versa - the wrong-side handler bug
        // class is now structurally impossible.
        switch (cmd.direction) {
            case TO_SLAVE:
                if (sender.equals(master)) applySlaveCommand(cmd, seq, args);
                break;
            case TO_MASTER:
                if (slaves.contains(sender) || toBeConfirmedSlaves.contains(sender)) applyMasterCommand(cmd, sender, args);
                break;
            default:
                break;
        }
    }

    /** Slave side: apply a command from the master, then ACK it if sequenced. */
    private static void applySlaveCommand(HiveCommand cmd, int seq, String[] args) {
        boolean applied = true;
        switch (cmd) {
            case INTERVAL:
                if (args.length < 2) { applied = false; break; }
                try {
                    Pair<Integer, Integer> interval = new Pair<>(Integer.valueOf(args[0]), Integer.valueOf(args[1]));
                    ChatUtils.info("Received rows " + args[0] + "-" + args[1] + " from master.");
                    printerModule.setInterval(interval);
                } catch (NumberFormatException e) {
                    applied = false;
                }
                break;
            case PAUSE:
                printerModule.pause();
                break;
            case START:
                printerModule.start();
                break;
            case REMOVE:
                master = null;
                printerModule.toggle();
                break;
            case SKIP:
                printerModule.skipBuilding();
                break;
            case FINALIZE:
                printerModule.runFinalize();
                break;
            case VERIFY:
                printerModule.runVerify();
                break;
            case GO_TO_CORNER:
                if (args.length < 1) { applied = false; break; }
                try {
                    printerModule.goToCorner(Integer.valueOf(args[0]));
                } catch (NumberFormatException e) {
                    applied = false;
                }
                break;
            case MINE:
                if (args.length < 1) { applied = false; break; }
                try {
                    printerModule.mineLine(Integer.valueOf(args[0]));
                } catch (NumberFormatException e) {
                    applied = false;
                }
                break;
            default:
                applied = false;
                break;
        }
        if (applied && seq >= 0) queueMasterDM("ack:" + seq);
    }

    // Client to Master message
    /** Master side: apply a report from a slave. */
    private static void applyMasterCommand(HiveCommand cmd, String sender, String[] args) {
        switch (cmd) {
            case ACCEPT:
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
            case FINISHED:
                // Idempotent: a repeated finished from an already-finished slave
                // must not re-park it (this caused the corner-parking spam)
                if (Boolean.TRUE.equals(finishedSlavesDict.get(sender))) {
                    HiveLog.log("FINISHED duplicate from " + sender + " - ignored");
                    break;
                }
                finishedSlavesDict.put(sender, true);
                activeSlavesDict.put(sender, false);
                HiveLog.log("FINISHED " + sender + " (finished: "
                    + Collections.frequency(finishedSlavesDict.values(), true) + "/" + finishedSlavesDict.size() + ")");
                printerModule.slaveFinished(sender);
                if (tableController != null) tableController.rebuild();
                break;
            case ERROR:
                if (args.length < 2) break;
                try {
                    BlockPos relativeErrorPos = new BlockPos(Integer.valueOf(args[0]), 0, Integer.valueOf(args[1]));
                    HiveLog.log("ERROR " + sender + " reported failed block at rel " + relativeErrorPos.getX() + "," + relativeErrorPos.getZ());
                    printerModule.addError(relativeErrorPos);
                } catch (NumberFormatException ignored) {
                }
                break;
            case REMAP:
                // Slave's map transfer was incomplete/corrupt - re-send it
                HiveLog.log("REMAP requested by " + sender);
                printerModule.resendMap(sender);
                break;
            case FINALIZE_DONE:
                // Delegated slave finished dump/cartography/wipe - load the next map
                HiveLog.log("FINALIZE complete by " + sender + " - loading next map");
                printerModule.finalizeComplete();
                break;
            case PROGRESS:
                if (args.length < 1) break;
                try {
                    printerModule.onSlaveProgress(sender, Integer.valueOf(args[0]));
                } catch (NumberFormatException ignored) {
                }
                break;
            case HEARTBEAT:
                handleHeartbeat(sender, args);
                break;
            case VERIFY_DONE:
                // Pre-finalize canvas verification finished; args[0] = remaining issue count
                int remaining = 0;
                if (args.length >= 1) {
                    try {
                        remaining = Integer.parseInt(args[0]);
                    } catch (NumberFormatException ignored) {
                    }
                }
                HiveLog.log("VERIFY done by " + sender + " - " + remaining + " issue(s) remain");
                printerModule.verifyDone(sender, remaining);
                break;
            case MAP_FAILED:
                // The slave gave up receiving the map - it can never build its rows.
                // Drop it and re-split so its rows go to bots that can actually work.
                HiveLog.log("MAP FAILED by " + sender + " - dropping from the hive and re-splitting");
                ChatUtils.warning("Slave " + sender + " could not receive the map after repeated attempts - removing it from the hive.");
                if (slaves.contains(sender)) removeSlave(sender);
                break;
            case LEAVING:
                // Slave's module was disabled locally - unregister WITHOUT sending
                // "remove" back (its module is off; "remove" would re-activate it).
                if (slaves.contains(sender)) {
                    slaves.removeIf(n -> n.equals(sender));
                    activeSlavesDict.remove(sender);
                    finishedSlavesDict.remove(sender);
                    slaveIntervals.remove(sender);
                    slaveHeartbeats.remove(sender);
                    hbStaleWarned.remove(sender);
                    HiveLog.log("LEAVING " + sender + " (slaves left: " + slaves.size() + ")");
                    ChatUtils.info("Slave " + sender + " left the hivemind (module disabled) - rows re-split.");
                    generateIntervals();
                    if (tableController != null) tableController.rebuild();
                }
                break;
            default:
                break;
        }
    }

    /** True when the slave reported a heartbeat within the last 60 seconds. */
    public static boolean hasFreshHeartbeat(String slave) {
        long[] h = slaveHeartbeats.get(slave);
        return h != null && System.currentTimeMillis() - h[0] < 60000;
    }

    /** Master side: record a slave's state heartbeat and correct assignment drift. */
    private static void handleHeartbeat(String sender, String[] args) {
        if (args.length < 1) return;
        // hb:<phase>,<start>,<end>,<unfinished>,<errors>
        String[] f = args[0].split(",");
        if (f.length < 5) return;
        try {
            long now = System.currentTimeMillis();
            slaveHeartbeats.put(sender, new long[]{now, Long.parseLong(f[3]), Long.parseLong(f[4])});
            hbStaleWarned.remove(sender);
            // Feed the work-stealing estimates with real verified data
            printerModule.onSlaveProgress(sender, Integer.parseInt(f[3]));
            // Drift correction: the slave's interval must match the master's intent.
            // This makes handoff/re-split disagreement self-healing instead of fatal.
            Pair<Integer, Integer> intended = slaveIntervals.get(sender);
            int s = Integer.parseInt(f[1]);
            int e = Integer.parseInt(f[2]);
            if (intended != null && (intended.getLeft() != s || intended.getRight() != e)) {
                HiveLog.log("HEARTBEAT drift " + sender + ": has " + s + "-" + e + ", intended "
                    + intended.getLeft() + "-" + intended.getRight() + " - re-sending interval");
                sendCommand(sender, HiveCommand.INTERVAL, intended.getLeft() + ":" + intended.getRight());
            }
        } catch (NumberFormatException ignored) {
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
            // ACK watchdog: state-changing commands must be acknowledged within 2s
            // or they are retried (3 attempts max), making command loss visible.
            if (!pendingAcks.isEmpty()) {
                ArrayList<Integer> finished = new ArrayList<>();
                for (Map.Entry<Integer, PendingAck> entry : pendingAcks.entrySet()) {
                    PendingAck p = entry.getValue();
                    if (++p.waitTicks < 40) continue;
                    p.waitTicks = 0;
                    p.retries++;
                    if (p.retries >= 3) {
                        HiveLog.log("CMD " + p.command + " to " + p.slave + " FAILED after 3 attempts (no ACK)");
                        ChatUtils.warning("Command " + p.command + " to " + p.slave + " failed after 3 attempts.");
                        finished.add(entry.getKey());
                    } else {
                        HiveLog.log("CMD " + p.command + " to " + p.slave + " not acked - retry " + (p.retries + 1) + "/3");
                        queueDM(p.slave, p.wireMessage);
                    }
                }
                for (Integer key : finished) pendingAcks.remove(key);
            }
        }

        // Heartbeats every 10s: slaves report state; master checks staleness.
        if (++hbTimer >= 200) {
            hbTimer = 0;
            if (isMasterMode()) {
                String hb = printerModule.getHeartbeatData();
                if (hb != null) HiveLog.log("HEARTBEAT master: " + hb.substring(3));
                long now = System.currentTimeMillis();
                for (String slave : slaves) {
                    long[] h = slaveHeartbeats.get(slave);
                    if (h != null && now - h[0] > 30000 && !hbStaleWarned.contains(slave)) {
                        hbStaleWarned.add(slave);
                        HiveLog.log("HEARTBEAT MISSING from " + slave + " (last " + ((now - h[0]) / 1000) + "s ago) - slave may be stuck");
                        ChatUtils.warning("No heartbeat from slave " + slave + " for " + ((now - h[0]) / 1000) + "s.");
                    }
                }
            } else if (master != null) {
                String hb = printerModule.getHeartbeatData();
                if (hb != null) queueMasterDM(hb);
            }
        }
    }
}

