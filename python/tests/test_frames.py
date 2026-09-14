"""Unit tests for omni_track frame parsing (stdlib unittest only)."""

import json
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from omni_track import frames  # noqa: E402
from omni_track.frames import (  # noqa: E402
    Movement, StepCount, Status, FootTracker, FootTrackerBattery, Battery,
    TreadmillConnected, TreadmillLock, Button, ShortButton, Boundary, ClientState,
    ScanResults, Settings, Echo, BridgeError,
)


class TestParse(unittest.TestCase):
    def test_movement(self):
        m = frames.parse_json(json.dumps({
            "type": "movement", "t_ms": 1, "x": 1.0, "y": 0.5, "z": 0.0, "speed": 1.118
        }))
        self.assertIsInstance(m, Movement)
        self.assertEqual(m.x, 1.0)
        self.assertEqual(m.y, 0.5)
        self.assertEqual(m.z, 0.0)
        self.assertAlmostEqual(m.speed, 1.118)
        self.assertIsNone(m.head_yaw_deg)
        self.assertEqual(m.as_tuple(), (1.0, 0.5, 0.0))
        self.assertTrue(m.is_moving(0.05))

    def test_movement_head_yaw(self):
        m = frames.parse_json('{"type":"movement","x":0,"y":0,"z":0,"speed":0,"head_yaw_deg":45.5}')
        self.assertEqual(m.head_yaw_deg, 45.5)
        self.assertFalse(m.is_moving())

    def test_step_count(self):
        s = frames.parse_json('{"type":"step_count","count":4242}')
        self.assertIsInstance(s, StepCount)
        self.assertEqual(s.count, 4242)

    def test_foot_tracker(self):
        f = frames.parse_json('{"type":"foot_tracker","side":"left","connected":true}')
        self.assertIsInstance(f, FootTracker)
        self.assertEqual(f.side, "left")
        self.assertTrue(f.connected)

    def test_foot_tracker_battery(self):
        f = frames.parse_json('{"type":"foot_tracker_battery","left_percentage":40,"right_percentage":60}')
        self.assertIsInstance(f, FootTrackerBattery)
        self.assertEqual((f.left_percentage, f.right_percentage), (40, 60))

    def test_battery(self):
        b = frames.parse_json('{"type":"battery","charge_level":0.87,"charging":true}')
        self.assertIsInstance(b, Battery)
        self.assertAlmostEqual(b.charge_level, 0.87)
        self.assertTrue(b.charging)

    def test_treadmill_events(self):
        c = frames.parse_json('{"type":"treadmill_connected","name":"Omni One A1"}')
        self.assertIsInstance(c, TreadmillConnected)
        self.assertEqual(c.name, "Omni One A1")
        lk = frames.parse_json('{"type":"treadmill_locked"}')
        self.assertIsInstance(lk, TreadmillLock)
        self.assertTrue(lk.locked)

    def test_buttons(self):
        b = frames.parse_json('{"type":"button","button":"long_home"}')
        self.assertIsInstance(b, Button)
        self.assertEqual(b.button, "long_home")
        sb = frames.parse_json('{"type":"short_button","pressed":false}')
        self.assertIsInstance(sb, ShortButton)
        self.assertFalse(sb.pressed)

    def test_boundary(self):
        b = frames.parse_json('{"type":"boundary","changed":true,"omni_mode":true}')
        self.assertIsInstance(b, Boundary)
        self.assertTrue(b.changed)
        self.assertTrue(b.omni_mode)

    def test_client_state(self):
        c = frames.parse_json('{"type":"client","connected":true}')
        self.assertIsInstance(c, ClientState)
        self.assertTrue(c.connected)

    def test_scan_results(self):
        s = frames.parse_json('{"type":"scan_results","names":["A","B"]}')
        self.assertIsInstance(s, ScanResults)
        self.assertEqual(s.names, ["A", "B"])

    def test_settings(self):
        s = frames.parse_json('{"type":"settings","movement":true,"rest_url":"https://x"}')
        self.assertIsInstance(s, Settings)
        self.assertTrue(s.movement)
        self.assertEqual(s.rest_url, "https://x")

    def test_status(self):
        s = frames.parse_json(json.dumps({
            "type": "status", "client_connected": True, "omni_connected": True,
            "treadmill_name": "Omni One A1", "treadmill_locked": False,
            "steps": 10, "charge_level": 0.9, "charging": False,
            "x": 0.3, "y": 0.1, "speed": 0.32,
            "foot_trackers": {"left": {"connected": True, "battery_pct": 80}},
        }))
        self.assertIsInstance(s, Status)
        self.assertTrue(s.client_connected)
        self.assertTrue(s.omni_connected)
        self.assertEqual(s.treadmill_name, "Omni One A1")
        self.assertFalse(s.treadmill_locked)
        self.assertEqual(s.steps, 10)
        self.assertAlmostEqual(s.charge_level, 0.9)
        m = s.movement
        self.assertIsNotNone(m)
        self.assertAlmostEqual(m.x, 0.3)
        self.assertTrue(s.foot_trackers["left"]["connected"])

    def test_echo_and_error(self):
        e = frames.parse_json('{"type":"echo","cmd":"echo","t_ms":5}')
        self.assertIsInstance(e, Echo)
        err = frames.parse_json('{"type":"error","error":"unknown cmd: x"}')
        self.assertIsInstance(err, BridgeError)
        self.assertEqual(err.error, "unknown cmd: x")

    def test_unknown_type_raises(self):
        with self.assertRaises(ValueError):
            frames.parse_json('{"type":"bogus"}')

    def test_non_object_raises(self):
        with self.assertRaises(ValueError):
            frames.parse_json('[1,2,3]')


if __name__ == "__main__":
    unittest.main()
