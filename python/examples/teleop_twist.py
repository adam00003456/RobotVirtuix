#!/usr/bin/env python3
"""ROS 2 teleop example: maps Omni One walking to geometry_msgs/Twist on /cmd_vel.

Requires a ROS 2 environment (rclpy, geometry_msgs) on the robot controller::

    # after colcon build / sourcing your ROS 2 install
    python3 examples/teleop_twist.py --bridge 192.168.1.42

Mapping (holonomic base):
    linear.x  = movement.x   (forward, m/s)
    linear.y  = movement.y   (lateral, right +, m/s)
    angular.z = -k * head_yaw (turn with your head, if IMU pose is enabled)

Head-yaw steering is optional: set --steer-yaw to enable and --steer-gain to tune.
Deadman: the treadmill's own software lock plus --max-speed are your safety
limits; always test with the robot wheels off the ground first.
"""

import argparse
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from omni_track import OmniTrackClient, Movement, OmniTrackCommander  # noqa: E402


def main() -> int:
    try:
        import rclpy
        from rclpy.node import Node
        from geometry_msgs.msg import Twist
    except ImportError:
        print("ROS 2 not available: source your ROS 2 install first "
              "(needs rclpy and geometry_msgs).")
        return 1

    ap = argparse.ArgumentParser()
    ap.add_argument("--bridge", required=True, help="OmniTrack bridge IP (headset)")
    ap.add_argument("--port", type=int, default=45454)
    ap.add_argument("--topic", default="/cmd_vel")
    ap.add_argument("--max-speed", type=float, default=1.0, help="clamp m/s")
    ap.add_argument("--deadband", type=float, default=0.08, help="ignore speeds below, m/s")
    ap.add_argument("--steer-yaw", action="store_true", help="steer with head yaw (needs IMU pose)")
    ap.add_argument("--steer-gain", type=float, default=1.0, help="rad/s per degree of head yaw")
    ap.add_argument("--rate", type=float, default=20.0, help="publish rate, Hz")
    args = ap.parse_args()

    rclpy.init()
    node = Node("omni_teleop")
    pub = node.create_publisher(Twist, args.topic, 10)

    last = {"t": 0.0, "x": 0.0, "y": 0.0, "yaw": None}
    commander = OmniTrackCommander(args.bridge)

    def clamp(v: float) -> float:
        return max(-args.max_speed, min(args.max_speed, v))

    def on_frame(frame):
        if not isinstance(frame, Movement):
            return
        msg = Twist()
        if frame.is_moving(args.deadband):
            msg.linear.x = clamp(frame.x)
            msg.linear.y = clamp(frame.y)
        if args.steer_yaw and frame.head_yaw_deg is not None:
            msg.angular.z = -args.steer_gain * frame.head_yaw_deg
        pub.publish(msg)
        last.update(t=time.time(), x=msg.linear.x, y=msg.linear.y, yaw=frame.head_yaw_deg)

    client = OmniTrackClient(on_frame=on_frame, port=args.port)
    client.start()

    stop = Twist()  # zero twist on shutdown / staleness
    try:
        commander.request_status()
        while rclpy.ok():
            rclpy.spin_once(node, timeout_sec=0)
            if time.time() - last["t"] > 0.5:  # data gone stale: hard stop
                pub.publish(stop)
            time.sleep(1.0 / args.rate)
    except KeyboardInterrupt:
        pass
    finally:
        pub.publish(stop)
        client.stop()
        node.destroy_node()
        rclpy.shutdown()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
