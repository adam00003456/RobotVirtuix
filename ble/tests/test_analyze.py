"""Analyzer tests on synthetic traces with known hidden fields.

No Bluetooth needed: the point is to prove find_candidates recovers the
velocity float, step counter, and strafe int we planted.
"""

import json
import math
import os
import random
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import analyze  # noqa: E402
from blelib import trace_row  # noqa: E402


def synth_packet(velocity: float, steps: int, strafe: int) -> bytes:
    p = bytearray(20)
    struct.pack_into("<f", p, 0, velocity)          # hidden velocity, m/s
    struct.pack_into("<H", p, 4, steps)             # hidden step counter
    struct.pack_into("<h", p, 8, strafe)            # hidden strafe (x100)
    p[6:8] = b"\xaa\x55"                            # constant signature
    return bytes(p)


def synth_trace():
    """Returns trace rows: idle baseline then a walking segment."""
    rng = random.Random(1234)
    rows = [trace_row(0, "marker", "", "", "start")]

    t = 100
    rows.append(trace_row(t, "marker", "", "", "idle"))
    for _ in range(40):
        rows.append(trace_row(t, "notify", "6e400003-b5a3-f393-e0a9-e50e24dcca9e",
                              synth_packet(0.0, 100, 0).hex()))
        t += 20

    rows.append(trace_row(t, "marker", "", "", "walk-slow"))
    steps = 100
    for _ in range(40):
        rows.append(trace_row(t, "notify", "6e400003-b5a3-f393-e0a9-e50e24dcca9e",
                              synth_packet(1.2 + rng.uniform(-0.05, 0.05), steps, -50).hex()))
        steps += 1
        t += 20
    return rows


class TestParsing(unittest.TestCase):
    def test_rows_by_label(self):
        rows = synth_trace()
        by_label = analyze.rows_by_label(rows)
        self.assertEqual(len(by_label["idle"]), 40)
        self.assertEqual(len(by_label["walk-slow"]), 40)
        self.assertNotIn("start", by_label)

    def test_load_trace(self):
        with tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False) as f:
            for r in synth_trace():
                f.write(json.dumps(r) + "\n")
            path = f.name
        try:
            rows = analyze.load_trace(path)
            self.assertEqual(len(rows), 83)  # start + idle marker + 40 + walk marker + 40
        finally:
            os.unlink(path)

    def test_dominant_size(self):
        rows = [trace_row(1, "notify", "u", (b"\x00" * 20).hex()),
                trace_row(2, "notify", "u", (b"\x00" * 20).hex()),
                trace_row(3, "notify", "u", (b"\x00" * 20).hex()),
                trace_row(4, "notify", "u", (b"\x00" * 20).hex()),
                trace_row(5, "notify", "u", (b"\x00" * 8).hex())]
        data = [analyze.data_bytes(r) for r in rows]
        self.assertEqual(analyze.dominant_size(data), 20)  # 4/5 = 80% coverage

    def test_dominant_size_rejects_mixed(self):
        rows = [trace_row(1, "notify", "u", (b"\x00" * 20).hex()),
                trace_row(2, "notify", "u", (b"\x00" * 20).hex()),
                trace_row(3, "notify", "u", (b"\x00" * 8).hex())]
        data = [analyze.data_bytes(r) for r in rows]
        self.assertIsNone(analyze.dominant_size(data))  # 2/3 = 67% < 80%


class TestCandidates(unittest.TestCase):
    def setUp(self):
        rows = synth_trace()
        by_label = analyze.rows_by_label(rows)
        self.a = by_label["idle"]
        self.b = by_label["walk-slow"]
        self.cands = analyze.find_candidates(self.a, self.b)

    def test_finds_velocity_float(self):
        hits = [c for c in self.cands
                if c.encoding == "f32le" and c.offset == 0
                and c.note in ("A-flat", "shifted")]
        self.assertTrue(hits, "f32le velocity @0 not found")
        mean_b = sum(hits[0].b_vals) / len(hits[0].b_vals)
        self.assertAlmostEqual(mean_b, 1.2, delta=0.1)

    def test_finds_step_counter(self):
        hits = [c for c in self.cands
                if c.encoding in ("u16le", "u32le") and c.offset == 4
                and c.note == "counter"]
        self.assertTrue(hits, "u16le counter @4 not found")
        self.assertAlmostEqual(hits[0].b_vals[-1] - hits[0].b_vals[0], 39)

    def test_finds_strafe_int(self):
        hits = [c for c in self.cands
                if c.encoding == "i16le" and c.offset == 8]
        self.assertTrue(hits, "i16le strafe @8 not found")
        self.assertEqual(hits[0].b_vals[0], -50)
        self.assertEqual(hits[0].a_vals[0], 0)

    def test_constant_signature_not_flagged(self):
        # bytes 6-7 (0xAA55) must not appear as a candidate window start.
        bad = [c for c in self.cands if c.offset == 6 and c.encoding in ("u8", "u16le")]
        for c in bad:
            spread_a = analyze.stdev(c.a_vals)
            spread_b = analyze.stdev(c.b_vals)
            self.assertLessEqual(max(spread_a, spread_b), 1e-9)

    def test_is_counter(self):
        self.assertTrue(analyze.is_counter(list(range(100, 160))))
        self.assertFalse(analyze.is_counter([5, 3, 8, 2, 9, 1, 4, 7, 6, 8, 2]))
        self.assertFalse(analyze.is_counter([7] * 10))


if __name__ == "__main__":
    unittest.main()
