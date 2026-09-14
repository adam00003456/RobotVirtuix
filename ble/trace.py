#!/usr/bin/env python3
"""Record every BLE notification the treadmill emits, with activity markers.

Usage:
    python3 trace.py <address-or-name> [--out traces/NAME.jsonl]
                     [--write UUID:HEX] [--write UUID:HEX ...]
                     [--poll-reads] [--reconnect]

Subscribes to every notify/indicate characteristic and writes JSONL rows:
    {"v":1,"t_ms":1234,"src":"notify","uuid":"...","data_hex":"...","marker":null}
Markers come from stdin while it runs — type a short label + Enter at each
activity change:

    idle          stand still (baseline)
    walk-slow     walk forward, casual pace
    walk-fast     walk forward, fast
    strafe-left   sidestep left
    strafe-right  sidestep right
    back          walk backward
    steps-20      count 20 deliberate steps
    stop          end

--write UUID:HEX sends bytes to a characteristic before subscribing (some
Nordic devices need a wake/register write before they stream; candidates come
from dump_gatt.py's writable characteristics).

Ctrl-C stops the trace cleanly.
"""

import argparse
import asyncio
import json
import os
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from blelib import require_bleak, looks_like_address, now_ms, iso_now, trace_row  # noqa: E402


def parse_write(spec: str):
    uuid, sep, hexpart = spec.partition(":")
    if not sep:
        raise argparse.ArgumentTypeError(f"--write expects UUID:HEX, got {spec!r}")
    try:
        return uuid.strip(), bytes.fromhex(hexpart.replace(" ", ""))
    except ValueError:
        raise argparse.ArgumentTypeError(f"bad hex in --write {spec!r}")


class _Disconnected(Exception):
    pass


async def main() -> int:
    require_bleak()
    from bleak import BleakScanner, BleakClient

    ap = argparse.ArgumentParser()
    ap.add_argument("target")
    ap.add_argument("--out", default=None)
    ap.add_argument("--write", action="append", type=parse_write, default=[])
    ap.add_argument("--poll-reads", action="store_true",
                    help="read all readable characteristics once per second")
    ap.add_argument("--reconnect", action="store_true")
    ap.add_argument("--timeout", type=float, default=20.0)
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

    out_path = args.out
    if out_path is None:
        os.makedirs("traces", exist_ok=True)
        safe = "".join(c if c.isalnum() else "-" for c in args.target)[:40]
        out_path = os.path.join("traces", f"{now_ms()}-{safe}.jsonl")
    out = open(out_path, "a", encoding="utf-8")

    loop = asyncio.get_running_loop()
    queue: asyncio.Queue = asyncio.Queue()

    def emit(row):
        queue.put_nowait(row)

    def on_notify(sender, data: bytearray):
        emit(trace_row(now_ms(), "notify", str(sender), bytes(data).hex()))

    meta = {
        "target": args.target,
        "address": target,
        "out": out_path,
        "started_at": iso_now(),
        "writes": [[u, h.hex()] for u, h in args.write],
        "poll_reads": args.poll_reads,
    }
    print(f"trace -> {out_path}")
    print("type activity labels + Enter at each change (idle / walk-slow / strafe-left / ...). Ctrl-C to stop.")

    threading.Thread(target=_stdin_markers, args=(loop, emit), daemon=True).start()
    emit(trace_row(0, "marker", "", "", "start"))

    try:
        while True:
            try:
                async with BleakClient(target, timeout=args.timeout) as client:
                    print("connected; subscribing to notifications")
                    count = 0
                    for service in client.services:
                        for char in service.characteristics:
                            props = char.properties
                            if "notify" in props or "indicate" in props:
                                try:
                                    await client.start_notify(char.uuid, on_notify)
                                    count += 1
                                    print(f"  subscribed {char.uuid} {sorted(props)}")
                                except Exception as e:
                                    print(f"  subscribe failed {char.uuid}: {e}")

                    for uuid, payload in args.write:
                        try:
                            await client.write_gatt_char(uuid, payload, response=True)
                            print(f"  wrote {len(payload)}B to {uuid}")
                            emit(trace_row(now_ms(), "write", uuid, payload.hex()))
                        except Exception as e:
                            print(f"  write failed {uuid}: {e}")

                    if count == 0:
                        print("no notifiable characteristics found; run dump_gatt.py "
                              "and consider a --write handshake first")

                    try:
                        await _drain(queue, out, client, emit, args.poll_reads)
                    except _Disconnected:
                        emit(trace_row(now_ms(), "marker", "", "", "disconnected"))
                        print("disconnected")
                        if not args.reconnect:
                            break
                        print("reconnecting in 3s ...")
                        await asyncio.sleep(3.0)
            except asyncio.CancelledError:
                raise
    except (KeyboardInterrupt, asyncio.CancelledError):
        pass
    finally:
        emit(trace_row(now_ms(), "marker", "", "", "stop"))
        _drain_remaining(queue, out)
        out.close()
        meta["ended_at"] = iso_now()
        meta_path = out_path.replace(".jsonl", ".meta.json")
        with open(meta_path, "w", encoding="utf-8") as f:
            json.dump(meta, f, indent=2)
        print(f"wrote {out_path} + {meta_path}")
    return 0


async def _drain(queue, out, client, emit, poll_reads):
    """Writes queued rows until the device drops the connection."""
    next_poll = 0.0
    while True:
        try:
            row = await asyncio.wait_for(queue.get(), timeout=0.25)
        except asyncio.TimeoutError:
            if not client.is_connected:
                raise _Disconnected()
            if poll_reads and time.monotonic() >= next_poll:
                next_poll = time.monotonic() + 1.0
                await _poll_reads(client, emit)
            continue
        out.write(json.dumps(row, separators=(",", ":")) + "\n")
        out.flush()


async def _poll_reads(client, emit):
    for service in client.services:
        for char in service.characteristics:
            if "read" not in char.properties:
                continue
            try:
                value = bytes(await client.read_gatt_char(char.uuid))
                emit(trace_row(now_ms(), "read", str(char.uuid), value.hex()))
            except Exception:
                pass


def _drain_remaining(queue, out):
    while True:
        try:
            row = queue.get_nowait()
        except asyncio.QueueEmpty:
            break
        out.write(json.dumps(row, separators=(",", ":")) + "\n")
    out.flush()


def _stdin_markers(loop, emit):
    """Reads labels from stdin (blocking) and posts marker rows onto the loop."""
    while True:
        try:
            line = input()
        except (EOFError, KeyboardInterrupt):
            return
        label = line.strip()
        if not label:
            continue

        def _post(label=label):
            emit(trace_row(now_ms(), "marker", "", "", label))

        loop.call_soon_threadsafe(_post)


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
