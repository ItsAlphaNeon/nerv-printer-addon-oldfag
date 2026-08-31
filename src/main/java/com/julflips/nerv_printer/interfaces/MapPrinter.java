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

    void goToCorner(int cornerIndex);

    void onIntervalsReassigned();

    boolean isFinalizePhase();
}
