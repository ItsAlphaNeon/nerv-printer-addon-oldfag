package com.julflips.nerv_printer.utils;

/**
 * Typed hivemind protocol commands. Every command declares which side may
 * RECEIVE it - dispatch routes on this, so a handler can never be added to
 * the wrong switch again (the class of bug that killed the remap/progress
 * commands silently).
 *
 * Wire format stays compatible: (s|m):<sender>:<command>[:args]
 * State-changing commands (needsAck) additionally carry a sequence number
 * once ACK support is active: (s|m):<sender>:<seq>:<COMMAND>[:args]
 */
public enum HiveCommand {
    // ---- Master -> slave ----
    REGISTER(Direction.TO_SLAVE, false),
    INTERVAL(Direction.TO_SLAVE, true),
    START(Direction.TO_SLAVE, true),
    PAUSE(Direction.TO_SLAVE, true),
    REMOVE(Direction.TO_SLAVE, false),
    SKIP(Direction.TO_SLAVE, false),
    FINALIZE(Direction.TO_SLAVE, true),
    MINE(Direction.TO_SLAVE, false),
    GO_TO_CORNER(Direction.TO_SLAVE, true),
    VERIFY(Direction.TO_SLAVE, true),

    // ---- Slave -> master ----
    ACCEPT(Direction.TO_MASTER, false),
    FINISHED(Direction.TO_MASTER, false),
    ERROR(Direction.TO_MASTER, false),
    REMAP(Direction.TO_MASTER, false),
    FINALIZE_DONE(Direction.TO_MASTER, false),
    PROGRESS(Direction.TO_MASTER, false),
    HEARTBEAT(Direction.TO_MASTER, false),
    VERIFY_DONE(Direction.TO_MASTER, false),
    MAP_FAILED(Direction.TO_MASTER, false),
    LEAVING(Direction.TO_MASTER, false),

    // ---- Either direction (used by the ACK layer itself) ----
    ACK(Direction.BOTH, false);

    public enum Direction { TO_SLAVE, TO_MASTER, BOTH }

    public final Direction direction;
    public final boolean needsAck;

    HiveCommand(Direction direction, boolean needsAck) {
        this.direction = direction;
        this.needsAck = needsAck;
    }

    /** Wire name: lowerCamel, e.g. GO_TO_CORNER -> "goToCorner" */
    public String wire() {
        String name = name().toLowerCase(java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : name.toCharArray()) {
            if (c == '_') { upper = true; continue; }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return sb.toString();
    }

    /** Parse a wire command name; null for unknown commands (logged + dropped by the caller). */
    public static HiveCommand parse(String wire) {
        for (HiveCommand c : values()) {
            if (c.wire().equals(wire)) return c;
        }
        return null;
    }

    /** Legacy wire names kept for cross-version compatibility. */
    public static HiveCommand parseCompat(String wire) {
        HiveCommand c = parse(wire);
        if (c != null) return c;
        switch (wire) {
            case "accept":        return ACCEPT;
            case "ack":           return ACK;
            case "finished":      return FINISHED;
            case "error":         return ERROR;
            case "remap":         return REMAP;
            case "finalizeDone":  return FINALIZE_DONE;
            case "progress":      return PROGRESS;
            case "hb":            return HEARTBEAT;
            case "goToCorner":    return GO_TO_CORNER;
            default:              return null;
        }
    }
}
