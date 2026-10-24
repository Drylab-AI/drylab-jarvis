"""Front-camera live-stream handling for the Jarvis server.

The phone opens two concurrent streaming POSTs per session <sid>:
  - POST /cam/video/<sid>  multipart/x-mixed-replace MJPEG (JPEG frames)
  - POST /cam/audio/<sid>  raw AAC/ADTS

A single ffmpeg per session reads both (via named pipes / stdin), muxes them
into one mp4 saved under recordings/, AND tees an mpegts copy over UDP so a
local VLC window shows the stream live. POST /cam/stop/<sid> (or both bodies
ending) finalizes the file and closes the window.

Design notes:
- Video goes to ffmpeg stdin (pipe:0). Audio goes to a FIFO ffmpeg opens as a
  second input. Using stdin for the larger video stream avoids FIFO blocking
  on the hot path.
- If audio never arrives (mic busy), ffmpeg still records video-only once the
  session stops, so we never lose the clip.
- The live window is best-effort: if VLC is missing, recording still works.
"""

from __future__ import annotations

import json
import os
import queue
import re
import shutil
import signal
import subprocess
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

FFMPEG = os.environ.get("JARVIS_FFMPEG", shutil.which("ffmpeg") or "ffmpeg")
# The live window is a browser tab (renders multipart MJPEG in an <img>
# reliably, unlike VLC). Prefer Chrome's app mode; fall back to xdg-open/VLC.
BROWSER = os.environ.get(
    "JARVIS_BROWSER",
    shutil.which("google-chrome") or shutil.which("chromium") or "",
)
VLC = os.environ.get("JARVIS_VLC", shutil.which("vlc") or "/snap/bin/vlc")
# Base UDP port for the live window; each session uses UDP_BASE + slot.
UDP_BASE = int(os.environ.get("JARVIS_UDP_BASE", "9100"))
# Turn the live window off with JARVIS_LIVE_WINDOW=0.
LIVE_WINDOW = os.environ.get("JARVIS_LIVE_WINDOW", "1") != "0"


def _safe(value: str, fallback: str) -> str:
    value = (value or "").strip()
    cleaned = re.sub(r"[^A-Za-z0-9._-]", "-", value).strip(".-") or fallback
    return cleaned[:128]


def _day_dir(recordings_dir: Path) -> Path:
    day = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    d = recordings_dir / day
    d.mkdir(parents=True, exist_ok=True)
    return d


class CamSession:
    """One front-camera streaming session: ffmpeg + audio FIFO + live window."""

    _slot_counter = 0
    _slot_lock = threading.Lock()

    def __init__(self, sid: str, device: str, recordings_dir: Path,
                 with_audio: bool = True):
        # with_audio=False: video only (the assistant's camera, whose audio is
        # the conversation going to Gemini). Without it ffmpeg would wait
        # forever on an audio FIFO nobody writes to, and save nothing.
        self.with_audio = with_audio
        self.sid = _safe(sid, "session")
        self.device = _safe(device, "device")
        self.recordings_dir = recordings_dir
        self.lock = threading.Lock()
        self.started = False
        self.stopped = False
        self.ffmpeg: subprocess.Popen | None = None
        self.vlc: subprocess.Popen | None = None
        self._ff_log = None
        self.audio_fifo: Path | None = None
        self.out_path: Path | None = None
        self.bytes_video = 0
        self.bytes_audio = 0
        self.started_at = datetime.now(timezone.utc).isoformat()
        # Decouple network ingestion from ffmpeg feeding with queues + writer
        # threads, so a blocking pipe/FIFO never stalls the async event loop.
        self._video_q: queue.Queue[bytes | None] = queue.Queue(maxsize=240)
        self._audio_q: queue.Queue[bytes | None] = queue.Queue(maxsize=240)
        self._video_thread: threading.Thread | None = None
        self._audio_thread: threading.Thread | None = None
        # Latest JPEG frame for the HTTP-MJPEG live view (/cam/live/<sid>).
        self.latest_jpeg: bytes | None = None
        self._frame_seq = 0
        with CamSession._slot_lock:
            self.slot = CamSession._slot_counter % 50
            CamSession._slot_counter += 1
        self.udp_port = UDP_BASE + self.slot

    # -- lifecycle ----------------------------------------------------------

    def ensure_started(self) -> None:
        """Start ffmpeg + the live window once, on the first stream to arrive."""
        with self.lock:
            if self.started:
                return
            self.started = True

            day_dir = _day_dir(self.recordings_dir)
            self.out_path = day_dir / f"{self.sid}_{self.device}_cam.mp4"

            # Audio FIFO: ffmpeg reads it as a second input. Create before launch.
            fifo_dir = Path("/tmp/jarvis_cam")
            fifo_dir.mkdir(parents=True, exist_ok=True)
            self.audio_fifo = fifo_dir / f"{self.sid}.aac" if self.with_audio else None
            if self.audio_fifo is not None:
                try:
                    if self.audio_fifo.exists():
                        self.audio_fifo.unlink()
                    os.mkfifo(self.audio_fifo)
                except FileExistsError:
                    pass
                except OSError:
                    self.audio_fifo = None

            cmd = [FFMPEG, "-hide_banner", "-loglevel", "warning", "-y",
                   # Video from stdin (MJPEG). Stamp each frame with the wall
                   # clock on arrival so video and audio share ONE timebase -
                   # mixing a framerate clock here with a wall-clock audio clock
                   # is what floods the mux with "backward in time" and stalls
                   # the live output.
                   "-use_wallclock_as_timestamps", "1",
                   "-f", "mjpeg", "-thread_queue_size", "512",
                   "-i", "pipe:0"]
            if self.audio_fifo is not None:
                # Audio FIFO as a second input on the same wall-clock timebase.
                cmd += ["-thread_queue_size", "512",
                        "-use_wallclock_as_timestamps", "1",
                        "-fflags", "+igndts+discardcorrupt",
                        "-err_detect", "ignore_err",
                        "-f", "aac", "-i", str(self.audio_fifo)]

            # Explicit stream mapping is REQUIRED for the tee muxer (without it
            # the tee outputs get no streams -> "Output file does not contain
            # any stream").
            cmd += ["-map", "0:v:0"]
            if self.audio_fifo is not None:
                cmd += ["-map", "1:a:0"]

            # Encode video to a steady 15 fps; fragmented mp4 stays playable
            # even if we are killed mid-stream.
            cmd += ["-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency",
                    "-pix_fmt", "yuv420p", "-r", "15", "-g", "30",
                    "-vsync", "cfr"]
            if self.audio_fifo is not None:
                # aresample async=1 pads/stretches audio to keep A/V in sync
                # despite jittery FIFO arrival, keeping timestamps monotonic.
                cmd += ["-c:a", "aac", "-b:a", "96k",
                        "-af", "aresample=async=1:first_pts=0"]

            # ffmpeg writes the fragmented mp4 file (with audio). The live
            # window is served separately as HTTP MJPEG from the latest frame
            # (see /cam/live/<sid>), which avoids all UDP/TCP join races.
            file_out = str(self.out_path)
            cmd += ["-movflags", "frag_keyframe+empty_moov+default_base_moof",
                    "-f", "mp4", file_out]

            # Drain ffmpeg stderr to a per-session log so its pipe never fills
            # (a full stderr pipe would block ffmpeg and freeze the stream).
            log_dir = Path("/tmp/jarvis_cam")
            log_dir.mkdir(parents=True, exist_ok=True)
            self._ff_log = open(log_dir / f"{self.sid}.ffmpeg.log", "wb")
            try:
                self.ffmpeg = subprocess.Popen(
                    cmd,
                    stdin=subprocess.PIPE,
                    stdout=subprocess.DEVNULL,
                    stderr=self._ff_log,
                )
            except Exception as exc:  # noqa: BLE001
                self.started = False
                raise RuntimeError(f"ffmpeg launch failed: {exc}") from exc

            # Dedicated feeder threads: these do the blocking writes to ffmpeg
            # stdin / the audio FIFO, draining the queues the request handlers
            # fill. This keeps the async event loop unblocked.
            self._video_thread = threading.Thread(
                target=self._video_feeder, daemon=True
            )
            self._video_thread.start()
            if self.audio_fifo is not None:
                self._audio_thread = threading.Thread(
                    target=self._audio_feeder, daemon=True
                )
                self._audio_thread.start()

            if LIVE_WINDOW:
                self._open_window()

    def _video_feeder(self) -> None:
        """Drain the video queue into ffmpeg stdin (blocking write off-loop)."""
        ff = self.ffmpeg
        if not ff or not ff.stdin:
            return
        while True:
            item = self._video_q.get()
            if item is None:
                break
            try:
                ff.stdin.write(item)
            except (BrokenPipeError, ValueError, OSError):
                break
        try:
            ff.stdin.close()
        except Exception:  # noqa: BLE001
            pass

    def _audio_feeder(self) -> None:
        """Open the audio FIFO for write (ffmpeg holds the read end) and drain
        the audio queue into it. Opening blocks until ffmpeg is reading, which
        is why this runs on its own thread."""
        if self.audio_fifo is None:
            return
        try:
            writer = open(self.audio_fifo, "wb", buffering=0)
        except OSError:
            return
        while True:
            item = self._audio_q.get()
            if item is None:
                break
            try:
                writer.write(item)
            except (BrokenPipeError, ValueError, OSError):
                break
        try:
            writer.close()
        except Exception:  # noqa: BLE001
            pass

    def _open_window(self) -> None:
        """Best-effort live window: open a browser tab on the server's viewer
        page, which shows the MJPEG stream in an <img> (browsers render
        multipart/x-mixed-replace reliably; VLC does not). We wait for the
        first frame so the window does not open on an empty stream."""

        def _launch():
            for _ in range(50):
                if self.stopped:
                    return
                if self.latest_jpeg is not None:
                    break
                time.sleep(0.2)
            port = int(os.environ.get("JARVIS_PORT", "8787"))
            url = f"http://127.0.0.1:{port}/cam/view/{self.sid}"
            try:
                if BROWSER:
                    # App mode = a clean dedicated window, no tabs/omnibox. Own
                    # process group + user-data-dir so we can close just this
                    # window on stop without touching the user's other Chrome.
                    self.vlc = subprocess.Popen(
                        [BROWSER, f"--app={url}",
                         "--window-size=720,720",
                         "--no-first-run", "--no-default-browser-check",
                         f"--user-data-dir=/tmp/jarvis_cam/chrome-{self.sid}"],
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL,
                        start_new_session=True,
                    )
                elif VLC and Path(VLC).exists():
                    self.vlc = subprocess.Popen(
                        [VLC, "--no-video-title-show",
                         f"http://127.0.0.1:{port}/cam/live/{self.sid}"],
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL,
                    )
            except Exception:  # noqa: BLE001
                self.vlc = None
        threading.Thread(target=_launch, daemon=True).start()

    # -- stream ingestion ---------------------------------------------------

    def write_video(self, frame: bytes) -> None:
        """Enqueue a JPEG frame for the video feeder thread. Drops the oldest
        frame when the queue is full so a slow disk never backs up memory.
        Also keep the latest frame for the HTTP-MJPEG live view."""
        if self.stopped:
            return
        self.bytes_video += len(frame)
        # Latest-frame snapshot for /cam/live/<sid> (robust late-join view).
        self.latest_jpeg = frame
        self._frame_seq += 1
        try:
            self._video_q.put_nowait(frame)
        except queue.Full:
            try:
                self._video_q.get_nowait()
            except queue.Empty:
                pass
            try:
                self._video_q.put_nowait(frame)
            except queue.Full:
                pass

    def has_audio(self) -> bool:
        return self.audio_fifo is not None

    def write_audio(self, data: bytes) -> None:
        """Enqueue an AAC chunk for the audio feeder thread."""
        if self.stopped or self.audio_fifo is None:
            return
        self.bytes_audio += len(data)
        try:
            self._audio_q.put_nowait(data)
        except queue.Full:
            try:
                self._audio_q.get_nowait()
            except queue.Empty:
                pass
            try:
                self._audio_q.put_nowait(data)
            except queue.Full:
                pass

    # -- finalize -----------------------------------------------------------

    def stop(self) -> dict:
        with self.lock:
            if self.stopped:
                return self._meta()
            self.stopped = True

        # Signal both feeder threads to finish; they close ffmpeg stdin and the
        # audio FIFO, which makes ffmpeg see EOF and finalize the mp4.
        try:
            self._video_q.put_nowait(None)
        except queue.Full:
            # Make room for the sentinel.
            try:
                self._video_q.get_nowait()
            except queue.Empty:
                pass
            self._video_q.put(None)
        if self.audio_fifo is not None:
            try:
                self._audio_q.put_nowait(None)
            except queue.Full:
                try:
                    self._audio_q.get_nowait()
                except queue.Empty:
                    pass
                self._audio_q.put(None)

        for t in (self._video_thread, self._audio_thread):
            if t:
                try:
                    t.join(timeout=5)
                except Exception:  # noqa: BLE001
                    pass

        # Wait for ffmpeg to finalize the mp4.
        ff = self.ffmpeg
        if ff:
            try:
                ff.wait(timeout=10)
            except Exception:  # noqa: BLE001
                try:
                    ff.terminate()
                    ff.wait(timeout=5)
                except Exception:  # noqa: BLE001
                    ff.kill()

        # Close the live window. Chrome in --app mode forks a detached process
        # tree, so terminating the launcher is not enough; match this session's
        # unique --user-data-dir and kill those processes. Falls back to the
        # launcher handle for the VLC path.
        chrome_profile = f"/tmp/jarvis_cam/chrome-{self.sid}"
        try:
            subprocess.run(["pkill", "-f", chrome_profile],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                           timeout=5)
        except Exception:  # noqa: BLE001
            pass
        if self.vlc:
            try:
                os.killpg(os.getpgid(self.vlc.pid), signal.SIGTERM)
            except Exception:  # noqa: BLE001
                try:
                    self.vlc.terminate()
                except Exception:  # noqa: BLE001
                    pass

        # Close the ffmpeg stderr log.
        try:
            if self._ff_log:
                self._ff_log.close()
        except Exception:  # noqa: BLE001
            pass

        # Clean up the FIFO.
        try:
            if self.audio_fifo and self.audio_fifo.exists():
                self.audio_fifo.unlink()
        except OSError:
            pass

        meta = self._meta()
        # JSON sidecar next to the mp4.
        try:
            if self.out_path and self.out_path.exists():
                self.out_path.with_suffix(".mp4.json").write_text(
                    json.dumps(meta, indent=2)
                )
        except OSError:
            pass
        return meta

    def _meta(self) -> dict:
        size = 0
        rel = None
        if self.out_path and self.out_path.exists():
            size = self.out_path.stat().st_size
            try:
                rel = self.out_path.relative_to(self.recordings_dir).as_posix()
            except ValueError:
                rel = self.out_path.name
        return {
            "sid": self.sid,
            "device": self.device,
            "started_at": self.started_at,
            "path": rel,
            "bytes": size,
            "bytes_video_in": self.bytes_video,
            "bytes_audio_in": self.bytes_audio,
            "ended_at": datetime.now(timezone.utc).isoformat(),
        }


# JPEG frame markers, used by the MJPEG parser in app.py.
JPEG_SOI = b"\xff\xd8"
JPEG_EOI = b"\xff\xd9"


class CamRegistry:
    """Tracks live sessions by sid."""

    def __init__(self, recordings_dir: Path):
        self.recordings_dir = recordings_dir
        self.sessions: dict[str, CamSession] = {}
        self.lock = threading.Lock()

    def get_or_create(self, sid: str, device: str, with_audio: bool = True) -> CamSession:
        key = _safe(sid, "session")
        with self.lock:
            s = self.sessions.get(key)
            if s is None:
                s = CamSession(key, device, self.recordings_dir, with_audio=with_audio)
                self.sessions[key] = s
            return s

    def get(self, sid: str) -> CamSession | None:
        return self.sessions.get(_safe(sid, "session"))

    def finish(self, sid: str) -> dict | None:
        key = _safe(sid, "session")
        with self.lock:
            s = self.sessions.pop(key, None)
        if s is None:
            return None
        return s.stop()
