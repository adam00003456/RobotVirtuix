package org.omnitrack.bridge;

/** Always reports a fixed, straight-ahead pose. */
final class ZeroPoseSource implements PoseSource {
    @Override public Pose snapshot() { return Pose.ZERO; }
    @Override public void start() {}
    @Override public void stop() {}
}
