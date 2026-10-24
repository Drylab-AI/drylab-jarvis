"""adb helpers for the Jarvis e2e harness (talk, tap, press, read the phone).

Everything here drives ONE phone, chosen by JARVIS_SERIAL (default: the only
attached device). Nothing opens a window on the PC.
"""

from __future__ import annotations

import os
import re
import shlex
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path

PKG = "com.jarvis.voicerec"
SERIAL = os.environ.get("JARVIS_SERIAL", "")
# The app's private files dir (see TestMode.dir in TestHarness.kt). adb
# cannot write here directly, so files go via /data/local/tmp and are copied
# in as root, then chowned to the app's uid and SELinux label.
JT_DIR = f"/data/data/{PKG}/files/jt"
STAGE = "/data/local/tmp/jt-stage"


def adb(*args: str, check: bool = True, timeout: float = 60) -> str:
    cmd = ["adb"] + (["-s", SERIAL] if SERIAL else []) + list(args)
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if check and p.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)} failed: {p.stderr.strip() or p.stdout.strip()}")
    return p.stdout


def sh(cmd: str, root: bool = False, check: bool = True, timeout: float = 60) -> str:
    if root:
        # adb joins its args with spaces for the remote shell, so the whole
        # root command must be one quoted word or `su -c` only gets its first
        # word and the rest (after && or ;) runs unprivileged.
        return adb("shell", f"su -c {shlex.quote(cmd)}", check=check, timeout=timeout)
    return adb("shell", cmd, check=check, timeout=timeout)


def device_ms() -> int:
    """The phone's elapsedRealtime in ms (same clock as JARVIS_T lines)."""
    out = sh("cat /proc/uptime")
    return int(float(out.split()[0]) * 1000)


# -- test mode ----------------------------------------------------------------

def _own() -> None:
    """Give the test dir to the app (owner + SELinux label of its data dir),
    so the app reads what root wrote."""
    sh(
        f"mkdir -p {JT_DIR}/clips; "
        f"U=$(stat -c %u /data/data/{PKG}); "
        f"chown -R $U:$U {JT_DIR}; chmod -R u+rwX {JT_DIR}; "
        f"chcon -R $(stat -c %C /data/data/{PKG}/files) {JT_DIR} 2>/dev/null; true",
        root=True, check=False,
    )


def set_test_mode(on: bool, real_mic: bool = False) -> None:
    if on:
        sh(f"mkdir -p {JT_DIR} && echo {'realmic' if real_mic else 'scripted'} "
           f"> {JT_DIR}/test-mode", root=True)
        _own()
    else:
        sh(f"rm -f {JT_DIR}/test-mode", root=True, check=False)


def push_clips(clips_dir: Path) -> None:
    sh(f"rm -rf {STAGE}; mkdir -p {STAGE}")
    adb("push", *[str(f) for f in sorted(clips_dir.glob("*.raw"))], STAGE, timeout=300)
    sh(f"mkdir -p {JT_DIR}/clips && cp {STAGE}/*.raw {JT_DIR}/clips/", root=True)
    _own()


def say(name: str, trigger: str = "now", delay_ms: int = 0) -> None:
    """Queue a clip on the scripted mic (the 'user' starts talking)."""
    sh(
        f"am broadcast -p {PKG} -a {PKG}.TEST_INJECT "
        f"--es path {JT_DIR}/clips/{name}.raw --es name {name} "
        f"--es trigger {trigger} --el delay {delay_ms}"
    )


def wake_test() -> None:
    """Run the 'hey jarvis' clip through the real wake-word detector."""
    sh(
        f"am start-foreground-service -n {PKG}/.WakeService "
        f"-a {PKG}.WAKE_TEST --es pcm {JT_DIR}/clips/hey_jarvis.raw",
        root=True,
    )


def stop_assistant() -> None:
    sh(f"am start-foreground-service -n {PKG}/.AssistantService -a {PKG}.ASSIST_STOP",
       root=True, check=False)


def start_assistant() -> None:
    sh(f"am start -n {PKG}/.AssistantActivity -a {PKG}.ASSISTANT", check=False)


def go_home() -> None:
    """Close the assistant UI (finish its task) so the next launch is fresh."""
    sh(f"am force-stop {PKG}", root=True, check=False)
    # force-stop also kills WakeService; bring it back for the wake path.
    sh(f"am start-foreground-service -n {PKG}/.WakeService -a {PKG}.WAKE_START",
       root=True, check=False)


def wake_screen() -> None:
    sh("input keyevent KEYCODE_WAKEUP", check=False)


# -- hardware keys ------------------------------------------------------------

def gpio_node() -> str:
    out = sh("getevent -pl", root=True)
    cur = None
    for line in out.splitlines():
        m = re.match(r"add device \d+: (\S+)", line)
        if m:
            cur = m.group(1)
        if 'name:' in line and '"gpio_keys"' in line and cur:
            return cur
    raise RuntimeError("gpio_keys input node not found")


KEY_VOLUMEDOWN = 114
KEY_VOLUMEUP = 115


def press_key(code: int, hold_ms: int = 80) -> None:
    """Inject a real press on the physical-button node, as root.

    volrecd grabs gpio_keys and reads it like a finger press, so this tests
    the daemon -> trampoline -> service path end to end.
    """
    node = gpio_node()
    sh(
        f"sendevent {node} 1 {code} 1; sendevent {node} 0 0 0; "
        f"sleep {hold_ms / 1000:.3f}; "
        f"sendevent {node} 1 {code} 0; sendevent {node} 0 0 0",
        root=True,
    )


# -- reading the phone ----------------------------------------------------------

@dataclass
class Event:
    t: int          # phone elapsedRealtime ms
    name: str
    detail: str


def logcat_clear() -> None:
    adb("logcat", "-c", check=False)


def timeline(since_ms: int = 0) -> list[Event]:
    """All JARVIS_T events in the logcat buffer at/after since_ms."""
    out = adb("logcat", "-d", "-s", "JARVIS_T:I", "-v", "raw", check=False)
    evs = []
    for line in out.splitlines():
        parts = line.strip().split(" ", 2)
        if len(parts) < 2 or not parts[0].isdigit():
            continue
        t = int(parts[0])
        if t >= since_ms:
            evs.append(Event(t, parts[1], parts[2] if len(parts) > 2 else ""))
    return evs


def wait_event(name: str, since_ms: int, timeout_s: float, detail: str = "") -> Event | None:
    end = time.time() + timeout_s
    while time.time() < end:
        for e in timeline(since_ms):
            if e.name == name and detail in e.detail:
                return e
        time.sleep(0.3)
    return None


def logcat_text(tags: str = "AssistantService:* JARVIS_T:* WakeService:* CameraService:*") -> str:
    return adb("logcat", "-d", "-v", "time", "-s", *tags.split(), check=False)


def ui_text() -> str:
    """All visible text on screen (uiautomator dump)."""
    sh("uiautomator dump /sdcard/jt-ui.xml", check=False)
    xml = sh("cat /sdcard/jt-ui.xml", check=False)
    import html
    return "\n".join(html.unescape(m) for m in re.findall(r'text="([^"]*)"', xml))


def transcript() -> str:
    """The assistant transcript text view, joined into plain sentences."""
    txt = ui_text()
    lines = [l for l in txt.splitlines() if l.startswith(("Jarvis:", "You:"))]
    joined = " ".join(l.split(":", 1)[1].strip() for l in "\n".join(lines).split("\n") if ":" in l)
    return re.sub(r"\s+", " ", joined)


def screenshot(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "wb") as f:
        subprocess.run(["adb"] + (["-s", SERIAL] if SERIAL else []) +
                       ["exec-out", "screencap", "-p"], stdout=f, check=False, timeout=30)


def tap_text(label: str) -> bool:
    """Tap the centre of the first element whose text equals label."""
    sh("uiautomator dump /sdcard/jt-ui.xml", check=False)
    xml = sh("cat /sdcard/jt-ui.xml", check=False)
    m = re.search(r'text="' + re.escape(label) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        return False
    x1, y1, x2, y2 = map(int, m.groups())
    sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
    return True


def pull_played(dest: Path) -> Path | None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    sh(f"cp {JT_DIR}/played.pcm {STAGE}/played.pcm && chmod 0644 {STAGE}/played.pcm",
       root=True, check=False)
    p = subprocess.run(["adb"] + (["-s", SERIAL] if SERIAL else []) +
                       ["pull", f"{STAGE}/played.pcm", str(dest)],
                       capture_output=True, text=True)
    return dest if p.returncode == 0 else None


def install(apk: Path) -> None:
    adb("install", "-r", "-g", str(apk), timeout=300)
