package org.omnitrack.bridge;

import org.json.JSONException;
import org.json.JSONObject;
import org.omnitrack.core.OmniCodec;

/** Thread-safe aggregation of the latest state, serialized as the "status" frame. */
final class StatusSnapshot {
    private volatile boolean clientConnected;
    private volatile boolean omniConnected;
    private volatile String treadmillName;
    private volatile Boolean treadmillLocked; // null = unknown
    private volatile Float chargeLevel;      // null = unknown
    private volatile boolean charging;
    private volatile int footLeftPercent = -1;
    private volatile int footRightPercent = -1;
    private volatile Boolean footLeftConnected;  // null = unknown (events only)
    private volatile Boolean footRightConnected;
    private volatile int steps = -1;
    private volatile Float lastForward;
    private volatile Float lastLateral;
    private volatile Float lastSpeed;
    private volatile Long lastMovementAtMs;
    private volatile boolean calibratedUnknown = true; // becomes false after a boundary event

    void setClientConnected(boolean v) { clientConnected = v; }
    void setOmniConnected(boolean v) { omniConnected = v; }
    void setTreadmillName(String n) { treadmillName = n; }
    void setTreadmillLocked(boolean v) { treadmillLocked = v; }
    void setBattery(OmniCodec.Battery b) { chargeLevel = b.chargeLevel; charging = b.charging; }
    void setFootTrackerBattery(OmniCodec.FootTrackerBattery b) {
        footLeftPercent = b.leftPercent;
        footRightPercent = b.rightPercent;
    }
    void setFootTrackerConnected(boolean left, boolean v) {
        if (left) footLeftConnected = v; else footRightConnected = v;
    }
    void setSteps(int s) { steps = s; }
    void setMovement(OmniCodec.Movement m) {
        lastForward = m.forward;
        lastLateral = m.lateral;
        lastSpeed = m.speed();
        lastMovementAtMs = System.currentTimeMillis();
    }
    void setCalibrated(boolean v) { calibratedUnknown = false; }

    JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "status");
            o.put("t_ms", System.currentTimeMillis());
            o.put("client_connected", clientConnected);
            o.put("omni_connected", omniConnected);
            o.putOpt("treadmill_name", treadmillName);
            if (treadmillLocked != null) o.put("treadmill_locked", treadmillLocked);
            if (chargeLevel != null) {
                o.put("charge_level", (double) chargeLevel);
                o.put("charging", charging);
            }
            JSONObject foot = new JSONObject();
            putFoot(foot, "left", footLeftConnected, footLeftPercent);
            putFoot(foot, "right", footRightConnected, footRightPercent);
            o.put("foot_trackers", foot);
            if (steps >= 0) o.put("steps", steps);
            if (lastForward != null) {
                o.put("x", (double) lastForward);
                o.put("y", (double) lastLateral);
                o.put("speed", (double) lastSpeed);
                o.put("last_movement_age_ms",
                      System.currentTimeMillis() - lastMovementAtMs);
            }
            o.put("calibration_known", !calibratedUnknown);
            return o;
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static void putFoot(JSONObject foot, String side, Boolean connected, int pct) throws JSONException {
        JSONObject f = new JSONObject();
        if (connected != null) f.put("connected", connected);
        if (pct >= 0) f.put("battery_pct", pct);
        foot.put(side, f);
    }
}
