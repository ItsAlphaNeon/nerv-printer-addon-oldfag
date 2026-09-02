package com.julflips.nerv_printer.interfaces;

import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;

public interface MapPrinter {

    void setInterval(Pair<Integer, Integer> interval);

    void mineLine(int minedLines);

    void addError(BlockPos relativeBlockPos);

    void pause();

    void start();

    boolean isActive();

    void toggle();

    boolean getActivationReset();

    void skipBuilding();

    void slaveFinished(String slave);

    // Hivemind extensions (WebSocket transport)

    void broadcastSetup();

    void slaveRegistered(String slave);

    void applySetup(String json);

    void applyMapData(String message);

    void resendMap(String slave);

    // Efficiency: workload balancing

    /**
     * Master-side: block count for every map row (index 0-127), or null when no
     * map is loaded. Used to split rows by workload instead of by row count.
     */
    int[] getRowBlocks();

    /** Master-side: a slave reported how many unfinished rows its interval still has. */
    void onSlaveProgress(String slave, int unfinishedRows);

    /**
     * Diagnostic suffix for interval logs explaining the AFK-anchor decision
     * (why the master did or did not take the duper-adjacent rows).
     */
    default String afkAnchorStatus() {
        return "";
    }

    /** True when AFK-anchor mode is active, so the master should be
     * assigned the first (duper-adjacent) row section instead of the middle one.
     */
    boolean usesAfkAnchorRows();

    /**
     * Slave-side: the master delegated finalize (dump, cartography, finished-map
     * chest and wipe) to this slave because the master is anchoring the dupers.
     */
    void runFinalize();

    /**
     * Slave-side: verify the whole canvas before the finalize runs - walk the
     * four quadrant centers (loads every chunk), then scan every cell against
     * the map and repair what is wrong. Reports verifyDone:&lt;remaining&gt;.
     */
    void runVerify();

    /** Master-side: the delegated slave completed the finalize - load the next map. */
    void finalizeComplete();

    /** Master-side: the verifying slave finished its pre-finalize canvas scan.
     * {@code remaining} is the number of unresolved issues (0 = clean).
     */
    void verifyDone(String slave, int remaining);

    /**
     * Slave-side: this bot accepted a hivemind invite via whisper. Leave the
     * master setup flow (it is otherwise stuck in the chest-selection states)
     * and wait for the master's setup broadcast.
     */
    void onInviteAccepted();

    void goToCorner(int cornerIndex);

    void onIntervalsReassigned();

    boolean isFinalizePhase();

    /**
     * Compact state heartbeat for protocol reconciliation:
     * "phase,intervalStart,intervalEnd,unfinishedRows,errorCount" or null when
     * the module cannot report (no map loaded etc). Phase is one of
     * BUILDING / ANCHORING / FINALIZING / PARKED / IDLE.
     */
    String getHeartbeatData();
}
