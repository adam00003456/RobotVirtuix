"""Loopback tests for the UDP client (stdlib unittest only)."""

import json
import os
import sys
import threading
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from omni_track import OmniTrackClient, Movement, Echo, parse_json  # noqa: E402


class TestClientLoopback(unittest.TestCase):
    def test_receives_movement_frame(self):
        received = threading.Event()
        got = {}

        def on_frame(frame):
            got["frame"] = frame
            received.set()

        client = OmniTrackClient(on_frame=on_frame, host="127.0.0.1", port=0)
        client.start()
        try:
            payload = json.dumps({
                "type": "movement", "t_ms": int(time.time() * 1000),
                "x": 1.2, "y": -0.3, "z": 0.0, "speed": 1.24, "head_yaw_deg": 12.0,
            }).encode("utf-8")
            import socket
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                s.sendto(payload, ("127.0.0.1", client.port))

            self.assertTrue(received.wait(timeout=3.0), "frame not received")
            m = got["frame"]
            self.assertIsInstance(m, Movement)
            self.assertAlmostEqual(m.x, 1.2)
            self.assertAlmostEqual(m.y, -0.3)
            self.assertAlmostEqual(m.speed, 1.24)
            self.assertEqual(m.head_yaw_deg, 12.0)
        finally:
            client.stop()

    def test_bad_frames_do_not_kill_loop_and_error_is_visible(self):
        received = threading.Event()

        def on_frame(frame):
            received.set()

        client = OmniTrackClient(on_frame=on_frame, host="127.0.0.1", port=0)
        client.start()
        try:
            import socket
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                s.sendto(b"this is not json", ("127.0.0.1", client.port))
                s.sendto(b'{"type":"bogus"}', ("127.0.0.1", client.port))
                time.sleep(0.3)
                self.assertIsNotNone(client.last_error)
                # loop still alive: a good frame still arrives
                good = json.dumps({"type": "echo", "t_ms": 1}).encode()
                s.sendto(good, ("127.0.0.1", client.port))
                self.assertTrue(received.wait(timeout=3.0))
        finally:
            client.stop()

    def test_parse_json_helper(self):
        e = parse_json('{"type":"echo"}')
        self.assertIsInstance(e, Echo)


if __name__ == "__main__":
    unittest.main()
