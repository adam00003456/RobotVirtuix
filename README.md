# OmniTrack — open-source Omni One tracking SDK for robotic teleoperation

An engine-agnostic, clean-room SDK for reading **Virtuix Omni One** treadmill
tracking data — no Unity, no Unreal, no proprietary middleware.

Built for **robotic teleoperation**: walk on the Omni One, drive a robot.

```
Omni One treadmill ─BLE→ Omni system app ─Messenger IPC→ omni-bridge (this repo)
                                                           │ JSON over UDP
                                                           ▼
                                          robot controller (any OS, any language)
```

## What's in here

| Path | What it is |
|------|------------|
| `docs/PROTOCOL.md` | **The reverse-engineered protocol**: architecture, transport, message framing, every tracking message with exact wire keys, and the full 272-code table extracted from the official SDK |
| `android/omni-track-core/` | Clean-room Java library: binds the Omni system service directly (`com.virtuix.android_middleware/.MainService`), speaks the tracking protocol, exposes a listener API. Zero dependencies, zero Virtuix code |
| `android/omni-bridge/` | Minimal Android app that runs on the headset and streams tracking as JSON over UDP. Builds to an installable APK with `./build_apk.sh` (no Gradle needed) |
| `python/omni_track/` | Robot-side client (stdlib only): parse frames, send commands |
| `python/examples/` | Live console viewer + ROS 2 `cmd_vel` teleop example |
| `analysis/` (git-ignored) | Reverse-engineering work products: extracted AARs, decompiled sources, code tables |

## Quickstart

**1. Build the bridge APK** (needs Android SDK build-tools 35 + platform 34):

```bash
cd android/omni-bridge
./build_apk.sh          # -> dist/omni-bridge.apk (debug-signed)
```

**2. Install and run on the Omni One headset** (developer mode + adb):

```bash
adb install dist/omni-bridge.apk
```

Launch "OmniTrack Bridge" from the headset launcher, set the target IP of your
robot PC (or leave broadcast), press **Start**.

**3. Consume it from your robot** (Python, any OS on the same LAN):

```python
from omni_track import OmniTrackClient, Movement, OmniTrackCommander

def on_frame(frame):
    if isinstance(frame, Movement):
        # x = forward m/s, y = lateral m/s, speed = |v|
        print(f"{frame.speed:.2f} m/s  v=({frame.x:+.2f}, {frame.y:+.2f})")

client = OmniTrackClient(on_frame=on_frame)
client.start()   # UDP :45454

commander = OmniTrackCommander("headset-ip")   # optional control channel
commander.request_status()                      # ask for a status snapshot
```

Or just watch it:

```bash
cd python
python3 examples/viewer.py --bridge 192.168.x.x
```

ROS 2 users: `python/examples/teleop_twist.py` maps walking to `geometry_msgs/Twist`.

## Tracking data

- **Movement** — view-relative velocity, m/s: `x` forward, `y` lateral (right+),
  `z` vertical (≈0). Includes `speed` and (with IMU pose enabled) `head_yaw_deg`.
- **Step count**, **treadmill scan/connect/disconnect**, **omni connected/disconnected**,
  **foot trackers** (connect events + battery %), **headset battery**, **treadmill lock**,
  **control/short buttons**, **boundary/calibration state**, **server settings**.

Full wire format and all commands: `docs/TELEOP.md`. Protocol internals: `docs/PROTOCOL.md`.

## Status & verification

Done and verified on this machine:

- Protocol reconstructed from the official Unity SDK 1.1.3 middleware AARs
  (full provenance table in `docs/PROTOCOL.md`).
- `omni-track-core` compiles against `android-34`; 59 JVM codec tests pass
  (`android/omni-track-core/run_tests.sh`).
- `omni-bridge` builds to a signed, verified APK (`aapt2 dump badging` clean).
- Python package: 19 unit + loopback tests pass.

**Not yet verified on real hardware** (no Omni One was connected for this
work). On-device checklist and known risks: `docs/TELEOP.md` §5. The big
unknowns: whether the Omni system service pushes `SET_CONTROLLER_DATA` to a
non-store client (entitlement gating), and exact axis signs/units of the
movement vector in IMU pose mode. Everything is parameterized to make that
first on-device session a debugging checklist, not a rewrite.

## Legal & safety

- Clean-room interoperability reconstruction of a protocol spoken by a device
  the researcher owns. **No Virtuix code is contained in this repository**;
  decompiled intermediates stay local (`analysis/` is git-ignored).
- Not affiliated with or endorsed by Virtuix. "Virtuix" and "Omni" are
  trademarks of Virtuix, used descriptively for interoperability.
- Robotic teleoperation is dangerous: wheel-up testing, deadman logic, and a
  physical e-stop first. The bridge sends no safety-rated guarantees — treat it
  as an input device, not a safety system.
- Respect the Omni One's treadmill lock; don't bypass safety features.

## License

To be chosen (recommend MIT/Apache-2.0 for the clean-room code). The
`unity-sdk-1.1.3/` original SDK remains under Virtuix's own license and is
excluded from this repository's source tree (see `.gitignore`).
