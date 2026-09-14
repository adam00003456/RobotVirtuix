"""Frame dataclasses and parsing for the OmniTrack bridge wire format.

Movement axes (m/s, view-relative — see docs/PROTOCOL.md section 5):
    x = forward (walk direction)
    y = lateral, right-positive
    z = vertical (~0 on a treadmill)
"""

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


@dataclass(frozen=True)
class Movement:
    t_ms: int
    x: float  # forward, m/s
    y: float  # lateral, right +, m/s
    z: float  # vertical, m/s (~0)
    speed: float = 0.0
    head_yaw_deg: Optional[float] = None

    def as_tuple(self) -> tuple:
        return (self.x, self.y, self.z)

    def is_moving(self, threshold: float = 0.05) -> bool:
        return self.speed > threshold


@dataclass(frozen=True)
class StepCount:
    t_ms: int
    count: int


@dataclass(frozen=True)
class FootTracker:
    t_ms: int
    side: str  # "left" | "right"
    connected: bool


@dataclass(frozen=True)
class FootTrackerBattery:
    t_ms: int
    left_percentage: int
    right_percentage: int


@dataclass(frozen=True)
class Battery:
    t_ms: int
    charge_level: float  # 0..1, -1 unknown
    charging: bool


@dataclass(frozen=True)
class TreadmillConnected:
    t_ms: int
    name: str


@dataclass(frozen=True)
class TreadmillDisconnected:
    t_ms: int


@dataclass(frozen=True)
class OmniConnected:
    t_ms: int


@dataclass(frozen=True)
class OmniDisconnected:
    t_ms: int


@dataclass(frozen=True)
class TreadmillLock:
    t_ms: int
    locked: bool


@dataclass(frozen=True)
class Button:
    t_ms: int
    button: str  # "pause" | "long_home" | "unknown_*"


@dataclass(frozen=True)
class ShortButton:
    t_ms: int
    pressed: bool


@dataclass(frozen=True)
class Boundary:
    t_ms: int
    changed: bool
    omni_mode: Optional[bool] = None


@dataclass(frozen=True)
class ClientState:
    t_ms: int
    connected: bool


@dataclass(frozen=True)
class ScanResults:
    t_ms: int
    names: List[str] = field(default_factory=list)


@dataclass(frozen=True)
class Settings:
    t_ms: int
    movement: Optional[bool] = None
    permissions: Optional[bool] = None
    rest_url: Optional[str] = None
    forward_exception: Optional[bool] = None


@dataclass(frozen=True)
class Status:
    """Periodic (1 Hz) snapshot from the bridge."""
    t_ms: int
    raw: Dict[str, Any]

    @property
    def client_connected(self) -> bool:
        return bool(self.raw.get("client_connected", False))

    @property
    def omni_connected(self) -> bool:
        return bool(self.raw.get("omni_connected", False))

    @property
    def treadmill_name(self) -> Optional[str]:
        return self.raw.get("treadmill_name")

    @property
    def treadmill_locked(self) -> Optional[bool]:
        return self.raw.get("treadmill_locked")

    @property
    def steps(self) -> Optional[int]:
        v = self.raw.get("steps")
        return None if v is None else int(v)

    @property
    def charge_level(self) -> Optional[float]:
        v = self.raw.get("charge_level")
        return None if v is None else float(v)

    @property
    def charging(self) -> Optional[bool]:
        return self.raw.get("charging")

    @property
    def foot_trackers(self) -> Dict[str, Any]:
        return self.raw.get("foot_trackers", {})

    @property
    def movement(self) -> Optional[Movement]:
        if "x" not in self.raw:
            return None
        return Movement(
            t_ms=self.t_ms,
            x=float(self.raw["x"]),
            y=float(self.raw.get("y", 0.0)),
            z=float(self.raw.get("z", 0.0)),
            speed=float(self.raw.get("speed", 0.0)),
        )


@dataclass(frozen=True)
class Echo:
    t_ms: int
    raw: Dict[str, Any]


@dataclass(frozen=True)
class BridgeError:
    t_ms: int
    error: str


def parse_json(text: str):
    """Parses one JSON frame (str or bytes) into a dataclass. Raises ValueError."""
    import json

    obj = json.loads(text)
    if not isinstance(obj, dict):
        raise ValueError("frame must be a JSON object")
    return parse_frame(obj)


def parse_frame(obj: Dict[str, Any]):
    """Dispatches a decoded JSON frame dict to a dataclass instance."""
    kind = obj.get("type")
    t_ms = int(obj.get("t_ms", 0))

    if kind == "movement":
        return Movement(
            t_ms=t_ms,
            x=float(obj.get("x", 0.0)),
            y=float(obj.get("y", 0.0)),
            z=float(obj.get("z", 0.0)),
            speed=float(obj.get("speed", 0.0)),
            head_yaw_deg=obj.get("head_yaw_deg"),
        )
    if kind == "step_count":
        return StepCount(t_ms=t_ms, count=int(obj.get("count", -1)))
    if kind == "foot_tracker":
        return FootTracker(t_ms=t_ms, side=str(obj.get("side")), connected=bool(obj.get("connected")))
    if kind == "foot_tracker_battery":
        return FootTrackerBattery(
            t_ms=t_ms,
            left_percentage=int(obj.get("left_percentage", -1)),
            right_percentage=int(obj.get("right_percentage", -1)),
        )
    if kind == "battery":
        return Battery(t_ms=t_ms, charge_level=float(obj.get("charge_level", -1.0)),
                       charging=bool(obj.get("charging", False)))
    if kind == "treadmill_connected":
        return TreadmillConnected(t_ms=t_ms, name=str(obj.get("name", "")))
    if kind == "treadmill_disconnected":
        return TreadmillDisconnected(t_ms=t_ms)
    if kind == "omni_connected":
        return OmniConnected(t_ms=t_ms)
    if kind == "omni_disconnected":
        return OmniDisconnected(t_ms=t_ms)
    if kind == "treadmill_locked":
        return TreadmillLock(t_ms=t_ms, locked=True)
    if kind == "treadmill_unlocked":
        return TreadmillLock(t_ms=t_ms, locked=False)
    if kind == "button":
        return Button(t_ms=t_ms, button=str(obj.get("button", "unknown")))
    if kind == "short_button":
        return ShortButton(t_ms=t_ms, pressed=bool(obj.get("pressed", False)))
    if kind == "boundary":
        return Boundary(t_ms=t_ms, changed=bool(obj.get("changed", False)),
                         omni_mode=obj.get("omni_mode"))
    if kind == "client":
        return ClientState(t_ms=t_ms, connected=bool(obj.get("connected", False)))
    if kind == "scan_results":
        return ScanResults(t_ms=t_ms, names=list(obj.get("names", [])))
    if kind == "settings":
        return Settings(
            t_ms=t_ms,
            movement=obj.get("movement"),
            permissions=obj.get("permissions"),
            rest_url=obj.get("rest_url"),
            forward_exception=obj.get("forward_exception"),
        )
    if kind == "status":
        return Status(t_ms=t_ms, raw=obj)
    if kind == "echo":
        return Echo(t_ms=t_ms, raw=obj)
    if kind == "error":
        return BridgeError(t_ms=t_ms, error=str(obj.get("error", "")))

    raise ValueError(f"unknown frame type: {kind!r}")
