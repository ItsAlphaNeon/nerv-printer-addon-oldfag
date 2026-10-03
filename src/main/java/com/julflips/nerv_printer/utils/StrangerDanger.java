package com.julflips.nerv_printer.utils;

import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Pair;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Joke feature: when a player outside the hive is spotted by any bot, the whole hive stops,
 * stares at them for a minute, then crouch-walks towards them, stops 5 blocks away and keeps
 * staring. After 3 minutes in total everyone goes back to work and that player is ignored
 * for the rest of the session. If the player leaves the range of all bots, the sequence is
 * aborted and everyone goes back to work like nothing happened.
 *
 * The master coordinates (sighting aggregation, phase timing); every bot (master included)
 * executes the behavior via {@link #tickBot(boolean)}.
 *
 * Wire format (socket):
 *   master -> slave: stare:mode:<0|1>, stare:start:<name>:<x>:<y>:<z>, stare:pos:<x>:<y>:<z>,
 *                    stare:approach, stare:end
 *   slave -> master: seen:<name>,<x>,<y>,<z>;<name>,<x>,<y>,<z>;...
 */
public final class StrangerDanger {

    private static final long STARE_MS = 60_000;          // Phase 1: stand still and stare
    private static final long TOTAL_MS = 180_000;         // Whole sequence, then ignore the player
    private static final long LOST_MS = 2_000;            // No bot saw the target for this long -> abort
    private static final long RETURN_TIMEOUT_MS = 30_000; // Give up walking back and just resume
    private static final double APPROACH_DISTANCE = 5.0;
    private static final double RETURN_BUFFER = 0.3;
    private static final double EYE_HEIGHT = 1.5;
    private static final int REPORT_INTERVAL = 10;        // Ticks between sighting reports / position updates

    // Master: coordination
    private static boolean enabled = false;
    private static final HashSet<String> ignoredPlayers = new HashSet<>();
    private static final HashMap<String, Pair<Vec3d, Long>> sightings = new HashMap<>(); // name -> (pos, last seen)
    private static String target = null;
    private static long startTime = 0;
    private static boolean approachSent = false;
    private static int tickCounter = 0;

    // Slave: whether the master wants sighting reports
    private static boolean reporting = false;

    // Bot: local behavior (master and slaves)
    private static String botTarget = null;
    private static Vec3d botTargetPos = null;
    private static boolean botApproach = false;
    private static boolean engaged = false;           // Currently controlling the player
    private static Vec3d returnPos = null;
    private static float returnYaw, returnPitch;
    private static long returnStartTime = 0;

    // ------------------------------------------------------------------
    // Master side
    // ------------------------------------------------------------------

    public static void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        if (!value && target != null) {
            target = null;
            broadcast("stare:end");
        }
        sightings.clear();
        SlaveSystem.sendToAllSlaves("stare:mode:" + (value ? 1 : 0));
    }

    /** Master side: tell a freshly registered slave whether to report sightings. */
    public static void onSlaveRegistered(String slave) {
        SlaveSystem.queueDM(slave, "stare:mode:" + (enabled ? 1 : 0));
    }

    /** Master side: a slave reported the players it can currently see. */
    public static void onSightingReport(String report) {
        if (!enabled || report.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (String entry : report.split(";")) {
            String[] parts = entry.split(",");
            if (parts.length < 4) continue;
            try {
                Vec3d pos = new Vec3d(Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]));
                sightings.put(parts[0], new Pair<>(pos, now));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private static void tickMaster() {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        for (Pair<String, Vec3d> seen : getVisiblePlayers()) {
            sightings.put(seen.getLeft(), new Pair<>(seen.getRight(), now));
        }
        sightings.values().removeIf(sighting -> now - sighting.getRight() > LOST_MS);

        if (target == null) {
            for (String name : sightings.keySet()) {
                if (SlaveSystem.isHiveMember(name) || ignoredPlayers.contains(name)) continue;
                target = name;
                startTime = now;
                approachSent = false;
                ChatUtils.info("§cStranger danger:§7 " + name + " spotted. The hive is staring...");
                broadcast("stare:start:" + name + ":" + formatPos(sightings.get(name).getLeft()));
                return;
            }
            return;
        }

        Pair<Vec3d, Long> sighting = sightings.get(target);
        if (sighting == null) {
            ChatUtils.info("§cStranger danger:§7 " + target + " left the range. Back to work, nothing happened.");
            target = null;
            broadcast("stare:end");
            return;
        }
        long elapsed = now - startTime;
        if (elapsed >= TOTAL_MS) {
            ChatUtils.info("§cStranger danger:§7 done staring. " + target + " is ignored for this session.");
            ignoredPlayers.add(target);
            target = null;
            broadcast("stare:end");
            return;
        }
        if (!approachSent && elapsed >= STARE_MS) {
            approachSent = true;
            ChatUtils.info("§cStranger danger:§7 approaching " + target + "...");
            broadcast("stare:approach");
        }
        if (tickCounter % REPORT_INTERVAL == 0) broadcast("stare:pos:" + formatPos(sighting.getLeft()));
    }

    /** Sends a command to every slave and applies it to the master's own bot. */
    private static void broadcast(String command) {
        SlaveSystem.sendToAllSlaves(command);
        onMasterCommand(command);
    }

    // ------------------------------------------------------------------
    // Slave side
    // ------------------------------------------------------------------

    private static void tickSlave() {
        if (!reporting || !SlaveSystem.isSlave() || tickCounter % REPORT_INTERVAL != 0) return;
        StringBuilder report = new StringBuilder();
        for (Pair<String, Vec3d> seen : getVisiblePlayers()) {
            if (report.length() > 0) report.append(';');
            Vec3d pos = seen.getRight();
            report.append(seen.getLeft()).append(',').append(formatPos(pos).replace(':', ','));
        }
        SlaveSystem.queueMasterDM("seen:" + report);
    }

    /** Applies a stare command from the master (or from the master's own coordinator). */
    public static void onMasterCommand(String content) {
        String[] split = content.split(":");
        if (split.length < 2) return;
        try {
            switch (split[1]) {
                case "mode":
                    if (split.length < 3) return;
                    reporting = split[2].equals("1");
                    if (!reporting) endBotStare();
                    break;
                case "start":
                    if (split.length < 6) return;
                    botTarget = split[2];
                    botTargetPos = parsePos(split, 3);
                    botApproach = false;
                    break;
                case "pos":
                    if (split.length < 5 || botTarget == null) return;
                    botTargetPos = parsePos(split, 2);
                    break;
                case "approach":
                    if (botTarget != null) botApproach = true;
                    break;
                case "end":
                    endBotStare();
                    break;
            }
        } catch (NumberFormatException ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Bot behavior (master and slaves)
    // ------------------------------------------------------------------

    /**
     * Drives the local bot while a stare is running. Must be called every tick by the printer.
     *
     * @param safeToFreeze whether the printer is at a point where it can be frozen for minutes
     *                     (not inside a chest interaction, button press or wipe sequence)
     * @return true if the stare controls the player this tick and the printer must not run
     */
    public static boolean tickBot(boolean safeToFreeze) {
        if (mc.player == null) return false;
        if (botTarget != null && !engaged) {
            // Join the stare as soon as the printer reaches a safe point
            if (!safeToFreeze) return false;
            engaged = true;
            returnPos = mc.player.getEntityPos();
            returnYaw = mc.player.getYaw();
            returnPitch = mc.player.getPitch();
            releaseKeys();
        }
        if (!engaged) return false;
        mc.player.setSprinting(false);

        if (botTarget != null) {
            Vec3d eyes = botTargetPos.add(0, EYE_HEIGHT, 0);
            mc.player.setYaw((float) Rotations.getYaw(eyes));
            mc.player.setPitch((float) Rotations.getPitch(eyes));
            if (botApproach) {
                boolean tooFar = horizontalDistance(botTargetPos) > APPROACH_DISTANCE;
                mc.options.sneakKey.setPressed(true);
                Utils.setForwardPressed(tooFar);
                Utils.setJumpPressed(tooFar && mc.player.horizontalCollision);
            } else {
                Utils.setForwardPressed(false);
            }
            return true;
        }

        // Stare is over: walk back to where the printer was interrupted, then resume
        mc.options.sneakKey.setPressed(false);
        if (horizontalDistance(returnPos) > RETURN_BUFFER
            && System.currentTimeMillis() - returnStartTime < RETURN_TIMEOUT_MS) {
            mc.player.setYaw((float) Rotations.getYaw(returnPos));
            mc.player.setPitch(0);
            Utils.setForwardPressed(true);
            Utils.setJumpPressed(mc.player.horizontalCollision);
            return true;
        }
        releaseKeys();
        mc.player.setYaw(returnYaw);
        mc.player.setPitch(returnPitch);
        engaged = false;
        returnPos = null;
        return false;
    }

    private static void endBotStare() {
        botTarget = null;
        botTargetPos = null;
        botApproach = false;
        returnStartTime = System.currentTimeMillis();
        mc.options.sneakKey.setPressed(false);
    }

    /** Drops any running stare immediately (module disabled / connection lost). */
    public static void resetBot() {
        boolean wasEngaged = engaged;
        endBotStare();
        engaged = false;
        returnPos = null;
        if (wasEngaged) releaseKeys();
    }

    /** Slave side: the master connection is gone - stop reporting and abort any stare. */
    public static void onMasterLost() {
        reporting = false;
        endBotStare();
    }

    // ------------------------------------------------------------------
    // Shared
    // ------------------------------------------------------------------

    /** True while a stare is running or this bot is still walking back - hive watchdogs should pause. */
    public static boolean isHiveFrozen() {
        return target != null || botTarget != null || engaged;
    }

    /** Called every tick by {@link SlaveSystem}. */
    public static void tick() {
        if (mc.player == null || mc.world == null) return;
        tickCounter++;
        if (SlaveSystem.isMasterMode()) {
            tickMaster();
        } else {
            tickSlave();
        }
    }

    private static ArrayList<Pair<String, Vec3d>> getVisiblePlayers() {
        ArrayList<Pair<String, Vec3d>> players = new ArrayList<>();
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof PlayerEntity player && !mc.player.equals(player)) {
                players.add(new Pair<>(player.getName().getString(), player.getEntityPos()));
            }
        }
        return players;
    }

    private static void releaseKeys() {
        Utils.setForwardPressed(false);
        Utils.setBackwardPressed(false);
        Utils.setJumpPressed(false);
        mc.options.sneakKey.setPressed(false);
    }

    private static double horizontalDistance(Vec3d pos) {
        double dx = pos.x - mc.player.getX();
        double dz = pos.z - mc.player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static String formatPos(Vec3d pos) {
        return String.format(Locale.ROOT, "%.2f:%.2f:%.2f", pos.x, pos.y, pos.z);
    }

    private static Vec3d parsePos(String[] split, int index) {
        return new Vec3d(Double.parseDouble(split[index]), Double.parseDouble(split[index + 1]), Double.parseDouble(split[index + 2]));
    }
}
