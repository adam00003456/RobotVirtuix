# Robotic teleoperation guide

End-to-end recipe for driving a robot from an Omni One treadmill using the
OmniTrack bridge.

## 1. Pipeline

```
Omni One treadmill (BLE) → Omni system app (MainService, on headset)
    → omni-bridge app (this repo, on headset, JSON over UDP)
    → your robot controller (any OS / language)
```

The treadmill is physically driven by a person; the robot mirrors their
gait. The tracking solution is **view-relative** (head + torso), so the
natural mapping is:

| Omni | Robot (holonomic) |
|------|--------------------|
| `x` (forward, m/s) | `linear.x` |
| `y` (lateral, right+) | `linear.y` |
| `head_yaw_deg` (optional) | `angular.z` (scaled) |
| `speed ≈ 0` | deadman: stop |

Differential-drive robots: fold `y` into `angular.z` or ignore it.

## 2. Bridge data frames (UDP, one JSON object per datagram)

Emitted to the configured host:port (default broadcast `45454`):

```jsonc
{"type":"movement","t_ms":1690000000123,"x":1.0,"y":0.0,"z":0.0,"speed":1.0,"head_yaw_deg":12.5}
{"type":"step_count","t_ms":...,"count":1234}
{"type":"status","t_ms":...,"client_connected":true,"omni_connected":true,
 "treadmill_name":"Omni One A1","treadmill_locked":false,
 "charge_level":0.87,"charging":false,
 "foot_trackers":{"left":{"connected":true,"battery_pct":80},"right":{"connected":true,"battery_pct":80}},
 "steps":1234,"x":0.3,"y":0.1,"speed":0.32,"last_movement_age_ms":12,"calibration_known":true}
{"type":"scan_results","t_ms":...,"names":["Omni One A1"]}
{"type":"treadmill_connected","t_ms":...,"name":"Omni One A1"}
{"type":"treadmill_disconnected","t_ms":...}
{"type":"omni_connected"/"omni_disconnected","t_ms":...}
{"type":"foot_tracker","t_ms":...,"side":"left","connected":true}
{"type":"foot_tracker_battery","t_ms":...,"left_percentage":80,"right_percentage":80}
{"type":"battery","t_ms":...,"charge_level":0.87,"charging":false}
{"type":"treadmill_locked"/"treadmill_unlocked","t_ms":...}
{"type":"button","t_ms":...,"button":"pause"}          // or "long_home"
{"type":"short_button","t_ms":...,"pressed":true}
{"type":"boundary","t_ms":...,"changed":true,"omni_mode":true}
{"type":"calibration_result","t_ms":...}
{"type":"client","t_ms":...,"connected":true}
{"type":"settings","t_ms":...,"movement":true,"rest_url":"https://api.omni.virtuix.com"}
{"type":"error","t_ms":...,"error":"..."}
```

`movement` frames are throttled to the newest value at a configurable max
rate (default 60 Hz); `status` is a 1 Hz heartbeat snapshot.

## 3. Bridge commands (UDP, default port `45455`)

```jsonc
{"cmd":"echo"}                     // reply: same payload + "type":"echo" (latency test)
{"cmd":"request_status"}           // reply: one "status" frame
{"cmd":"scan"}                     // -> scan_results frames follow
{"cmd":"connect","name":"..."}     // -> treadmill_connected follows
{"cmd":"disconnect"}
{"cmd":"get_battery"}
{"cmd":"get_steps"}
{"cmd":"get_foot_tracker_battery"}
{"cmd":"get_omni_status"}
{"cmd":"get_lock_status"}
{"cmd":"calibration_complete"}     // tell the server calibration finished (FORCE_CALIBRATION_COMPLETE)
```

Python:

```python
from omni_track import OmniTrackCommander
c = OmniTrackCommander("192.168.1.42")
c.echo()              # connectivity + RTT probe
c.scan()              # kick off a treadmill scan
c.connect("Omni One A1")
```

## 4. Head pose sources

The tracking solution needs the headset pose (`SET_PLAYER_DATA`,
PROTOCOL.md §5.1) to produce view-relative velocity. The official Unity SDK
sends the XR pose every frame. The bridge can't run an XR engine, so it offers:

- **IMU** (default): head roll/pitch/yaw from the standard Android
  rotation-vector sensor at 50 Hz. No XR runtime needed; reproduces the
  dominant term (yaw). Position is sent as zeros.
- **Zero**: fixed straight-ahead reference. Movement still reports, but
  relative to a fixed frame — useful for verifying the treadmill push before
  debugging pose plumbing.

For pixel-perfect parity with the official SDK, run the bridge code inside a
real XR app (Pico/OpenXR) and feed the tracked pose to
`OmniTrackClient.sendPlayerPose(...)` — the API takes any pose source.

## 5. First on-device session: verification checklist

No hardware was available during reconstruction; expect these checkpoints:

1. **Bind + registration.** `adb logcat -s OmniTrackBridge` on the headset.
   Look for "connected to Omni middleware". If `onServiceBindFailed` repeats,
   check the system app exists: `adb shell pm list packages | grep android_middleware`.
2. **Settings handshake.** A `settings` frame (and log line with
   `movement=` flag) should arrive right after connecting. If `movement` is
   false server-side, tracking may be disabled for this client.
3. **Registration variant.** If you bind but get no events, try
   `client.setRegisterAsUnityClient(false)` to send `REGISTER_CLIENT` (1)
   instead of `REGISTER_UNITY_CLIENT` (10007).
4. **Entitlement gating.** A sideloaded bridge is not a store product. If
   everything connects but `SET_CONTROLLER_DATA` never arrives, check logcat
   for entitlement handling in the system app; the platform services may gate
   tracking on `ENTITLEMENT_CHECK`. (The protocol itself has no such gate in
   the client-visible enum; the gate, if any, is inside the system app.)
5. **Movement vector signs/units.** Walk forward: expect `x > 0`. Strafe
   right: `y > 0`. Speed magnitude should read your walking speed in m/s
   (walk a measured distance at a steady pace and sanity-check the integral).
   With **Zero** pose, the frame is fixed; with **IMU**, turn your head while
   walking straight and watch the vector rotate with your view.
6. **Treadmill flow.** `scan` → connect to the discovered name → watch for
   `treadmill_connected`, then movement frames.

## 6. Safety

- **Wheels off the ground** for the first runs. E-stop reachable at all times.
- Publish zero command on staleness (the ROS 2 example does this at 0.5 s).
- Clamp speeds (`--max-speed`); use a deadband (`--deadband`) so treadmill
  noise doesn't creep the robot.
- The Omni's own treadmill lock (`treadmill_locked` frames) is the human-side
  safety stop — surface it in your UI and never bypass it.
