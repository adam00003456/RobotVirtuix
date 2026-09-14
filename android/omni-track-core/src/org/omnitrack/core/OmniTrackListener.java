package org.omnitrack.core;

import android.os.Bundle;

import java.util.List;

/**
 * Callbacks from {@link OmniTrackClient}.
 *
 * All callbacks are invoked on the client's dedicated handler thread. Keep them
 * cheap; hop to your own thread/queue for heavy work.
 */
public interface OmniTrackListener {
    /** Server messenger is set and the client has registered itself. */
    void onClientConnected();

    /**
     * The binding was lost (server died or client is shutting down). The client
     * auto-re-binds; {@link #onClientConnected()} follows when it succeeds.
     */
    void onClientDisconnected();

    /** bindService() failed: the Omni system app is not present or not bindable. */
    void onServiceBindFailed();

    /** SET_CONTROLLER_DATA: view-relative velocity in m/s (x=forward, y=lateral, z~0). */
    void onMovementData(OmniCodec.Movement movement);

    /** CURRENT_STEP_COUNT. */
    void onStepCount(int count);

    /** OMNI_ONE_SCAN_RESULT. */
    void onTreadmillScanResults(List<String> names);

    /** OMNI_ONE_CONNECTED_DEVICE_NAME (response to connect or to the startup query). */
    void onConnectedTreadmillName(String name);

    /** DISCONNECTED_FROM_TREADMILL. */
    void onTreadmillDisconnected();

    /** OMNI_CONNECTED / OMNI_DISCONNECTED (overall treadmill connection). */
    void onOmniConnected();
    void onOmniDisconnected();

    /** LEFT/RIGHT_FOOT_TRACKER_CONNECTED/DISCONNECTED. */
    void onFootTrackerConnected(boolean left);
    void onFootTrackerDisconnected(boolean left);

    /** FOOT_TRACKER_BATTERY_STATUS. */
    void onFootTrackerBattery(OmniCodec.FootTrackerBattery battery);

    /** BATTERY_STATUS. */
    void onBatteryStatus(OmniCodec.Battery battery);

    /** TREADMILL_LOCKED / TREADMILL_UNLOCKED. */
    void onTreadmillLocked();
    void onTreadmillUnlocked();

    /** CONTROL_BUTTON_PRESSED (see {@link OmniCodec.ControlButton}). */
    void onControlButton(OmniCodec.ControlButton button);

    /** SHORT_BUTTON_PRESSED / SHORT_BUTTON_NOT_PRESSED. */
    void onShortButtonState(boolean pressed);

    /** BOUNDARY_CHANGED_OMNI / BOUNDARY_CHANGED_ROOM (recalibrate) or BOUNDARY_NOT_CHANGED. */
    void onBoundaryChanged(boolean omniMode);
    void onBoundaryNotChanged();

    /** CALIBRATION_RESULT (legacy result of CALIBRATE_OMNI). */
    void onCalibrationResult();

    /** SERVER asks the client to force-complete its calibration (FORCE_CALIBRATION_COMPLETE ack sent by you via client). */
    void onForceCalibrationRequested();

    /** SETTINGS payload from the handshake. */
    void onSettings(OmniCodec.Settings settings);

    /** UNKNOWN_EXCEPTION. */
    void onUnknownException(String message, String stackTrace);

    /** KNOWN_EXCEPTION. */
    void onKnownException(int type, String message, String stackTrace);

    /** Any message this SDK does not model (future protocol versions). */
    void onUnknownMessage(int what, Bundle data);
}
