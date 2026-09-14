# OmniTrack — open-source Omni One tracking SDK for robotic teleoperation

An engine-agnostic, clean-room SDK for reading **Virtuix Omni One** treadmill
tracking data — no Unity, no Unreal, no proprietary middleware.

Built for **robotic teleoperation**: walk on the Omni, drive a robot.

## Pick your path

**Path A — treadmill only (no headset).** The treadmill is embedded hardware
(Nordic BLE SoCs; left/right foot trackers on a 2.4 GHz Gazell link; battery,
button, lock). It exposes its data over Bluetooth LE, and that's the cleanest
teleop architecture: treadmill-frame velocity straight to your robot, no
headset, no Android anywhere. The `ble/` toolkit discovers and decodes that
interface on your hardware:

```bash
cd ble
python3 -m pip install bleak
python3 scan.py            # find the treadmill + trackers
python3 dump_gatt.py NAME # map its services
python3 trace.py NAME     # walk on it with labeled activities
python3 analyze.py traces/*.jsonl --compare idle walk-slow
```

Full walkthrough and contingencies: `ble/README.md`. Everything downstream
(`python/omni_track`) consumes the decoded data unchanged.

**Path B — with the Omni One headset.** Android runs on the headset
(Pico-based), and the pre-installed system app
`com.virtuix.android_middleware/.MainService` owns the treadmill link and
computes view-relative movement. The `android/` packages replace Unity
entirely:

```
treadmill ─BLE→ headset system app ─Messenger IPC→ omni-bridge (this repo)
                                                 │ JSON over UDP
                                                 ▼
                                   robot controller (any OS)
```

- `android/omni-track-core` — clean-room Java client for the Messenger
  protocol. Zero dependencies, zero Virtuix code.
- `android/omni-bridge` — headset app streaming JSON over UDP; builds to an
  installable APK with plain build-tools (`./build_apk.sh`, no Gradle).

## Robot-side client (both paths)

```python
from omni_track import OmniTrackClient, Movement, OmniTrackCommander

def on_frame(frame):
    if isinstance(frame, Movement):
        print(f"{frame.speed:.2f} m/s  v=({frame.x:+.2f}, {frame.y:+.2f})")

client = OmniTrackClient(on_frame=on_frame)
client.start()   # UDP :45454
```

Examples: `python/examples/viewer.py` (console viewer) and
`python/examples/teleop_twist.py` (ROS 2 `cmd_vel` teleop).

## Repository layout

| Path | What it is |
|------|------------|
| `docs/PROTOCOL.md` | The reverse-engineered Messenger protocol: transport, framing, handshake, every tracking message with exact wire keys, all 272 codes, provenance |
| `docs/TELEOP.md` | Teleop guide: bridge frames, commands, pose sources, on-device checklist, safety |
| `ble/` | Direct-BLE reconnaissance toolkit (scan → GATT dump → labeled traces → candidate field analysis) |
| `android/omni-track-core/` | Engine-agnostic Java client for the headset protocol (Path B) |
| `android/omni-bridge/` | Headset streaming app (Path B) |
| `python/omni_track/` | Robot-side client (stdlib only) |
| `analysis/` (git-ignored) | Reverse-engineering work products; stays local |

## Status & verification

Done and verified on this machine:

- Protocol reconstructed from the official Unity SDK 1.1.3 middleware AARs
  (full provenance in `docs/PROTOCOL.md`).
- `omni-track-core` compiles against `android-34`; 59 JVM codec tests pass.
- `omni-bridge` builds to a signed APK (`apksigner verify` + badging clean).
- Python: 19 unit + loopback tests pass.
- BLE analyzer: 9 synthetic-trace tests pass — it demonstrably recovers
  planted float32 velocity fields, step counters, and int16 strafe fields.

**Not yet verified on real hardware** (no Omni One connected for this work):

- Path B on-device behavior: entitlement gating, event push to sideloaded
  clients, IMU-pose axis signs — checklist in `docs/TELEOP.md` §5.
- Path A entirely: the BLE interface (GATT layout, packet format, pairing,
  possible handshake) must be captured on your treadmill. The toolkit is the
  capture session; `ble/README.md` walks through it and the contingencies.

## Legal & safety

- Clean-room interoperability reconstruction of a protocol spoken by a device
  the researcher owns. **No Virtuix code is in this repository**; decompiled
  intermediates stay local (`analysis/` is git-ignored).
- Not affiliated with or endorsed by Virtuix. "Virtuix" and "Omni" are
  trademarks of Virtuix, used descriptively for interoperability.
- Walking on the Omni requires its support harness — during capture sessions
  and always. Robotic teleoperation: wheels-up first, deadman logic, physical
  e-stop; this SDK is an input device, not a safety system.
- Respect the treadmill lock; don't bypass safety features.

## License

To be chosen (recommend MIT/Apache-2.0 for the clean-room code). The
original `unity-sdk-1.1.3/` remains under Virtuix's own license and is
excluded from this repository's source tree (see `.gitignore`).
