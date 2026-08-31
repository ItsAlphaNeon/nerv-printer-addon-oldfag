package com.julflips.nerv_printer.utils;

import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Master-side hivemind diagnostic log.
 *
 * Every coordination event on the master (commands sent to slaves, slave
 * responses, registrations, disconnects, interval assignments, phase changes)
 * is appended to a single timestamped log file under
 * <game dir>/nerv_printer/hive_logs/ so a whole benchmark run can be handed
 * over for troubleshooting without describing it manually.
 *
 * All methods are safe to call from any thread (WebSocket threads included);
 * writes are synchronized and flushed per line so nothing is lost on a crash.
 */
public final class HiveLog {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    /** Incoming/outgoing payloads above this size are truncated (map/config transfers can be hundreds of KB). */
    private static final int MAX_PAYLOAD = 160;

    private static BufferedWriter writer = null;
    private static Path logFile = null;
    private static boolean announcedPath = false;
    /**
     * Logging only becomes active once a slave actually connects - a plain
     * single-user master (no hivemind configured) must not create log files.
     */
    private static volatile boolean enabled = false;

    private HiveLog() {
    }

    /** Called by SlaveSystem when a slave socket connection appears. */
    public static void enable() {
        enabled = true;
    }

    /** True when this client is an active hivemind master with slave connections. */
    private static boolean shouldLog() {
        return enabled && SlaveSystem.isHiveActive() && SlaveSystem.isMasterMode();
    }

    /** Logs a master-side event. No-op on slaves and when no printer module is active. */
    public static synchronized void log(String event) {
        if (!shouldLog()) return;
        try {
            ensureOpen();
            writer.write("[" + TIME.format(LocalDateTime.now()) + "] " + event);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            // Logging must never break the printer - drop the line silently.
        }
    }

    /** Logs a wire message. Direction is "IN" (from a slave) or "OUT" (to a slave). */
    public static synchronized void logMessage(String direction, String peer, String content) {
        log(direction + " " + peer + ": " + truncate(content));
    }

    private static String truncate(String content) {
        if (content == null) return "";
        if (content.length() <= MAX_PAYLOAD) return content.replace("\n", " ");
        return content.substring(0, MAX_PAYLOAD).replace("\n", " ")
            + " ...[truncated " + (content.length() - MAX_PAYLOAD) + " chars]";
    }

    private static void ensureOpen() throws IOException {
        if (writer != null) return;
        Path dir = FabricLoader.getInstance().getGameDir().resolve("nerv-printer").resolve("hive_logs");
        Files.createDirectories(dir);
        logFile = dir.resolve("hive-master-" + FILE_STAMP.format(LocalDateTime.now()) + ".log");
        writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8);
        writer.write("# Nerv Printer hivemind log started " + LocalDateTime.now());
        writer.newLine();
        writer.flush();
        if (!announcedPath) {
            announcedPath = true;
            ChatUtils.info("Hivemind log: " + logFile.toAbsolutePath());
        }
    }
}
