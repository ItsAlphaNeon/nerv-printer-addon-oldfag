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

    void applyMapData(String fileName, String base64);

    void goToCorner(int cornerIndex);

    void onIntervalsReassigned();

    boolean isFinalizePhase();
}
