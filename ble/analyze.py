#!/usr/bin/env python3
"""Analyze BLE notification traces: which bytes encode the tracking data?

Usage:
    python3 analyze.py trace1.jsonl [trace2.jsonl ...]
    python3 analyze.py traces/*.jsonl --compare idle walk-slow --top 15
    python3 analyze.py traces/*.jsonl --uuid 0000xxxx-...

Stdlib only. Two modes:

1. Summary (default): markers, per-characteristic packet rates and payload
   size histograms — enough to spot the streaming characteristic.

2. --compare A B: compares every (offset, encoding) window inside fixed-size
   payloads between activity A and activity B. Reports windows that are flat
   during A but move during B — the classic signature of velocity fields and
   step counters. Encodings tried: u8/i16le/u16le/i32le/u32le/f32le/f64le.
"""

import argparse
import json
import math
import sys
from collections import defaultdict

sys.path.insert(0, __file__.rsplit("/", 1)[0] or ".")
from blelib import load_trace, decode, ENCODINGS  # noqa: E402


# ---------------------------------------------------------------- parsing

def rows_by_label(rows):
    """Splits a trace into {label: [notify rows]}, in arrival order."""
    out = defaultdict(list)
    current = "unmarked"
    for r in rows:
        if r.get("src") == "marker":
            current = r.get("marker") or current
            continue
        if r.get("src") in ("notify", "read"):
            out[current].append(r)
    return dict(out)


def data_bytes(row):
    try:
        return bytes.fromhex(row.get("data_hex", ""))
    except ValueError:
        return b""


def uuids(rows):
    seen = []
    for r in rows:
        u = r.get("uuid") or "(unknown)"
        if u not in seen:
            seen.append(u)
    return seen


def dominant_size(values):
    """Most common payload size, if it covers >= 80% of packets, else None."""
    if not values:
        return None
    counts = defaultdict(int)
    for v in values:
        counts[len(v)] += 1
    size, n = max(counts.items(), key=lambda kv: kv[1])
    return size if n >= 0.8 * len(values) else None


# ---------------------------------------------------------------- stats

def fmt_val(v):
    if isinstance(v, float):
        if abs(v) >= 1e6 or (v != 0 and abs(v) < 1e-4):
            return f"{v:.3e}"
        return f"{v:.4f}".rstrip("0").rstrip(".")
    return str(v)


def summarize(values):
    if not values:
        return "-"
    lo, hi = min(values), max(values)
    mean = sum(values) / len(values)
    return f"{fmt_val(lo)}..{fmt_val(hi)} (mean {fmt_val(mean)})"


def stdev(values):
    if len(values) < 2:
        return 0.0
    mean = sum(values) / len(values)
    return math.sqrt(sum((v - mean) ** 2 for v in values) / (len(values) - 1))


def is_counter(values):
    """Monotonic non-decreasing (steps-like) sequence over enough samples."""
    if len(values) < 8:
        return False
    increases = sum(1 for a, b in zip(values, values[1:]) if b > a)
    if increases < len(values) // 2:
        return False
    return all(b >= a for a, b in zip(values, values[1:]))


def valid_num(v):
    return v is not None and math.isfinite(v)


# ---------------------------------------------------------------- candidates

class Candidate:
    __slots__ = ("uuid", "offset", "encoding", "width", "score", "a_vals", "b_vals", "note")

    def __init__(self, uuid, offset, encoding, width, score, a_vals, b_vals, note):
        self.uuid = uuid
        self.offset = offset
        self.encoding = encoding
        self.width = width
        self.score = score
        self.a_vals = a_vals
        self.b_vals = b_vals
        self.note = note

    def line(self):
        a = summarize(self.a_vals)
        b = summarize(self.b_vals)
        return (f"  {self.uuid[:36]:<38} off={self.offset:<3} {self.encoding:<6} "
                f"w={self.width} score={self.score:.3g} {self.note:<14} "
                f"A[{a}] B[{b}]")


def find_candidates(uuid_a_rows, uuid_b_rows):
    """Given rows for two activities (per uuid), returns ranked candidates."""
    results = []
    for u in uuids(uuid_a_rows + uuid_b_rows):
        a_data = [data_bytes(r) for r in uuid_a_rows if (r.get("uuid") or "(unknown)") == u]
        b_data = [data_bytes(r) for r in uuid_b_rows if (r.get("uuid") or "(unknown)") == u]
        size = dominant_size(a_data + b_data)
        if size is None or size < 2 or len(a_data) < 5 or len(b_data) < 5:
            continue

        for encoding, width in ENCODINGS:
            # Naturally aligned offsets only (width-1/2/4/8 packing is how
            # Nordic firmware structures payloads); this avoids "straddle"
            # windows that mix adjacent fields.
            offsets = range(0, size - width + 1) if width == 1 \
                else range(0, size - width + 1, width)
            for offset in offsets:
                a_vals = [decode(d, offset, encoding) for d in a_data]
                b_vals = [decode(d, offset, encoding) for d in b_data]
                a_vals = [v for v in a_vals if valid_num(v)]
                b_vals = [v for v in b_vals if valid_num(v)]
                if len(a_vals) < 5 or len(b_vals) < 5:
                    continue

                spread_a, spread_b = stdev(a_vals), stdev(b_vals)
                mean_a = sum(a_vals) / len(a_vals)
                mean_b = sum(b_vals) / len(b_vals)
                separation = abs(mean_b - mean_a)
                note = ""

                if is_counter(b_vals) and not is_counter(a_vals):
                    score = 100.0 + spread_b
                    note = "counter"
                elif spread_a < 1e-9 and spread_b > 1e-9:
                    # Flat during A, moving during B: the classic data field.
                    score = spread_b + separation
                    note = "A-flat"
                elif separation > 4 * max(spread_a, spread_b, 1e-9):
                    score = min(separation, 1000.0)
                    note = "shifted"
                else:
                    continue

                # Reject absurd f32 decodings of constant/garbage regions.
                if encoding == "f32le" and (abs(mean_b) > 1e12 or abs(mean_a) > 1e12):
                    continue

                results.append(Candidate(u, offset, encoding, width, score, a_vals, b_vals, note))

    # Deduplicate near-identical overlapping windows of the same width.
    results.sort(key=lambda c: -c.score)
    kept = []
    for c in results:
        dup = False
        for k in kept:
            if k.uuid == c.uuid and k.width == c.width and k.encoding == c.encoding \
               and abs(k.offset - c.offset) < c.width:
                dup = True
                break
        if not dup:
            kept.append(c)
    return kept


# ---------------------------------------------------------------- reports

def report_summary(rows):
    print(f"{len(rows)} rows")
    labels = rows_by_label(rows)
    print("\nmarkers:")
    t0 = rows[0]["t_ms"] if rows else 0
    for r in rows:
        if r.get("src") == "marker":
            print(f"  +{r['t_ms'] - t0:>7}ms  {r.get('marker')}")

    print("\nper-characteristic packets:")
    per = defaultdict(list)
    for r in rows:
        if r.get("src") == "notify":
            per[r.get("uuid") or "(unknown)"].append(r)
    t_min = min((r["t_ms"] for r in rows), default=0)
    t_max = max((r["t_ms"] for r in rows), default=0)
    seconds = max((t_max - t_min) / 1000.0, 1e-9)
    for u, rs in sorted(per.items()):
        sizes = [len(data_bytes(r)) for r in rs]
        counts = defaultdict(int)
        for s in sizes:
            counts[s] += 1
        size_hist = ", ".join(f"{s}B x{n}" for s, n in sorted(counts.items()))
        rate = len(rs) / seconds
        print(f"  {u}\n      {len(rs)} pkts ({rate:.1f}/s) sizes: {size_hist}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("traces", nargs="+")
    ap.add_argument("--compare", nargs=2, metavar=("A", "B"), default=None)
    ap.add_argument("--top", type=int, default=20)
    ap.add_argument("--uuid", default=None, help="only analyze this characteristic uuid")
    args = ap.parse_args()

    rows = []
    for path in args.traces:
        rows.extend(load_trace(path))
    if not rows:
        print("no rows in trace(s)")
        return 1
    if args.uuid:
        rows = [r for r in rows if (r.get("uuid") or "") == args.uuid]

    report_summary(rows)

    if args.compare:
        label_a, label_b = args.compare
        by_label = rows_by_label(rows)
        a_rows = [r for r in by_label.get(label_a, []) if r.get("src") == "notify"]
        b_rows = [r for r in by_label.get(label_b, []) if r.get("src") == "notify"]
        if not a_rows or not b_rows:
            print(f"\n--compare needs notify rows labeled {label_a!r} and {label_b!r}; "
                  f"labels present: {sorted(by_label)}")
            return 1
        print(f"\ncandidates ({label_a} -> {label_b}), top {args.top}:")
        cands = find_candidates(a_rows, b_rows)
        if not cands:
            print("  none: payloads vary in size, or the activity difference didn't "
                  "show up. Try comparing other markers, or a longer capture.")
        for c in cands[: args.top]:
            print(c.line())
        print("\nheuristic: velocity (m/s) shows as f32le/i16le windows that are ~0 "
              "when idle and move with speed; steps as a monotonic counter (u16le/u32le).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
