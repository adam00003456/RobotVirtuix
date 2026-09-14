#!/usr/bin/env python3
"""Connect to the treadmill and dump its full GATT profile.

Usage:
    python3 dump_gatt.py <address-or-name> [--json-out gatt.json]

For every service -> characteristic it records UUID, properties, handle (where
available), descriptors, and the value of readable characteristics. The output
is what we need to plan the notification trace (which characteristics stream
data) and spot write-controlled wake/handshake registers.

If pairing is required, the OS should prompt (macOS) or the device rejects
reads with an error — try bonding first with nRF Connect, then re-run.
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from blelib import require_bleak, looks_like_address, iso_now  # noqa: E402


def char_row(char):
    return {
        "uuid": str(char.uuid),
        "handle": getattr(char, "handle", None),
        "properties": sorted(char.properties),
        "descriptors": [str(d.uuid) for d in getattr(char, "descriptors", []) or []],
    }


async def main() -> int:
    require_bleak()
    from bleak import BleakScanner, BleakClient

    ap = argparse.ArgumentParser()
    ap.add_argument("target", help="BLE address or advertised name")
    ap.add_argument("--timeout", type=float, default=20.0)
    ap.add_argument("--json-out", default=None)
    args = ap.parse_args()

    target = args.target
    if not looks_like_address(target):
        print(f"resolving name {target!r} ...")
        devices = await BleakScanner.discover(timeout=10.0)
        match = next((d for d in devices if d.name and target.lower() in d.name.lower()), None)
        if match is None:
            print("device not found; run scan.py first")
            return 1
        target = match.address
        print(f"-> {match.name!r} at {target}")

    report = {"target": args.target, "address": target, "dumped_at": iso_now(), "services": []}

    async with BleakClient(target, timeout=args.timeout) as client:
        print(f"connected to {target}; services:")
        for service in client.services:
            print(f"service {service.uuid}")
            svc = {"uuid": str(service.uuid), "characteristics": []}
            for char in service.characteristics:
                row = char_row(char)
                print(f"    char {char.uuid} props={sorted(char.properties)}")
                if "read" in char.properties:
                    try:
                        value = bytes(await client.read_gatt_char(char.uuid))
                        row["value_hex"] = value.hex()
                        preview = "".join(chr(b) if 32 <= b < 127 else "." for b in value[:64])
                        print(f"        read ({len(value)}B): {value[:64].hex()}")
                        print(f"        ascii: {preview}")
                    except Exception as e:
                        row["read_error"] = str(e)
                        print(f"        read error: {e}")
                svc["characteristics"].append(row)
            report["services"].append(svc)

    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)
        print(f"wrote {args.json_out}")
    else:
        print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    import asyncio
    raise SystemExit(asyncio.run(main()))
