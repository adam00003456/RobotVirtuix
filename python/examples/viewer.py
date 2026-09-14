#!/usr/bin/env python3
"""Live console viewer for Omni One tracking frames.

Usage:
    python3 examples/viewer.py [--port 45454] [--host 0.0.0.0]
"""

import argparse
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from omni_track import OmniTrackClient, Movement, Status, OmniTrackCommander  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=45454)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--bridge", help="bridge IP for sending commands (optional)")
    args = ap.parse_args()

    state = {
        "speed": 0.0,
        "x": 0.0,
        "y": 0.0,
        "yaw": None,
        "omni": False,
        "steps": None,
        "battery": None,
        "last": time.time(),
    }

    def on_frame(frame):
        if isinstance(frame, Movement):
            state.update(speed=frame.speed, x=frame.x, y=frame.y,
                         yaw=frame.head_yaw_deg, last=time.time())
        elif isinstance(frame, Status):
            state.update(omni=frame.omni_connected, steps=frame.steps,
                         battery=frame.charge_level)
        else:
            print(f"\r{frame:<72}", end="")

    client = OmniTrackClient(on_frame=on_frame, host=args.host, port=args.port)
    client.start()
    print(f"listening on udp/{args.port} ... Ctrl-C to quit")

    commander = OmniTrackCommander(args.bridge) if args.bridge else None
    if commander:
        commander.request_status()

    try:
        while True:
            time.sleep(0.1)
            age = time.time() - state["last"]
            bar = "#" * int(min(1.0, state["speed"]) * 20)
            yaw = f"{state['yaw']:+.0f}deg" if state["yaw"] is not None else "  n/a"
            print(
                f"\rspeed {state['speed']:.2f} m/s | {bar:<20} | "
                f"vx {state['x']:+.2f} vy {state['y']:+.2f} | head {yaw} | "
                f"omni {'UP' if state['omni'] else 'down'} | "
                f"steps {state['steps']} | bat {state['battery']} | "
                f"frames {'' if age < 0.5 else 'STALE '}{age:.1f}s ",
                end="", flush=True,
            )
    except KeyboardInterrupt:
        print()
    finally:
        client.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
