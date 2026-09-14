"""Shared helpers for the OmniTrack BLE reconnaissance tools.

blelib stays stdlib-only so the trace analyzer and its tests run anywhere;
only scan.py / dump_gatt.py / trace.py import bleak (pip install bleak).
"""

import json
import re
import struct
import sys
from datetime import datetime, timezone

TRACE_FORMAT_VERSION = 1

# Default name patterns worth flagging in scan output. The treadmill's
# advertised name is unknown until the first on-device scan, so scan.py
# prints EVERY device and highlights anything matching these.
NAME_PATTERNS = ["omni", "virtuix", "agg", "tracker"]


def now_ms() -> int:
    import time
    return int(time.time() * 1000)


def iso_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def looks_like_address(target: str) -> bool:
    """True for a MAC (aa:bb:cc:dd:ee:ff), a macOS CB UUID, or an integer."""
    if re.fullmatch(r"[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}", target or ""):
        return True
    if re.fullmatch(r"[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}", target or ""):
        return True
    return False


def name_matches(name: str, patterns) -> bool:
    if not name:
        return False
    low = name.lower()
    return any(p in low for p in patterns)


def require_bleak():
    try:
        import bleak  # noqa: F401
    except ImportError:
        sys.exit(
            "bleak is required for Bluetooth LE access:\n"
            "    python3 -m pip install bleak\n"
            "(On macOS bleak uses CoreBluetooth; enable Bluetooth for your terminal app.)"
        )


async def resolve_target(target: str, scan_seconds: float = 10.0):
    """Returns a BleakDevice for an address, or the best name match."""
    import bleak

    if looks_like_address(target):
        return bleak.BleakClient  # placeholder, caller uses address directly

    from bleak import BleakScanner

    print(f"scanning {scan_seconds:.0f}s for a device matching {target!r} ...")
    devices = await BleakScanner.discover(timeout=scan_seconds)
    low = (target or "").lower()
    for d in devices:
        if d.name and low in d.name.lower():
            return d
    print("no device matched; showing everything the scan saw:")
    for d in sorted(devices, key=lambda x: x.name or ""):
        print(f"  {d.name!r:<40} {d.address}")
    sys.exit(1)


# ---------------------------------------------------------------- trace I/O

def trace_row(t_ms: int, src: str, uuid: str, data_hex: str, marker=None) -> dict:
    return {
        "v": TRACE_FORMAT_VERSION,
        "t_ms": t_ms,
        "src": src,
        "uuid": uuid,
        "data_hex": data_hex,
        "marker": marker,
    }


def load_trace(path: str) -> list:
    rows = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as e:
                raise ValueError(f"{path}: bad JSONL line: {e}")
    return rows


# ---------------------------------------------------------------- decoding

_DECODERS = {
    "u8": lambda b, o: b[o],
    "i8": lambda b, o: struct.unpack_from("<b", b, o)[0],
    "u16le": lambda b, o: struct.unpack_from("<H", b, o)[0],
    "i16le": lambda b, o: struct.unpack_from("<h", b, o)[0],
    "u32le": lambda b, o: struct.unpack_from("<I", b, o)[0],
    "i32le": lambda b, o: struct.unpack_from("<i", b, o)[0],
    "f32le": lambda b, o: struct.unpack_from("<f", b, o)[0],
    "f64le": lambda b, o: struct.unpack_from("<d", b, o)[0],
}

# (encoding, width) pairs the analyzer tries at each offset.
ENCODINGS = [
    ("u8", 1),
    ("i16le", 2),
    ("u16le", 2),
    ("i32le", 4),
    ("u32le", 4),
    ("f32le", 4),
    ("f64le", 8),
]


def decode(data: bytes, offset: int, encoding: str):
    fn = _DECODERS[encoding]
    try:
        return fn(data, offset)
    except struct.error:
        return None
