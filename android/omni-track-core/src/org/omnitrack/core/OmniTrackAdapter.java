package org.omnitrack.core;

import android.os.Bundle;

import java.util.List;

/** No-op {@link OmniTrackListener}; extend and override what you need. */
public class OmniTrackAdapter implements OmniTrackListener {
    @Override public void onClientConnected() {}
    @Override public void onClientDisconnected() {}
    @Override public void onServiceBindFailed() {}
    @Override public void onMovementData(OmniCodec.Movement movement) {}
    @Override public void onStepCount(int count) {}
    @Override public void onTreadmillScanResults(List<String> names) {}
    @Override public void onConnectedTreadmillName(String name) {}
    @Override public void onTreadmillDisconnected() {}
    @Override public void onOmniConnected() {}
    @Override public void onOmniDisconnected() {}
    @Override public void onFootTrackerConnected(boolean left) {}
    @Override public void onFootTrackerDisconnected(boolean left) {}
    @Override public void onFootTrackerBattery(OmniCodec.FootTrackerBattery battery) {}
    @Override public void onBatteryStatus(OmniCodec.Battery battery) {}
    @Override public void onTreadmillLocked() {}
    @Override public void onTreadmillUnlocked() {}
    @Override public void onControlButton(OmniCodec.ControlButton button) {}
    @Override public void onShortButtonState(boolean pressed) {}
    @Override public void onBoundaryChanged(boolean omniMode) {}
    @Override public void onBoundaryNotChanged() {}
    @Override public void onCalibrationResult() {}
    @Override public void onForceCalibrationRequested() {}
    @Override public void onSettings(OmniCodec.Settings settings) {}
    @Override public void onUnknownException(String message, String stackTrace) {}
    @Override public void onKnownException(int type, String message, String stackTrace) {}
    @Override public void onUnknownMessage(int what, Bundle data) {}
}
