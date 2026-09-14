"""UDP client for the OmniTrack bridge.

- :class:`OmniTrackClient` listens for JSON data frames (default port 45454)
  and invokes a callback with parsed dataclasses.
- :class:`OmniTrackCommander` sends command frames to the bridge's command
  port (default 45455) and can wait for a direct reply (echo / request_status).

Both are stdlib-only.
"""

import json
import socket
import threading
from typing import Any, Callable, Optional, Union

from .frames import parse_frame

DEFAULT_DATA_PORT = 45454
DEFAULT_CMD_PORT = 45455


class OmniTrackClient:
    """Threaded listener for bridge data frames.

    Usage::

        from omni_track import OmniTrackClient

        def on_frame(frame):
            if frame.__class__.__name__ == "Movement":
                print(f"speed {frame.speed:.2f} m/s")

        client = OmniTrackClient(on_frame=on_frame)
        client.start(port=45454)
        ...
        client.stop()
    """

    def __init__(
        self,
        on_frame: Optional[Callable[[Any], None]] = None,
        host: str = "0.0.0.0",
        port: int = DEFAULT_DATA_PORT,
    ):
        self._on_frame = on_frame
        self._bind_host = host
        self._port = port
        self._sock: Optional[socket.socket] = None
        self._thread: Optional[threading.Thread] = None
        self._running = threading.Event()
        self._error: Optional[Exception] = None

    @property
    def port(self) -> int:
        return self._port

    @property
    def last_error(self) -> Optional[Exception]:
        return self._error

    def start(self, port: Optional[int] = None) -> None:
        """Binds the UDP socket and starts the receive thread (idempotent)."""
        if self._running.is_set():
            return
        if port is not None:
            self._port = port
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind((self._bind_host, self._port))
        sock.settimeout(0.25)
        self._port = sock.getsockname()[1]
        self._sock = sock
        self._running.set()
        self._thread = threading.Thread(target=self._loop, name="OmniTrackFrames", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        """Stops the receive thread and closes the socket."""
        self._running.clear()
        if self._thread is not None:
            self._thread.join(timeout=2.0)
            self._thread = None
        if self._sock is not None:
            self._sock.close()
            self._sock = None

    def __enter__(self) -> "OmniTrackClient":
        self.start()
        return self

    def __exit__(self, *exc) -> None:
        self.stop()

    def _loop(self) -> None:
        sock = self._sock
        while self._running.is_set() and sock is not None:
            try:
                data, addr = sock.recvfrom(65535)
            except socket.timeout:
                continue
            except OSError:
                break  # socket closed by stop()
            try:
                frame = parse_frame(json.loads(data.decode("utf-8")))
            except (ValueError, UnicodeDecodeError) as e:
                self._error = e
                continue
            if self._on_frame is not None:
                try:
                    self._on_frame(frame)
                except Exception as e:  # user callbacks must not kill the loop
                    self._error = e


class OmniTrackCommander:
    """Sends command frames to the bridge and (optionally) waits for a reply.

    Fire-and-forget commands return None; request/echo commands return the
    bridge's direct reply as a parsed frame.
    """

    def __init__(self, host: str, port: int = DEFAULT_CMD_PORT, timeout: float = 1.0):
        self.host = host
        self.port = port
        self.timeout = timeout

    def _send(self, obj: dict, expect_reply: bool):
        payload = json.dumps(obj).encode("utf-8")
        if not expect_reply:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                s.sendto(payload, (self.host, self.port))
            return None

        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.settimeout(self.timeout)
            s.sendto(payload, (self.host, self.port))
            try:
                data, _ = s.recvfrom(65535)
            except socket.timeout:
                return None
            return parse_frame(json.loads(data.decode("utf-8")))

    def echo(self) -> Optional[Any]:
        """Latency/connectivity probe; returns the bridge's reply."""
        return self._send({"cmd": "echo"}, expect_reply=True)

    def request_status(self):
        """Requests an immediate status frame reply."""
        return self._send({"cmd": "request_status"}, expect_reply=True)

    def scan(self) -> None:
        self._send({"cmd": "scan"}, expect_reply=False)

    def connect(self, name: str) -> None:
        self._send({"cmd": "connect", "name": name}, expect_reply=False)

    def disconnect(self) -> None:
        self._send({"cmd": "disconnect"}, expect_reply=False)

    def get_battery(self) -> None:
        self._send({"cmd": "get_battery"}, expect_reply=False)

    def get_steps(self) -> None:
        self._send({"cmd": "get_steps"}, expect_reply=False)

    def get_foot_tracker_battery(self) -> None:
        self._send({"cmd": "get_foot_tracker_battery"}, expect_reply=False)

    def get_omni_status(self) -> None:
        self._send({"cmd": "get_omni_status"}, expect_reply=False)

    def get_lock_status(self) -> None:
        self._send({"cmd": "get_lock_status"}, expect_reply=False)

    def calibration_complete(self) -> None:
        self._send({"cmd": "calibration_complete"}, expect_reply=False)
