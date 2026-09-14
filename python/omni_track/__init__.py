"""omni_track: robot-side client for the OmniTrack bridge.

Consumes the JSON/UDP frames published by the OmniTrack bridge app
(android/omni-bridge) running on the Virtuix Omni One headset.

Not affiliated with Virtuix. Reconstructed for interoperability; see
docs/PROTOCOL.md and docs/TELEOP.md.
"""

__version__ = "0.1.0"

from .frames import (  # noqa: F401
    Movement,
    StepCount,
    Status,
    FootTracker,
    FootTrackerBattery,
    Battery,
    TreadmillConnected,
    TreadmillDisconnected,
    OmniConnected,
    OmniDisconnected,
    TreadmillLock,
    Button,
    ShortButton,
    Boundary,
    ClientState,
    ScanResults,
    Settings,
    Echo,
    BridgeError,
    parse_frame,
    parse_json,
)
from .client import OmniTrackClient, OmniTrackCommander  # noqa: F401
