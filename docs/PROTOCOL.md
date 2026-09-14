# Omni One Tracking Protocol (Reverse-Engineered)

Clean-room reconstruction of the protocol the Virtuix Omni One system software uses
to deliver treadmill tracking data to client applications. Reconstructed from the
Unity SDK 1.1.3 middleware AARs (`omni_one_unity_4800x` / `omni_one_unity_4859x`)
shipped in `unity-sdk-1.1.3`. No Virtuix source code is reproduced in this
repository; this document describes the observed wire behavior for interoperability.

**Scope:** tracking-relevant messages (movement, steps, treadmill connection,
foot trackers/pods, battery, lock, calibration/boundary, buttons, connection
lifecycle). Social/store services (achievements, leaderboards, friends,
multiplayer, DLC, VOIP, ...) are out of scope and only listed in the appendix.

---

## 1. Architecture

```
+-------------------+  BLE  +--------------------------------+  Messenger IPC  +-----------------+
|  Omni One         |<----->|  Omni system app               |<--------------->|  Your client    |
|  treadmill + pods |       |  com.virtuix.android_middleware|   (bind +       |  (any Android   |
|  + foot trackers  |       |  /.MainService                 |    Messages)    |   app, no Unity) |
+-------------------+       +--------------------------------+                 +-----------------+
        ^                                                                                   |
        |                                                                                   | JSON/UDP
        v                                                                                   v
   hardware sensors                                                              robot controller (any OS)
```

The official Unity SDK is a three-layer stack:

1. **Unity C#** (`OmniOneSDK/Runtime/...`) — engine facade.
2. **`com.virtuix.omni_one_unity` AAR** — in-process Android glue
   (`AndroidMiddlewareService`), also written against the protocol below. It
   contains **no** Unity classes; the only Unity dependency is the C# layer
   calling into it via JNI.
3. **`com.virtuix.android_middleware` system app** — pre-installed on the Omni
   One headset (Pico-based Android). It owns the Bluetooth link to the
   treadmill and foot trackers, computes the tracking solution, and exposes it
   through a bound Android `Service`.

This SDK replaces layers 1 and 2: a clean-room client that binds directly to
layer 3. It contains no Virtuix code.

## 2. Transport

Android bound-service Messenger IPC:

- **Target:** explicit intent to
  `ComponentName("com.virtuix.android_middleware", "com.virtuix.android_middleware.MainService")`,
  bound with `Context.BIND_AUTO_CREATE`.
- On `onServiceConnected(IBinder)` the binder **is** the server's `Messenger`
  (`new Messenger(service)`).
- The client hosts its own `Messenger` (a `Handler`) and sets
  `Message.replyTo` on **every** outgoing message. The server registers that
  client Messenger and pushes events to it.
- The server can die/restart; the client should re-bind (Virtuix retries after
  5 seconds) and queue outgoing messages while disconnected, flushing them on
  reconnect.

## 3. Message framing

Every `android.os.Message` on this protocol:

| Field      | Meaning |
|------------|---------|
| `what`     | Message type code (see tables below) |
| `arg1`     | low 32 bits of a 64-bit correlation id (`sourceId`) |
| `arg2`     | high 32 bits of `sourceId` |
| `data`     | `Bundle` payload; typed keys per message (some carry a single `json_string` key with a Gson JSON object) |
| `replyTo`  | client `Messenger` (set on all client→server messages) |

`sourceId` encoding (`DtoMessage.Companion`):

```java
long sourceId = ((long) arg2 << 32) + (arg1 & 0xFFFFFFFFL);
arg1 = (int)(sourceId & 0xFFFFFFFFL);
arg2 = (int)(sourceId >>> 32);
```

Fire-and-forget messages use `sourceId = Long.MIN_VALUE` (`arg1 = 0`,
`arg2 = 0x80000000`). Request/response pairs echo the request's `sourceId` in
the response so late responses can be correlated (the tracking events below are
pushes and normally ignore it).

## 4. Connection lifecycle

```
client                                  server (MainService)
  |-- bindService(MainService) ------------->|
  |<-- onServiceConnected(binder) -----------|
  |-- REGISTER_UNITY_CLIENT (10007) -------->|   (replyTo = client messenger)
  |-- CONFIGURATION (100037, empty) -------->|   (request settings)
  |<-- SETTINGS (100038) -------------------|   (bundle: MOVEMENT, LOG_SETTINGS,
  |                                            PERMISSIONS, REST_URL,
  |                                            flag_smith_environment_key,
  |                                            forward_exception, ...)
  |
  |      ... normal operation: SET_PLAYER_DATA in, events out ...
  |
  |-- UNREGISTER_UNITY_CLIENT (10008) ----->|
  |-- unbindService ------------------------>|
```

Notes:

- The Virtuix client sends `REGISTER_UNITY_CLIENT` (10007), not
  `REGISTER_CLIENT` (1). The difference is not observable from the client side;
  both exist in the enum. `OmniTrackClient` sends 10007 to mirror the reference
  client and can be configured to send 1.
- On `onServiceDisconnected` the reference client clears its server messenger,
  cancels pings, and re-binds after 5 s. There is also a `PING` (777777777)
  message; the reference client disables pings (`SEND_PINGS = false`).

## 5. Movement (the tracking loop)

The treadmill solution is **view-relative velocity** in meters per second,
taking both the head and the harness/torso orientation into account (per
Virtuix's own Unity docs: walking forward at 1 m/s with head and torso aligned
returns `(0, 0, 1)`; turning the head 45° while walking straight rotates the
vector to stay camera-relative, e.g. `(0.71, 0, 0.71)`).

### 5.1 Client → server: `SET_PLAYER_DATA` (what = 4)

The client feeds the headset pose each frame (the Unity SDK does this at
render rate, 72–90 Hz; 30–50 Hz is a reasonable lower bound for teleop):

| Bundle key      | Type  | Meaning (Omni convention) |
|-----------------|-------|-----------------------------|
| `rotation_x`    | float | HMD **roll**  (Unity euler Z) |
| `rotation_y`    | float | HMD **pitch** (Unity euler X) |
| `rotation_z`    | float | HMD **yaw**   (Unity euler Y) |
| `position_x`    | float | HMD position (Unity Z) |
| `position_y`    | float | HMD position (Unity X) |
| `position_z`    | float | HMD position (Unity Y) |

The axis shuffle above is exact: the Unity SDK sends
`getMovementData(euler.z, euler.x, euler.y, pos.z, pos.x, pos.y)`.

### 5.2 Server → client: `SET_CONTROLLER_DATA` (what = 3)

Pushed whenever the tracking solution updates (treadmill sensor rate; expect
tens of Hz):

| Bundle key      | Type  | Meaning |
|-----------------|-------|---------|
| `controller_x`  | float | **forward** velocity (maps to Unity +Z) |
| `controller_y`  | float | **lateral/strafe** velocity (maps to Unity +X) |
| `controller_z`  | float | vertical component (maps to Unity +Y; ~0 on a treadmill) |

`speed = sqrt(x² + y² + z²)` in m/s. Signs follow the reference client's mapping
(`Unity movement = (controller_y, controller_z, controller_x)`); verify signs on
hardware before wiring motors.

### 5.3 Units and caveats

- Units are m/s (documented by Virtuix for the Unity API which passes these
  floats through unmodified).
- `strafeMultiplier` from `SETTINGS` suggests lateral gain is tunable on the
  server side.
- If no treadmill is connected the reference client reports (0, 0, 0).

## 6. Treadmill connection

| Direction | Message | what | Payload |
|-----------|---------|------|---------|
| C → S | `OMNI_ONE_TREADMILL_SCAN` | 160019 | — |
| S → C | `OMNI_ONE_SCAN_RESULT` | 31000045 | `DEVICE_NAME`: `ArrayList<String>` of discovered treadmills |
| C → S | `GET_OMNI_ONE_CONNECTED_TREADMILL_NAME` | 160017 | — |
| S → C | `OMNI_ONE_CONNECTED_DEVICE_NAME` | 160018 | `TREADMILL_NAME`: String |
| C → S | `CONNECT_TO_TREADMILL` | 160016 | `TREADMILL_NAME`: String |
| C → S | `DISCONNECT_FROM_TREADMILL` | 160020 | — |
| S → C | `DISCONNECTED_FROM_TREADMILL` | 160021 | — |
| C → S | `START_TREADMILL_SCAN` | 31000018 | — (used by the platform layer; the 160019 scan is the one exercised by the connection UI) |

The service constructor in the reference client sends
`GET_OMNI_ONE_CONNECTED_TREADMILL_NAME` once on startup, so a freshly bound
client learns the connected treadmill without asking.

## 7. Omni connection status

| Direction | Message | what | Payload |
|-----------|---------|------|---------|
| C → S | `GET_OMNI_STATUS` | 31000011 | — |
| S → C | `OMNI_CONNECTED` | 31000012 | — |
| S → C | `OMNI_DISCONNECTED` | 31000013 | — |

"Omni status" is the overall treadmill connection state.

## 8. Foot trackers (the treadmill "pods")

The Omni One's foot trackers are the left/right sensor pods.

| Direction | Message | what | Payload |
|-----------|---------|------|---------|
| S → C | `LEFT_FOOT_TRACKER_CONNECTED` / `RIGHT_FOOT_TRACKER_CONNECTED` | 31000025 / 31000024 | — |
| S → C | `LEFT_FOOT_TRACKER_DISCONNECTED` / `RIGHT_FOOT_TRACKER_DISCONNECTED` | 31000023 / 31000022 | — |
| C → S | `GET_FOOT_TRACKER_BATTERY_STATUS` | 31000027 | — |
| S → C | `FOOT_TRACKER_BATTERY_STATUS` | 31000026 | `right_percentage`: int, `left_percentage`: int |

## 9. Battery

| Direction | Message | what | Payload |
|-----------|---------|------|---------|
| C → S | `GET_BATTERY_STATUS` | 16008 | — |
| S → C | `BATTERY_STATUS` | 16009 | `charge_level`: float (0–1), `charging`: boolean |

## 10. Treadmill lock

The treadmill can be software-locked (safety lock so the belt won't drive).

| Direction | Message | what | Payload |
|-----------|---------|------|---------|
| C → S | `GET_TREADMILL_LOCK_STATUS` | 31000048 | — |
| S → C | `TREADMILL_LOCKED` | 31000026-style: 31000046 | — |
| S → C | `TREADMILL_UNLOCKED` | 31000047 | — |

## 11. Step count

| Direction | Message | what | Payload |
|-----------|---------|------|---------|
| C → S | `REQUEST_STEP_COUNT` | 10009 | — |
| S → C | `CURRENT_STEP_COUNT` | 100010 | `step_count`: int |

## 12. Buttons

| Direction | Message | what | Payload |
|-----------|---------|------|---------|
| S → C | `CONTROL_BUTTON_PRESSED` | 5001 | `button_type`: int (1 = pause, 2 = long home, 999999 = unknown) |
| C → S | `WAS_SHORT_BUTTON_PRESSED` | 31000028 | — |
| S → C | `SHORT_BUTTON_PRESSED` / `SHORT_BUTTON_NOT_PRESSED` | 31000029 / 31000030 | — |

## 13. Calibration / boundary

The Omni tracks a room-scale boundary; tracking data is valid after
calibration. The boundary-change query tells a client whether it must
recalibrate.

| Direction | Message | what | Payload |
|-----------|---------|------|---------|
| C → S | `WAS_BOUNDARY_CHANGED` | 31000031 | — |
| S → C | `BOUNDARY_CHANGED_OMNI` | 31000032 | — (boundary changed while on the Omni → recalibrate) |
| S → C | `BOUNDARY_CHANGED_ROOM` | 31000033 | — |
| S → C | `BOUNDARY_NOT_CHANGED` | 31000034 | — (still calibrated) |
| C → S | `FORCE_BOUNDARY_CHANGED_TO_OMNI` | 31000044 | — |
| C → S | `FORCE_CALIBRATION_COMPLETE` | 100056 | — (client finished its in-app calibration) |
| S → C | `CALIBRATION_RESULT` | 10006 | — (legacy; result of `CALIBRATE_OMNI` 10005) |
| S → C | `REQUEST_UNITY_CALIBRATION_AND_LAUNCH_PRODUCT` | 100055 | `json_string`: `{"packageName": "...", "productId": "..."}` |
| S → C | `FORCE_UNITY_CALIBRATION` | 100057 | — |

The launcher's calibration screen is started with the Android intent action
`com.virtuix.launcher.CALIBRATION_SCREEN` (extras `packageName`, `productId`) —
launcher-side, not needed for raw tracking.

## 14. Errors

| Direction | Message | what | Payload |
|-----------|---------|------|---------|
| S → C | `UNKNOWN_EXCEPTION` | 13001 | `message`: String, `stack_trace`: String |
| S → C | `KNOWN_EXCEPTION` | 13002 | `type`: int, `message`, `stack_trace` |

## 15. Session / focus (optional for teleop)

| Direction | Message | what | Notes |
|-----------|---------|------|-------|
| C → S | `SPLASH_SCREEN_COMPLETE` | 160010 | startup handshake step |
| C → S | `VR_ENVIRONMENT_HAS_FOCUS` / `VR_ENVIRONMENT_LOST_FOCUS` | 160014 / 160015 | focus tracking |
| C → S | `START_GAME_SESSION` | 2000003 | `json_string`: `{productId, startTime (ISO-8601 UTC), packageName}` |
| C → S | `STOP_GAME_SESSION` | 2000004 | — |
| C → S | `SDK_GAME_STOP` | 2000005 | — |
| C → S | `PING` | 777777777 | keepalive (reference client leaves it off) |

## 16. Entitlement (store products — probably irrelevant for teleop)

`ENTITLEMENT_CHECK` (6) / `ENTITLEMENT_RESULT` (7) verify the calling package is
an entitled (store-purchased) product. The Unity SDK checks entitlement before
considering itself fully initialized. **Unknown:** whether `MainService` gates
`SET_CONTROLLER_DATA` on entitlement. A sideloaded teleop app is not a store
product; if tracking events don't arrive, entitlement gating is the first
thing to check (adb logcat on the headset).

## 17. Not reconstructed (out of scope for tracking)

System/firmware (`INSTALL_FIRMWARE`, `AGG_52833/5340_FIRMWARE`,
`GAZELLE_CHANNEL` radio config, system software updates, wifi, factory reset,
screenshots, DLC, photos/screen recordings, VOIP, realtime network/social).
Codes are in the appendix; payloads were not reconstructed.

---

## Appendix A — Full message code table (272 codes)

Extracted from `MessageTypes` in the middleware AAR (version string
`omni_one_unity_12987_debug`, AAR `omni_one_unity_4859x-debug`). Duplicated
codes exist in the original enum (e.g. `STORE_PRODUCTS_UPDATED` reuses
30000002; the first name is kept).

| what | Constant |
|-----:|-----------|
| 1 | `REGISTER_CLIENT` |
| 2 | `UNREGISTER_CLIENT` |
| 3 | `SET_CONTROLLER_DATA` |
| 4 | `SET_PLAYER_DATA` |
| 6 | `ENTITLEMENT_CHECK` |
| 7 | `ENTITLEMENT_RESULT` |
| 8 | `OMNI_ONLINE_STATUS_CHECK` |
| 9 | `OMNI_ONLINE_STATUS` |
| 5001 | `CONTROL_BUTTON_PRESSED` |
| 10001 | `UNINSTALL_COMPLETED` |
| 10002 | `UNINSTALL_APP` |
| 10003 | `INSTALL_APP` |
| 10004 | `UPDATE_PROGRESS` |
| 10005 | `CALIBRATE_OMNI` |
| 10006 | `CALIBRATION_RESULT` |
| 10007 | `REGISTER_UNITY_CLIENT` |
| 10008 | `UNREGISTER_UNITY_CLIENT` |
| 10009 | `REQUEST_STEP_COUNT` |
| 13001 | `UNKNOWN_EXCEPTION` |
| 13002 | `KNOWN_EXCEPTION` |
| 15001 | `AGG_52833_FIRMWARE` |
| 15002 | `AGG_5340_FIRMWARE` |
| 15003 | `FOOT_TRACKER_FIRMWARE` |
| 15004 | `GET_TREADMILL_INFO` |
| 15005 | `GAZELLE_CHANNEL_INFO` |
| 16001 | `SYSTEM_SOFTWARE_RELEASE_AVAILABLE` |
| 16002 | `NO_SYSTEM_SOFTWARE_RELEASE_AVAILABLE` |
| 16003 | `IS_SYSTEM_SOFTWARE_UPDATE_AVAILABLE` |
| 16004 | `UPDATE_SYSTEM_SOFTWARE` |
| 16005 | `SYSTEM_SOFTWARE_UPDATE_COMPLETE` |
| 16006 | `SYSTEM_SOFTWARE_UPDATE_FAILURE` |
| 16007 | `STARTUP_TASK_COMPLETE` |
| 16008 | `GET_BATTERY_STATUS` |
| 16009 | `BATTERY_STATUS` |
| 100010 | `CURRENT_STEP_COUNT` |
| 100011 | `UPDATE_MIDDLEWARE` |
| 100012 | `TAKE_SCREENSHOT` |
| 100013 | `SCREENSHOT_TAKEN` |
| 100014 | `CANCEL_INSTALLATION` |
| 100015 | `CANCEL_INSTALL_RESULT` |
| 100016 | `CHANGE_WIFI_ENABLED` |
| 100017 | `CHANGED_WIFI_ENABLED_RESULT` |
| 100018 | `FACTORY_RESET` |
| 100019 | `FACTORY_RESET_COMPLETE` |
| 100020 | `SCAN_WIFI` |
| 100021 | `SCAN_WIFI_RESULTS` |
| 100022 | `CONNECT_TO_WIFI` |
| 100023 | `CONNECT_TO_WIFI_RESULTS` |
| 100024 | `DISCONNECT_FROM_WIFI` |
| 100025 | `FORGET_WIFI_NETWORK` |
| 100026 | `GET_WIFI_STATUS` |
| 100027 | `WIFI_STATUS` |
| 100028 | `CONNECT_TO_HIDDEN_WIFI` |
| 100029 | `SET_DEVICE_NAME` |
| 100031 | `GET_DEVICE_NAME` |
| 100032 | `CURRENT_DEVICE_NAME` |
| 100033 | `LIST_SIDE_LOAD_APPS_IN_DIRECTORY` |
| 100034 | `SIDE_LOAD_APPS_IN_DIRECTORY` |
| 100035 | `SIDE_LOAD_APP` |
| 100036 | `SIDE_LOAD_INSTALL_PROGRESS` |
| 100037 | `CONFIGURATION` |
| 100038 | `SETTINGS` |
| 100039 | `DOWNLOAD_FILE` |
| 100040 | `DOWNLOAD_FILE_PROGRESS` |
| 100041 | `DOWNLOAD_FILE_FAILED` |
| 100042 | `INSTALL_FIRMWARE` |
| 100043 | `FIRMWARE_NEED_TO_INSTALL` |
| 100044 | `INSTALL_WITH_ERROR_REPORTING` |
| 100045 | `INSTALL_PROGRESS_WITH_ERROR` |
| 100046 | `INSTALL_ERROR` |
| 100047 | `CANCEL_INSTALLS_DOWNLOAD` |
| 100048 | `CANCEL_INSTALLS_DOWNLOAD_SUCCESS` |
| 100049 | `CANCEL_INSTALLS_DOWNLOAD_FAILED` |
| 100050 | `INSTALL_OBB_WITH_ERROR_REPORTING` |
| 100051 | `FIRMWARE_UPDATE_PROGRESS` |
| 100052 | `RECONNECTED_TO_OMNI_AFTER_FIRMWARE_UPDATE` |
| 100053 | `NEED_TO_INSTALL_FIRMWARE` |
| 100054 | `SET_AUTO_TIME` |
| 100055 | `REQUEST_UNITY_CALIBRATION_AND_LAUNCH_PRODUCT` |
| 100056 | `FORCE_CALIBRATION_COMPLETE` |
| 100057 | `FORCE_UNITY_CALIBRATION` |
| 160010 | `SPLASH_SCREEN_COMPLETE` |
| 160011 | `RESTART_ACTIVITY` |
| 160012 | `SHOW_HOME_SCREEN` |
| 160013 | `HIDE_HOME_SCREEN` |
| 160014 | `VR_ENVIRONMENT_HAS_FOCUS` |
| 160015 | `VR_ENVIRONMENT_LOST_FOCUS` |
| 160016 | `CONNECT_TO_TREADMILL` |
| 160017 | `GET_OMNI_ONE_CONNECTED_TREADMILL_NAME` |
| 160018 | `OMNI_ONE_CONNECTED_DEVICE_NAME` |
| 160019 | `OMNI_ONE_TREADMILL_SCAN` |
| 160020 | `DISCONNECT_FROM_TREADMILL` |
| 160021 | `DISCONNECTED_FROM_TREADMILL` |
| 160022 | `POWER_STATE` |
| 160023 | `CLOSE_PICO_BROWSER` |
| 2000001 | `SET_USER` |
| 2000002 | `USER_SET` |
| 2000003 | `START_GAME_SESSION` |
| 2000004 | `STOP_GAME_SESSION` |
| 2000005 | `SDK_GAME_STOP` |
| 2000006 | `USER_LOGOUT` |
| 2000007 | `SYSTEM_SHUTDOWN` |
| 2000008 | `IS_AUTHENTICATED` |
| 2000009 | `AUTHENTICATION_STATUS` |
| 2000014 | `CLEAR_PREFERENCES_CACHE` |
| 2000015 | `USER_COMPLETED_FIRST_TIME_USER_EXPERIENCE` |
| 3000001 | `CONNECT_REALTIME_NETWORK` |
| 3000002 | `PARTY_INVITE` |
| 3000003 | `PARTY_CHANGED` |
| 3000004 | `PROFILE` |
| 3000005 | `ONLINE_STATUS` |
| 3000006 | `FRIEND_STATUS_UPDATE` |
| 3000007 | `DISCONNECT_REALTIME_NETWORK` |
| 20000010 | `LOGIN` |
| 20000011 | `LOGIN_RESPONSE` |
| 20000012 | `LOGIN_FAILED` |
| 20000013 | `LOGGED_IN_USER_PROFILE` |
| 30000002 | `STORE_PRODUCTS_UPDATED` |
| 31000000 | `GET_PLAYER_DETAILS` |
| 31000001 | `PLAYER_DETAILS` |
| 31000002 | `GET_PLAYER_DETAILS_FAILED` |
| 31000003 | `CREATE_GAME_INVITE` |
| 31000004 | `CREATE_GAME_INVITE_SUCCESS` |
| 31000005 | `CREATE_GAME_INVITE_FAILURE` |
| 31000006 | `GAME_INVITE` |
| 31000007 | `GET_LAUNCH_DETAILS` |
| 31000008 | `LAUNCH_DETAILS_SUCCESS` |
| 31000009 | `ACCEPT_GAME_INVITE` |
| 31000010 | `NO_LAUNCH_DETAILS` |
| 31000011 | `GET_OMNI_STATUS` |
| 31000012 | `OMNI_CONNECTED` |
| 31000013 | `OMNI_DISCONNECTED` |
| 31000014 | `SET_OMNI_VOIP_STATUS` |
| 31000015 | `GET_OMNI_VOIP_STATUS` |
| 31000016 | `OMNI_VOIP_CONNECTED` |
| 31000017 | `OMNI_VOIP_DISCONNECTED` |
| 31000018 | `START_TREADMILL_SCAN` |
| 31000019 | `INVITE_PLAYERS` |
| 31000020 | `INVITE_PLAYERS_FAILED` |
| 31000021 | `SHOW_INVITE_PLAYERS` |
| 31000022 | `RIGHT_FOOT_TRACKER_DISCONNECTED` |
| 31000023 | `LEFT_FOOT_TRACKER_DISCONNECTED` |
| 31000024 | `RIGHT_FOOT_TRACKER_CONNECTED` |
| 31000025 | `LEFT_FOOT_TRACKER_CONNECTED` |
| 31000026 | `FOOT_TRACKER_BATTERY_STATUS` |
| 31000027 | `GET_FOOT_TRACKER_BATTERY_STATUS` |
| 31000028 | `WAS_SHORT_BUTTON_PRESSED` |
| 31000029 | `SHORT_BUTTON_PRESSED` |
| 31000030 | `SHORT_BUTTON_NOT_PRESSED` |
| 31000031 | `WAS_BOUNDARY_CHANGED` |
| 31000032 | `BOUNDARY_CHANGED_OMNI` |
| 31000033 | `BOUNDARY_CHANGED_ROOM` |
| 31000034 | `BOUNDARY_NOT_CHANGED` |
| 31000035 | `SET_GAZELLE_CHANNEL` |
| 31000036 | `GET_GAZELLE_CHANNEL` |
| 31000037 | `GAZELLE_CHANNEL` |
| 31000038 | `GET_SDK_CONFIGURATION` |
| 31000039 | `SDK_CONFIGURATION` |
| 31000040 | `SDK_CONFIGURATION_FAILURE` |
| 31000041 | `SET_LAUNCH_DETAILS` |
| 31000042 | `IMAGE_REQUEST` |
| 31000043 | `IMAGE_REQUESTS` |
| 31000044 | `FORCE_BOUNDARY_CHANGED_TO_OMNI` |
| 31000045 | `OMNI_ONE_SCAN_RESULT` |
| 31000046 | `TREADMILL_LOCKED` |
| 31000047 | `TREADMILL_UNLOCKED` |
| 31000048 | `GET_TREADMILL_LOCK_STATUS` |
| 31000049 | `GET_ID_TOKEN` |
| 31000050 | `ID_TOKEN` |
| 31000051 | `KILL_GAME` |
| 32000000 | `GET_ACHIEVEMENT` |
| 32000001 | `ACHIEVEMENT` |
| 32000002 | `GET_ACHIEVEMENT_FAILED` |
| 32000003 | `GET_USER_ACHIEVEMENT` |
| 32000004 | `USER_ACHIEVEMENT` |
| 32000005 | `GET_USER_ACHIEVEMENT_FAILED` |
| 32000006 | `UNLOCK_USER_ACHIEVEMENT` |
| 32000007 | `UNLOCK_USER_ACHIEVEMENT_SUCCESS` |
| 32000008 | `UNLOCK_USER_ACHIEVEMENT_FAILED` |
| 32000009 | `SET_COUNT` |
| 32000010 | `SET_COUNT_SUCCESS` |
| 32000011 | `SET_COUNT_FAILED` |
| 32000012 | `UNLOCK_BIT_FIELD_USER_ACHIEVEMENT` |
| 32000013 | `UNLOCK_BIT_FIELD_USER_ACHIEVEMENT_SUCCESS` |
| 32000014 | `UNLOCK_BIT_FIELD_USER_ACHIEVEMENT_FAILED` |
| 32000015 | `BATCH_USER_ACHIEVEMENT_UPDATE` |
| 32000016 | `BATCH_USER_ACHIEVEMENT_UPDATE_SUCCESS` |
| 32000017 | `BATCH_USER_ACHIEVEMENT_UPDATE_FAILED` |
| 33000000 | `GET_GAME_STAT` |
| 33000001 | `GET_GAME_STAT_FAILED` |
| 33000002 | `GAME_STAT` |
| 33000003 | `GET_GAME_STATS` |
| 33000004 | `GAME_STATS` |
| 33000005 | `GET_GAME_STATS_FAILED` |
| 33000006 | `SET_USER_GAME_STAT` |
| 33000007 | `SET_USER_GAME_STAT_SUCCESS` |
| 33000008 | `SET_USER_GAME_STAT_FAILED` |
| 33000009 | `GET_USER_GAME_STAT` |
| 33000010 | `USER_GAME_STAT` |
| 33000011 | `GET_USER_GAME_STAT_FAILED` |
| 33000012 | `SET_USER_GAME_STATS` |
| 33000013 | `SET_USER_GAME_STATS_SUCCESS` |
| 33000014 | `SET_USER_GAME_STATS_FAILED` |
| 34000001 | `GET_LEADERBOARD` |
| 34000002 | `GET_LEADERBOARD_FAILED` |
| 34000003 | `LEADERBOARD` |
| 34000004 | `GET_LEADERBOARD_VALUES` |
| 34000005 | `LEADERBOARD_VALUES` |
| 34000006 | `GET_LEADERBOARD_VALUES_FAILED` |
| 34000007 | `GET_LEADERBOARD_POSITION` |
| 34000008 | `LEADERBOARD_POSITION` |
| 34000009 | `GET_LEADERBOARD_POSITION_FAILED` |
| 35000001 | `GET_STORE_PRODUCTS` |
| 35000003 | `GET_PRODUCTS` |
| 35000004 | `PRODUCT_UPDATE` |
| 36000001 | `VOIP_JOIN_PARTY` |
| 36000002 | `VOIP_SPEAKER_INDICATOR` |
| 36000004 | `VOIP_LEAVE_CHANNEL` |
| 36000005 | `VOIP_DESTROY` |
| 36000006 | `VOIP_MUTE` |
| 36000007 | `VOIP_SET_VOLUME` |
| 36000009 | `VOIP_SET_USER_VOLUME` |
| 36000010 | `VOIP_MUTE_USER` |
| 36000011 | `VOIP_SET_USER_ID` |
| 37000001 | `POWER_OFF` |
| 37000002 | `REBOOT` |
| 38000000 | `START_SCREEN_RECORDING` |
| 39000000 | `SET_MUSIC_VOLUME` |
| 39000001 | `GET_MUSIC_VOLUME` |
| 39000002 | `MUSIC_VOLUME` |
| 39000003 | `START_MUSIC` |
| 40000001 | `GET_GAME_INVITES` |
| 40000002 | `ALL_GAME_INVITES` |
| 40000003 | `GAME_INVITE_NOTIFICATION` |
| 40000004 | `GET_FRIENDS` |
| 40000005 | `FRIEND` |
| 40000006 | `GET_FRIEND_REQUESTS` |
| 40000007 | `FRIEND_REQUEST` |
| 40000008 | `REMOVE_FRIEND_REQUEST` |
| 40000009 | `GET_PARTY_INVITES` |
| 40000010 | `LAUNCHER_PARTY_INVITE` |
| 40000011 | `REMOVE_PARTY_INVITE` |
| 40000012 | `REMOVE_GAME_INVITE` |
| 41000001 | `DOWNLOADABLE_CONTENT_GET_LIST_REQUEST` |
| 41000002 | `DOWNLOADABLE_CONTENT_GET_LIST_SUCCESS` |
| 41000003 | `DOWNLOADABLE_CONTENT_GET_LIST_FAILURE` |
| 41000004 | `DOWNLOADABLE_CONTENT_DOWNLOAD_BY_ID_REQUEST` |
| 41000005 | `DOWNLOADABLE_CONTENT_DOWNLOAD_SUCCESS` |
| 41000006 | `DOWNLOADABLE_CONTENT_DOWNLOAD_FAILURE` |
| 41000007 | `DOWNLOADABLE_CONTENT_DOWNLOAD_BY_NAME_REQUEST` |
| 41000008 | `DOWNLOADABLE_CONTENT_DELETE_BY_ID_REQUEST` |
| 41000009 | `DOWNLOADABLE_CONTENT_DELETE_BY_ID_RESULT` |
| 41000010 | `DOWNLOADABLE_CONTENT_CANCEL_BY_ID_REQUEST` |
| 41000011 | `DOWNLOADABLE_CONTENT_CANCEL_RESULT` |
| 41000012 | `DOWNLOADABLE_CONTENT_DELETE_BY_NAME_REQUEST` |
| 41000013 | `DOWNLOADABLE_CONTENT_CANCEL_BY_NAME_REQUEST` |
| 41000014 | `DOWNLOADABLE_CONTENT_CANCEL_BY_NAME_SUCCESS` |
| 41000015 | `DOWNLOADABLE_CONTENT_CANCEL_BY_NAME_FAIL` |
| 41000016 | `DOWNLOADABLE_CONTENT_STATUS_BY_ID_REQUEST` |
| 41000017 | `DOWNLOADABLE_CONTENT_STATUS_RESULT` |
| 41000018 | `DOWNLOADABLE_CONTENT_DOWNLOAD_UPDATE` |
| 41000019 | `DOWNLOADABLE_CONTENT_DELETE_FOR_SAFETY` |
| 41000020 | `DOWNLOADABLE_CONTENT_LAUNCH_CHECKOUT_FLOW` |
| 41000021 | `DOWNLOADABLE_CONTENT_CHECKOUT_SUCCESS` |
| 41000022 | `DOWNLOADABLE_CONTENT_CHECKOUT_FAILURE` |
| 42000001 | `GET_PHOTOS` |
| 42000002 | `PHOTOS` |
| 42000003 | `GET_SCREEN_RECORDINGS` |
| 42000004 | `SCREEN_RECORDINGS` |
| 42000005 | `DELETE_FILE` |
| 777777777 | `PING` |
| 999999999 | `UNKNOWN` |

## Appendix B — Reconstruction provenance

| Fact | Source |
|------|--------|
| Service component, bind flags, 5 s retry | `AndroidMiddlewareService.bindService()`, `connection.onServiceDisconnected` |
| Framing (`what`, `arg1/arg2` sourceId, `replyTo`, queueing) | `UnityMessageSender`, `DtoMessage`, `MessageTypes.Companion` |
| Handshake (REGISTER_UNITY_CLIENT → CONFIGURATION → SETTINGS) | `AndroidMiddlewareService.connection.onServiceConnected`, `LauncherConfigurationMessageReceiver.getSettings`, `SettingsDto` |
| Movement keys + axis shuffle | `MovementService` (shared), `PlayerDataDto`, `AndroidMovementService.cs` |
| Movement units/semantics | `MovementService.cs` XML docs (Unity SDK 1.1.3) |
| Treadmill connection keys | `TreadmillConnectionService`, `ScanResultDto`, `ConnectedTreadmillNameDto`, `ConnectToTreadmillDto` |
| Pod/foot-tracker mapping | `PlatformService.registerHandlers` (LEFT/RIGHT_FOOT_TRACKER_* → handleLeft/RightPodConnected) |
| Battery/lock/step/button/boundary keys | `BatteryStatusDto`, `FootTrackerBatteryStatusDto`, `ControlButtonDto`, `AndroidMiddlewareService.handleStepCount`, `SystemService.registerHandlers` |
