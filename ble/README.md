# BLE reconnaissance: talking to the Omni One treadmill directly

**Your situation:** you have the Omni One treadmill but not the headset. The
headset is where Android runs, so the Messenger-IPC SDK (`../android/`) needs
it. The treadmill itself is embedded hardware — Nordic nRF52833/nRF5340 BLE
SoCs, left/right foot trackers on a Gazell 2.4 GHz link, its own battery,
button, and firmware (all confirmed from the system protocol, see
`../docs/PROTOCOL.md`). It talks to the world over Bluetooth LE.

This toolkit reverse-engineers that BLE interface from your Mac, so your
robot gets tracking data with **no Virtuix headset, no Unity, no Android**.

**Status: toolkit ready, on-device captures not yet done.** The scripts below
are the capture session; the analyzer will point at the bytes that encode
velocity and steps. Bring back the traces and the packet format gets
finalized.

## Prerequisites

```bash
python3 -m pip install bleak
```

Bluetooth on. Stand near the treadmill. Charger unplugged (safer to walk).

**Safety: use the support harness/ring.** Walking on the Omni surface without
the support is a fall hazard — every step of the capture session involves
walking. One person spotting, phone within reach, nothing on the floor.

## Step 1 — find it: `scan.py`

```bash
python3 scan.py --duration 15 --json-out scan.json
```

Everything visible is printed; candidates (name matches omni/virtuix/agg/
tracker, or Nordic manufacturer data, company id `0x0059`) are flagged.

- The base and possibly the two foot trackers may each advertise.
- **Nothing shows up?** Try the button on the treadmill (short press, then
  hold 5 s — the protocol has a "short button" concept, so the button is
  real). If a phone's nRF Connect app still sees nothing, the base may need
  power or a battery charge.

Note the address (on macOS: a UUID) and the exact name.

## Step 2 — map it: `dump_gatt.py`

```bash
python3 dump_gatt.py <address-or-name> --json-out gatt.json
```

Full service/characteristic dump: UUIDs, properties, readable values.

What you're looking for:

- A streaming characteristic: `notify`/`indicate` + often a big UUID like
  `6e400003-...` (Nordic-UART-style) or a custom 128-bit UUID.
- Writable characteristics: candidates for a wake/handshake register (some
  Nordic designs need a write before data flows).
- Readable values: battery percentages, firmware versions (nice for the doc).

If reads fail with pairing/auth errors: pair the treadmill to your Mac or
phone first (System Settings → Bluetooth on macOS, or bond via nRF Connect on
Android — bonding there is visible and reliable), then re-run.

## Step 3 — record it: `trace.py`

```bash
python3 trace.py <address-or-name> --reconnect --poll-reads
```

Subscribes to every notification and writes `traces/*.jsonl`. While it runs,
type these labels + Enter, 15–30 s each, **with the harness on**:

```
idle            stand still (baseline — do this first and well)
walk-slow       walk forward, casual pace
walk-fast       walk forward, fast but controlled
strafe-left     sidestep left
strafe-right    sidestep right
back            walk backward
steps-20        count 20 deliberate steps out loud
stop            (Ctrl-C also works)
```

Label discipline is what makes the analyzer work: one activity per label, no
transition time inside a segment, don't rush.

If nothing streams: many Nordic devices need a handshake write first. Take a
writable characteristic from `gatt.json` and retry:

```bash
python3 trace.py <address> --reconnect --write <uuid>:01
```

Try `01`, `0001`, `0100`, or plausible wake bytes. If the characteristic is
write-without-response only, that's a good sign it's a command register. Keep
notes of which UUID accepted which bytes — if a write wakes the stream,
that's the handshake, and we'll bake it into the final SDK.

## Step 4 — decode it: `analyze.py`

```bash
python3 analyze.py traces/*.jsonl                 # summary: rates, sizes, markers
python3 analyze.py traces/*.jsonl --compare idle walk-slow
python3 analyze.py traces/*.jsonl --compare idle walk-fast
python3 analyze.py traces/*.jsonl --compare strafe-left strafe-right
```

Candidates are printed ranked:

- `A-flat` windows: constant while idle, moving with the activity —
  velocity fields. A float32 near 0 (idle) that walks up to ~1–2 with pace is
  forward speed; a second independent one is lateral. The strafe-left vs
  strafe-right comparison splits forward from lateral definitively.
- `counter` windows: monotonic during `steps-20` — step counter.
- `shifted` windows: constants that change between activities — mode/status
  bytes.

Velocity may be encoded as float32 m/s (matching the headsets' units) or as a
scaled integer (e.g. mm/s or velocity*100 — the protocol has a configurable
`strafeMultiplier`, so scaled ints are plausible). Both decode variants are
reported; the one that tracks your actual pace is the answer.

## What to bring back

- `scan.json`, `gatt.json`, every `traces/*.jsonl` + `.meta.json`
- A note of your setup: which characteristic streamed, at what rate, whether a
  `--write` handshake was needed, pairing behavior.

With those, the direct-BLE SDK falls out: a small Python (bleak) client —
and later a C/C++ port for an MCU on the robot itself — that subscribes to
the streaming characteristic and publishes the same JSON frames the
headset-based bridge emits (`../python/omni_track/` consumes them unchanged).

## If BLE refuses to talk

Escalations, in order of effort:

1. **Bonding/auth wall:** the base may only accept bonded centrals. Pair
   through the OS or nRF Connect first (Step 2 note).
2. **App-level handshake:** a required write sequence (crypto nonce, magic
   bytes) before notifications flow. `dump_gatt.py` shows the writable
   registers; brute-forcing common Nordic wake patterns is Step 3.
3. **It stays silent:** the base may gate streaming on the foot trackers
   being connected/active — power the trackers, put them where they pair with
   the base (check their LEDs), and re-trace. If the Gazell link between
   trackers and base must be established first, that's visible as the base
   changing advertising/connection behavior.
4. **Last resort:** an HCI snoop of a *real* Omni One session (borrow a
   headset for an hour, enable Bluetooth HCI snoop logging on it via
   developer options, capture one game run) reveals the full protocol
   including any handshake — that log decodes everything at once.
