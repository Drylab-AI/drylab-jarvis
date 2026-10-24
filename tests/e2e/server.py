"""PC-side helpers for the e2e harness: start/stop the server, read its state."""

from __future__ import annotations

import json
import os
import signal
import subprocess
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SERVER_DIR = ROOT / "server"
BASE = os.environ.get("JARVIS_BASE", "http://127.0.0.1:8787")
LOG = ROOT / "tests/logs/interaction/server.log"


def get(path: str, timeout: float = 5) -> dict:
    with urllib.request.urlopen(BASE + path, timeout=timeout) as r:
        return json.loads(r.read() or b"{}")


def healthy() -> bool:
    try:
        return bool(get("/health").get("ok"))
    except Exception:  # noqa: BLE001
        return False


def status() -> dict:
    try:
        return get("/assistant/status")
    except Exception:  # noqa: BLE001
        return {"active": False}


def say_text(text: str) -> int:
    """POST /assistant/say (text injection). Returns the HTTP status."""
    import urllib.parse

    data = urllib.parse.urlencode({"text": text}).encode()
    req = urllib.request.Request(BASE + "/assistant/say", data=data, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception:  # noqa: BLE001
        return 0


def server_pids() -> list[int]:
    out = subprocess.run(["pgrep", "-f", "uvicorn app:app"], capture_output=True, text=True).stdout
    return [int(p) for p in out.split()]


def stop() -> None:
    for pid in server_pids():
        try:
            os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    for _ in range(40):
        if not server_pids():
            return
        time.sleep(0.25)
    for pid in server_pids():
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass


def start(env: dict[str, str] | None = None, live_window: bool = False) -> None:
    """(Re)start the server detached. The live camera window is OFF in tests
    so nothing steals the user's focus; the viewer is checked over HTTP."""
    stop()
    LOG.parent.mkdir(parents=True, exist_ok=True)
    e = dict(os.environ)
    e["JARVIS_LIVE_WINDOW"] = "1" if live_window else "0"
    e["JARVIS_SERVER_LOG"] = str(LOG)
    e.update(env or {})
    subprocess.run([str(SERVER_DIR / "run-server.sh")], env=e, check=True,
                   capture_output=True)
    for _ in range(80):
        if healthy():
            return
        time.sleep(0.25)
    raise RuntimeError(f"server did not come up; see {LOG}")


def log_text() -> str:
    try:
        return LOG.read_text(errors="replace")
    except OSError:
        return ""


def mjpeg_fps(sid: str, seconds: float = 4.0) -> float:
    """Count distinct frames served by /cam/live/<sid> over `seconds`."""
    try:
        r = urllib.request.urlopen(f"{BASE}/cam/live/{sid}", timeout=seconds + 3)
    except Exception:  # noqa: BLE001
        return 0.0
    frames = 0
    end = time.time() + seconds
    buf = b""
    try:
        while time.time() < end:
            chunk = r.read1(65536) if hasattr(r, "read1") else r.read(65536)
            if not chunk:
                break
            buf += chunk
            frames += buf.count(b"Content-Type: image/jpeg")
            buf = buf[-64:]
    except Exception:  # noqa: BLE001
        pass
    finally:
        r.close()
    return frames / seconds
