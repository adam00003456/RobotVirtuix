package org.omnitrack.bridge;

/**
 * Source of the headset pose fed to SET_PLAYER_DATA. The tracking solution is
 * view-relative, so the client must tell the server where the head points.
 *
 * Implementations: {@link ZeroPoseSource} (movement relative to a fixed
 * reference) and {@link ImuPoseSource} (head yaw/pitch/roll from the device
 * IMU via the standard Android sensor stack — no XR engine required).
 */
interface PoseSource {
    final class Pose {
        final float rollDeg;
        final float pitchDeg;
        final float yawDeg;

        Pose(float rollDeg, float pitchDeg, float yawDeg) {
            this.rollDeg = rollDeg;
            this.pitchDeg = pitchDeg;
            this.yawDeg = yawDeg;
        }

        static final Pose ZERO = new Pose(0f, 0f, 0f);
    }

    Pose snapshot();

    void start();

    void stop();
}
