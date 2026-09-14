package org.omnitrack.core;

import java.util.List;

/**
 * Encoders/decoders for tracking-protocol payloads. Pure logic; the Android
 * client feeds it {@link BundlePayload}, tests feed it {@link MapPayload}.
 *
 * All parse methods take the <b>incoming</b> message payload; all build
 * methods return the payload for an <b>outgoing</b> message.
 */
public final class OmniCodec {
    private OmniCodec() {}

    // ---------------------------------------------------------------- models

    /** View-relative velocity in m/s (Omni axis convention). */
    public static final class Movement {
        public final float forward;   // controller_x, maps to Unity +Z
        public final float lateral;   // controller_y, maps to Unity +X (right +)
        public final float vertical;  // controller_z, maps to Unity +Y (~0)

        public Movement(float forward, float lateral, float vertical) {
            this.forward = forward;
            this.lateral = lateral;
            this.vertical = vertical;
        }

        public float speed() {
            return (float) Math.sqrt(
                (double) forward * forward
                + (double) lateral * lateral
                + (double) vertical * vertical);
        }

        @Override
        public String toString() {
            return "Movement{" + forward + ", " + lateral + ", " + vertical + " m/s}";
        }
    }

    public static final class Battery {
        public final float chargeLevel; // 0..1, -1 if unknown
        public final boolean charging;
        public Battery(float chargeLevel, boolean charging) {
            this.chargeLevel = chargeLevel;
            this.charging = charging;
        }
    }

    public static final class FootTrackerBattery {
        public final int leftPercent;
        public final int rightPercent;
        public FootTrackerBattery(int leftPercent, int rightPercent) {
            this.leftPercent = leftPercent;
            this.rightPercent = rightPercent;
        }
    }

    public static final class ControlButton {
        public static final int PAUSE = 1;
        public static final int LONG_HOME = 2;
        public static final int UNKNOWN = 999999;
        public final int type;
        public ControlButton(int type) {
            this.type = type;
        }
    }

    /** Typed subset of the SETTINGS payload. */
    public static final class Settings {
        public final Boolean movement;      // movement feature enabled server-side
        public final Boolean logSettings;
        public final Boolean permissions;
        public final String restUrl;
        public final String flagsmithEnvironmentKey;
        public final Boolean forwardException;

        public Settings(Boolean movement, Boolean logSettings, Boolean permissions,
                         String restUrl, String flagsmithEnvironmentKey, Boolean forwardException) {
            this.movement = movement;
            this.logSettings = logSettings;
            this.permissions = permissions;
            this.restUrl = restUrl;
            this.flagsmithEnvironmentKey = flagsmithEnvironmentKey;
            this.forwardException = forwardException;
        }
    }

    // ---------------------------------------------------------------- parse

    /** SET_CONTROLLER_DATA (what = 3). */
    public static Movement parseMovement(Payload p) {
        return new Movement(
            p.getFloat(Keys.CONTROLLER_X, 0f),
            p.getFloat(Keys.CONTROLLER_Y, 0f),
            p.getFloat(Keys.CONTROLLER_Z, 0f));
    }

    /** CURRENT_STEP_COUNT (what = 100010). */
    public static int parseStepCount(Payload p) {
        return p.getInt(Keys.STEP_COUNT, -1);
    }

    /** OMNI_ONE_SCAN_RESULT (what = 31000045). */
    public static List<String> parseScanResults(Payload p) {
        return p.getStringArrayList(Keys.DEVICE_NAMES);
    }

    /** OMNI_ONE_CONNECTED_DEVICE_NAME / request for CONNECT_TO_TREADMILL (what = 160018). */
    public static String parseTreadmillName(Payload p) {
        return p.getString(Keys.TREADMILL_NAME, null);
    }

    /** BATTERY_STATUS (what = 16009). */
    public static Battery parseBattery(Payload p) {
        return new Battery(p.getFloat(Keys.CHARGE_LEVEL, -1f), p.getBoolean(Keys.CHARGING, false));
    }

    /** FOOT_TRACKER_BATTERY_STATUS (what = 31000026). */
    public static FootTrackerBattery parseFootTrackerBattery(Payload p) {
        return new FootTrackerBattery(
            p.getInt(Keys.LEFT_PERCENTAGE, -1),
            p.getInt(Keys.RIGHT_PERCENTAGE, -1));
    }

    /** CONTROL_BUTTON_PRESSED (what = 5001). */
    public static ControlButton parseControlButton(Payload p) {
        return new ControlButton(p.getInt(Keys.BUTTON_TYPE, ControlButton.UNKNOWN));
    }

    /** UNKNOWN_EXCEPTION (what = 13001). */
    public static String[] parseUnknownException(Payload p) {
        return new String[] {
            p.getString(Keys.MESSAGE, ""),
            p.getString(Keys.STACK_TRACE, "")
        };
    }

    /** KNOWN_EXCEPTION (what = 13002) -> {type, message, stackTrace}. */
    public static Object[] parseKnownException(Payload p) {
        return new Object[] {
            p.getInt(Keys.TYPE, -1),
            p.getString(Keys.MESSAGE, ""),
            p.getString(Keys.STACK_TRACE, "")
        };
    }

    /** SETTINGS (what = 100038). Null-valued fields mean "key not present". */
    public static Settings parseSettings(Payload p) {
        return new Settings(
            p.containsKey(Keys.SETTINGS_MOVEMENT) ? p.getBoolean(Keys.SETTINGS_MOVEMENT, false) : null,
            p.containsKey(Keys.SETTINGS_LOG_SETTINGS) ? p.getBoolean(Keys.SETTINGS_LOG_SETTINGS, false) : null,
            p.containsKey(Keys.SETTINGS_PERMISSIONS) ? p.getBoolean(Keys.SETTINGS_PERMISSIONS, false) : null,
            p.getString(Keys.SETTINGS_REST_URL, null),
            p.getString(Keys.SETTINGS_FLAGSMITH_ENV, null),
            p.containsKey(Keys.SETTINGS_FORWARD_EXCEPTION) ? p.getBoolean(Keys.SETTINGS_FORWARD_EXCEPTION, false) : null);
    }

    // ---------------------------------------------------------------- build

    /**
     * SET_PLAYER_DATA (what = 4). The headset pose, Omni axis convention:
     * rotation = (roll, pitch, yaw), position in Omni axes. See PROTOCOL.md 5.1
     * for the exact Unity shuffle (x=Unity euler Z, y=Unity euler X, z=Unity euler Y).
     */
    public static MapPayload buildPlayerPose(float rotationX, float rotationY, float rotationZ,
                                             float positionX, float positionY, float positionZ) {
        MapPayload p = new MapPayload();
        p.putFloat(Keys.ROTATION_X, rotationX);
        p.putFloat(Keys.ROTATION_Y, rotationY);
        p.putFloat(Keys.ROTATION_Z, rotationZ);
        p.putFloat(Keys.POSITION_X, positionX);
        p.putFloat(Keys.POSITION_Y, positionY);
        p.putFloat(Keys.POSITION_Z, positionZ);
        return p;
    }

    /** CONNECT_TO_TREADMILL (what = 160016). */
    public static MapPayload buildConnectToTreadmill(String treadmillName) {
        return new MapPayload().putString(Keys.TREADMILL_NAME, treadmillName);
    }
}
