package org.omnitrack.core;

/**
 * Bundle keys used by the tracking protocol (see docs/PROTOCOL.md).
 * Reconstructed for interoperability; not affiliated with Virtuix.
 */
public final class Keys {
    private Keys() {}

    // SET_PLAYER_DATA (client -> server, HMD pose)
    public static final String ROTATION_X = "rotation_x"; // roll  (Unity euler Z)
    public static final String ROTATION_Y = "rotation_y"; // pitch (Unity euler X)
    public static final String ROTATION_Z = "rotation_z"; // yaw   (Unity euler Y)
    public static final String POSITION_X = "position_x";
    public static final String POSITION_Y = "position_y";
    public static final String POSITION_Z = "position_z";

    // SET_CONTROLLER_DATA (server -> client, view-relative velocity, m/s)
    public static final String CONTROLLER_X = "controller_x"; // forward
    public static final String CONTROLLER_Y = "controller_y"; // lateral (right +)
    public static final String CONTROLLER_Z = "controller_z"; // vertical (~0)

    // CURRENT_STEP_COUNT
    public static final String STEP_COUNT = "step_count";

    // Treadmill scan / connection
    public static final String DEVICE_NAMES = "DEVICE_NAME";
    public static final String TREADMILL_NAME = "TREADMILL_NAME";

    // BATTERY_STATUS
    public static final String CHARGE_LEVEL = "charge_level";
    public static final String CHARGING = "charging";

    // FOOT_TRACKER_BATTERY_STATUS
    public static final String RIGHT_PERCENTAGE = "right_percentage";
    public static final String LEFT_PERCENTAGE = "left_percentage";

    // CONTROL_BUTTON_PRESSED
    public static final String BUTTON_TYPE = "button_type";

    // Exceptions
    public static final String MESSAGE = "message";
    public static final String STACK_TRACE = "stack_trace";
    public static final String TYPE = "type";

    // SETTINGS (typed keys observed in the reference client)
    public static final String SETTINGS_MOVEMENT = "MOVEMENT";
    public static final String SETTINGS_LOG_SETTINGS = "LOG_SETTINGS";
    public static final String SETTINGS_PERMISSIONS = "PERMISSIONS";
    public static final String SETTINGS_REST_URL = "REST_URL";
    public static final String SETTINGS_FLAGSMITH_ENV = "flag_smith_environment_key";
    public static final String SETTINGS_FORWARD_EXCEPTION = "forward_exception";

    /** Shared JSON payload key for Gson-serialized DTOs. */
    public static final String JSON_STRING = "json_string";
}
