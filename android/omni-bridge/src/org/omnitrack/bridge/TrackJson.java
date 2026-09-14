package org.omnitrack.bridge;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.omnitrack.core.OmniCodec;

import java.util.List;

/** Builds the wire JSON frames documented in docs/TELEOP.md. */
final class TrackJson {
    private TrackJson() {}

    static long now() {
        return System.currentTimeMillis();
    }

    static String movement(OmniCodec.Movement m, Double headYawDeg) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "movement");
            o.put("t_ms", now());
            o.put("x", (double) m.forward);   // forward, m/s
            o.put("y", (double) m.lateral);   // lateral (right +), m/s
            o.put("z", (double) m.vertical);  // vertical, ~0
            o.put("speed", (double) m.speed());
            if (headYawDeg != null) {
                o.put("head_yaw_deg", headYawDeg);
            }
            return o.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    static String stepCount(int count) {
        return frame("step_count").put("count", count).toStringOrNull();
    }

    static String scanResults(List<String> names) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "scan_results");
            o.put("t_ms", now());
            JSONArray a = new JSONArray();
            if (names != null) {
                for (String n : names) a.put(n);
            }
            o.put("names", a);
            return o.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    static String treadmillConnected(String name) {
        return frame("treadmill_connected").put("name", name).toStringOrNull();
    }

    static String treadmillDisconnected() {
        return frame("treadmill_disconnected").toStringOrNull();
    }

    static String omniConnected() {
        return frame("omni_connected").toStringOrNull();
    }

    static String omniDisconnected() {
        return frame("omni_disconnected").toStringOrNull();
    }

    static String footTracker(boolean left, boolean connected) {
        return frame("foot_tracker")
            .put("side", left ? "left" : "right")
            .put("connected", connected)
            .toStringOrNull();
    }

    static String battery(OmniCodec.Battery b) {
        return frame("battery")
            .put("charge_level", (double) b.chargeLevel)
            .put("charging", b.charging)
            .toStringOrNull();
    }

    static String footTrackerBattery(OmniCodec.FootTrackerBattery b) {
        return frame("foot_tracker_battery")
            .put("left_percentage", b.leftPercent)
            .put("right_percentage", b.rightPercent)
            .toStringOrNull();
    }

    static String treadmillLock(boolean locked) {
        return frame(locked ? "treadmill_locked" : "treadmill_unlocked").toStringOrNull();
    }

    static String controlButton(OmniCodec.ControlButton b) {
        String name;
        if (b.type == OmniCodec.ControlButton.PAUSE) name = "pause";
        else if (b.type == OmniCodec.ControlButton.LONG_HOME) name = "long_home";
        else name = "unknown_" + b.type;
        return frame("button").put("button", name).toStringOrNull();
    }

    static String shortButton(boolean pressed) {
        return frame("short_button").put("pressed", pressed).toStringOrNull();
    }

    static String boundary(boolean changed, boolean omniMode) {
        return frame("boundary")
            .put("changed", changed)
            .putOpt("omni_mode", changed ? (Object) omniMode : null)
            .toStringOrNull();
    }

    static String calibrationResult() {
        return frame("calibration_result").toStringOrNull();
    }

    static String clientConnected(boolean connected) {
        return frame("client").put("connected", connected).toStringOrNull();
    }

    static String settings(OmniCodec.Settings s) {
        Builder b = frame("settings");
        if (s.movement != null) b.put("movement", s.movement);
        if (s.permissions != null) b.put("permissions", s.permissions);
        if (s.restUrl != null) b.put("rest_url", s.restUrl);
        if (s.forwardException != null) b.put("forward_exception", s.forwardException);
        return b.toStringOrNull();
    }

    static String error(String message) {
        return frame("error").put("error", message).toStringOrNull();
    }

    static String status(StatusSnapshot s) {
        return s.toJson().toString();
    }

    // -------------------------------------------------------------- tiny builder

    /** JSONObject wrapper with a null-safe toString. */
    static final class Builder {
        final JSONObject o = new JSONObject();

        Builder put(String k, Object v) {
            try {
                o.put(k, v);
            } catch (JSONException ignored) {
            }
            return this;
        }

        Builder putOpt(String k, Object v) {
            try {
                o.putOpt(k, v);
            } catch (JSONException ignored) {
            }
            return this;
        }

        String toStringOrNull() {
            return o.toString();
        }
    }

    static Builder frame(String type) {
        Builder b = new Builder();
        b.put("type", type);
        b.put("t_ms", now());
        return b;
    }
}
