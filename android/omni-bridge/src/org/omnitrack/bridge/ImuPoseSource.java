package org.omnitrack.bridge;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

/**
 * Head orientation from the standard Android rotation-vector sensor.
 *
 * Caveat (see docs/TELEOP.md): the Omni expects the HMD pose in the coordinate
 * convention of PROTOCOL.md 5.1. This source feeds (roll, pitch, yaw) degrees
 * from the device IMU, which reproduces head yaw — the dominant term for
 * view-relative tracking — without an XR engine. Verify axis signs on
 * hardware; remap with SensorManager.remapCoordinateSystem if the yaw
 * reference needs correcting.
 */
final class ImuPoseSource implements PoseSource, SensorEventListener {
    private static final String TAG = "OmniTrackBridge";

    private final SensorManager sensors;
    private volatile Pose pose = Pose.ZERO;

    ImuPoseSource(Context context) {
        this.sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    }

    @Override public Pose snapshot() { return pose; }

    @Override public void start() {
        if (sensors == null) {
            Log.w(TAG, "no SensorManager; IMU pose unavailable");
            return;
        }
        Sensor rv = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rv == null) {
            Log.w(TAG, "no rotation-vector sensor");
            return;
        }
        sensors.registerListener(this, rv, 20000); // 50 Hz
    }

    @Override public void stop() {
        if (sensors != null) sensors.unregisterListener(this);
    }

    @Override public void onSensorChanged(SensorEvent event) {
        float[] r = new float[9];
        float[] o = new float[3];
        SensorManager.getRotationMatrixFromVector(r, event.values);
        SensorManager.getOrientation(r, o); // radians: [azimuth(yaw), pitch, roll]
        pose = new Pose(
            (float) Math.toDegrees(o[2]), // roll
            (float) Math.toDegrees(o[1]), // pitch
            (float) Math.toDegrees(o[0])  // yaw
        );
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
