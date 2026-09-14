#!/usr/bin/env python3
"""BLE scanner: find the Omni One treadmill and its foot trackers.

Usage:
    python3 scan.py [--duration 15] [--json-out scan.json]

Prints every visible device, highlighting name matches (omni/virtuix/agg/
tracker) and anything advertising Nordic Semiconductor manufacturer data
(company id 0x0059 — the treadmill's SoCs are nRF52833/nRF5340).

Notes:
- The treadmill may only advertise in a pairing/connectable mode; if nothing
  shows up, try its button (short press, then long press) and re-scan.
- Foot trackers are separate BLE devices; they may appear independently.
- On macOS, addresses are CoreBluetooth UUIDs — use them verbatim with the
  other tools.
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from blelib import require_bleak, name_matches, NAME_PATTERNS, iso_now  # noqa: E402

NORDIC_COMPANY_ID = 0x0059


async def main() -> int:
    require_bleak()
    from bleak import BleakScanner

    ap = argparse.ArgumentParser()
    ap.add_argument("--duration", type=float, default=15.0)
    ap.add_argument("--json-out", default=None)
    args = ap.parse_args()

    print(f"scanning for {args.duration:.0f}s ...")
    found = await BleakScanner.discover(timeout=args.duration, return_adv=True)

    rows = []
    for address, (device, adv) in sorted(found.items(), key=lambda kv: getattr(kv[1][1], "rssi", -999) or -999):
        name = getattr(adv, "local_name", None) or device.name or "(no name)"
        rssi = getattr(adv, "rssi", None)
        manufacturer = dict(getattr(adv, "manufacturer_data", {}) or {})
        services = [str(u) for u in (getattr(adv, "service_uuids", None) or [])]
        nordic = NORDIC_COMPANY_ID in manufacturer
        interesting = name_matches(name, NAME_PATTERNS) or nordic

        flag = " <== candidate" if interesting else ""
        print(f"{rssi if rssi is not None else '  ?':>4}  {name!r:<40} {address}{flag}")
        if manufacturer:
            for cid, blob in manufacturer.items():
                print(f"        manufacturer 0x{cid:04x} ({len(blob)}B): {blob.hex()}")
        if services:
            print(f"        services: {', '.join(services)}")

        rows.append({
            "address": address,
            "name": name,
            "rssi": rssi,
            "manufacturer_data": {f"0x{cid:04x}": blob.hex() for cid, blob in manufacturer.items()},
            "service_uuids": services,
            "candidate": bool(interesting),
        })

    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as f:
            json.dump({"scanned_at": iso_now(), "devices": rows}, f, indent=2)
        print(f"wrote {args.json_out}")

    if not any(r["candidate"] for r in rows):
        print("\nno candidates: re-run with a longer duration, wake the treadmill "
              "(button press), and stand near it. If a phone's nRF Connect app sees it, "
              "note the exact advertised name and re-scan.")
    return 0


if __name__ == "__main__":
    import asyncio
    raise SystemExit(asyncio.run(main()))
