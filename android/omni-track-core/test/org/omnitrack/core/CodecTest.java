package org.omnitrack.core;

import java.util.Arrays;
import java.util.List;

/**
 * Plain-JVM tests for the pure protocol logic (no Android classes touched).
 * Run: {@code ./run_tests.sh} from android/omni-track-core.
 */
public final class CodecTest {
    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean ok, String what) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + what);
        }
    }

    private static void eq(Object expected, Object actual, String what) {
        check(expected == null ? actual == null : expected.equals(actual),
              what + " (expected " + expected + ", got " + actual + ")");
    }

    public static void main(String[] args) {
        sourceIdTests();
        movementTests();
        parseTests();
        settingsTests();
        buildTests();
        messageCodeTests();
        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    private static void sourceIdTests() {
        eq(-9223372036854775808L, SourceId.NO_CLIENT_SOURCE, "NO_CLIENT_SOURCE");
        long[] ids = {Long.MIN_VALUE, 0L, 1L, 0x12345678L, 0xDEADBEEFCAFEL, Long.MAX_VALUE};
        for (long id : ids) {
            long round = SourceId.fromArgs(SourceId.lowArg(id), SourceId.highArg(id));
            eq(id, round, "sourceId round-trip " + id);
        }
        // Reference encoding of Long.MIN_VALUE: arg1 = 0, arg2 = 0x80000000.
        eq(0, SourceId.lowArg(Long.MIN_VALUE), "MIN lowArg");
        eq(0x80000000L, SourceId.highArg(Long.MIN_VALUE) & 0xFFFFFFFFL, "MIN highArg");
        eq(((long) 0x80000000 << 32) + (0 & 0xFFFFFFFFL), Long.MIN_VALUE, "MIN fromArgs");
    }

    private static void movementTests() {
        MapPayload p = new MapPayload()
            .putFloat("controller_x", 1.0f)
            .putFloat("controller_y", 0.5f)
            .putFloat("controller_z", 0.0f);
        OmniCodec.Movement m = OmniCodec.parseMovement(p);
        eq(1.0f, m.forward, "movement forward");
        eq(0.5f, m.lateral, "movement lateral");
        eq(0.0f, m.vertical, "movement vertical");
        check(Math.abs(m.speed() - 1.1180339887f) < 1e-5, "movement speed magnitude");

        // Unity mapping from the reference client: unity = (controller_y, controller_z, controller_x).
        eq(0.5f, m.lateral, "unity x == controller_y");
        eq(0.0f, m.vertical, "unity y == controller_z");
        eq(1.0f, m.forward, "unity z == controller_x");

        OmniCodec.Movement zero = OmniCodec.parseMovement(new MapPayload());
        eq(0f, zero.forward, "missing keys -> 0");
        eq(0f, zero.speed(), "missing keys -> zero speed");
    }

    private static void parseTests() {
        eq(4242, OmniCodec.parseStepCount(new MapPayload().putInt("step_count", 4242)), "step count");
        eq(-1, OmniCodec.parseStepCount(new MapPayload()), "step count default");

        List<String> names = OmniCodec.parseScanResults(new MapPayload()
            .putStringArrayList("DEVICE_NAME", Arrays.asList("Omni One A1", "Omni One B2")));
        eq(2, names == null ? -1 : names.size(), "scan result size");
        eq("Omni One A1", names.get(0), "scan result name");

        eq("Omni One A1", OmniCodec.parseTreadmillName(
            new MapPayload().putString("TREADMILL_NAME", "Omni One A1")), "treadmill name");
        eq(null, OmniCodec.parseTreadmillName(new MapPayload()), "treadmill name default");

        OmniCodec.Battery b = OmniCodec.parseBattery(new MapPayload()
            .putFloat("charge_level", 0.87f).putBoolean("charging", true));
        eq(0.87f, b.chargeLevel, "battery level");
        eq(true, b.charging, "battery charging");

        OmniCodec.FootTrackerBattery ftb = OmniCodec.parseFootTrackerBattery(new MapPayload()
            .putInt("left_percentage", 40).putInt("right_percentage", 60));
        eq(40, ftb.leftPercent, "foot battery left");
        eq(60, ftb.rightPercent, "foot battery right");

        eq(OmniCodec.ControlButton.PAUSE, OmniCodec.parseControlButton(
            new MapPayload().putInt("button_type", 1)).type, "pause button");
        eq(OmniCodec.ControlButton.LONG_HOME, OmniCodec.parseControlButton(
            new MapPayload().putInt("button_type", 2)).type, "long home button");
        eq(OmniCodec.ControlButton.UNKNOWN, OmniCodec.parseControlButton(
            new MapPayload()).type, "unknown button default");

        String[] ue = OmniCodec.parseUnknownException(new MapPayload()
            .putString("message", "boom").putString("stack_trace", "at X"));
        eq("boom", ue[0], "unknown exception message");
        eq("at X", ue[1], "unknown exception stack");

        Object[] ke = OmniCodec.parseKnownException(new MapPayload()
            .putInt("type", 3).putString("message", "nope").putString("stack_trace", "at Y"));
        eq(3, ke[0], "known exception type");
        eq("nope", ke[1], "known exception message");
    }

    private static void settingsTests() {
        OmniCodec.Settings s = OmniCodec.parseSettings(new MapPayload()
            .putBoolean("MOVEMENT", true)
            .putBoolean("LOG_SETTINGS", false)
            .putBoolean("PERMISSIONS", true)
            .putString("REST_URL", "https://api.omni.virtuix.com")
            .putBoolean("forward_exception", true));
        eq(Boolean.TRUE, s.movement, "settings movement");
        eq(Boolean.FALSE, s.logSettings, "settings log");
        eq(Boolean.TRUE, s.permissions, "settings permissions");
        eq("https://api.omni.virtuix.com", s.restUrl, "settings rest url");
        eq(null, s.flagsmithEnvironmentKey, "settings flagsmith absent -> null");
        eq(Boolean.TRUE, s.forwardException, "settings forward_exception");

        OmniCodec.Settings empty = OmniCodec.parseSettings(new MapPayload());
        eq(null, empty.movement, "empty settings -> null booleans");
        eq(null, empty.restUrl, "empty settings -> null url");
    }

    private static void buildTests() {
        MapPayload pose = OmniCodec.buildPlayerPose(1f, 2f, 3f, 4f, 5f, 6f);
        eq(1f, pose.getFloat("rotation_x", -1f), "pose rotation_x");
        eq(2f, pose.getFloat("rotation_y", -1f), "pose rotation_y");
        eq(3f, pose.getFloat("rotation_z", -1f), "pose rotation_z");
        eq(4f, pose.getFloat("position_x", -1f), "pose position_x");
        eq(5f, pose.getFloat("position_y", -1f), "pose position_y");
        eq(6f, pose.getFloat("position_z", -1f), "pose position_z");

        MapPayload connect = OmniCodec.buildConnectToTreadmill("Omni One A1");
        eq("Omni One A1", connect.getString("TREADMILL_NAME", null), "connect payload");
    }

    private static void messageCodeTests() {
        eq(3, MessageCodes.SET_CONTROLLER_DATA, "SET_CONTROLLER_DATA code");
        eq(4, MessageCodes.SET_PLAYER_DATA, "SET_PLAYER_DATA code");
        eq(10007, MessageCodes.REGISTER_UNITY_CLIENT, "REGISTER_UNITY_CLIENT code");
        eq(100010, MessageCodes.CURRENT_STEP_COUNT, "CURRENT_STEP_COUNT code");
        eq(160016, MessageCodes.CONNECT_TO_TREADMILL, "CONNECT_TO_TREADMILL code");
        eq(31000026, MessageCodes.FOOT_TRACKER_BATTERY_STATUS, "FOOT_TRACKER_BATTERY_STATUS code");
        eq("SET_CONTROLLER_DATA", MessageCodes.name(3), "name(3)");
        eq("UNKNOWN_123456", MessageCodes.name(123456), "name() unknown fallback");
    }
}
