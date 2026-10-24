"""Jarvis upload server.

Receives voice clips recorded on the rooted Pixel when Volume Up is pressed,
and stores them under recordings/YYYY-MM-DD/ with a JSON sidecar.

Endpoints:
- GET  /health  -> {"ok": true}
- POST /upload   -> multipart: file, device, started_at, duration_ms
                    streams the file to disk, returns {"id","path","bytes"}.

Safety: filenames are sanitised so a request can never write outside the
recordings dir, and uploads are capped at MAX_UPLOAD_BYTES.

An optional shared-secret header (X-Jarvis-Token) is OFF by default. Set the
JARVIS_TOKEN env var to turn it on; requests must then send the same value.
This is a LAN machine, so the token is just a switch you can flip later.
"""

from __future__ import annotations

import asyncio
import json
import os
import re
import uuid
from datetime import datetime, timezone
from pathlib import Path

from fastapi import (
    FastAPI,
    Form,
    Header,
    HTTPException,
    Request,
    UploadFile,
    WebSocket,
    WebSocketDisconnect,
)
from fastapi.responses import JSONResponse, StreamingResponse

from assistant import OP_CAM_JPEG, OP_MIC_PCM, GeminiLiveBridge, PhoneLink
from assistant_tools import ToolRouter
from camera_stream import JPEG_EOI, JPEG_SOI, CamRegistry

# Store recordings in a sibling of this file's parent: jarvis/recordings/
BASE_DIR = Path(__file__).resolve().parent.parent
RECORDINGS_DIR = Path(os.environ.get("JARVIS_RECORDINGS_DIR", BASE_DIR / "recordings"))

# 200 MB hard cap per upload, matching the plan. A ~2h AAC 128 kbps clip is
# well under this; the cap only guards against a runaway or hostile request.
MAX_UPLOAD_BYTES = int(os.environ.get("JARVIS_MAX_UPLOAD_BYTES", 200 * 1024 * 1024))
CHUNK = 1024 * 1024  # 1 MB streaming chunks, so we never load the clip into RAM

# Optional shared secret. Empty -> auth disabled.
JARVIS_TOKEN = os.environ.get("JARVIS_TOKEN", "").strip()

app = FastAPI(title="Jarvis upload server", version="0.1.0")

# Front-camera live-stream sessions (Volume Down feature).
cam_registry = CamRegistry(RECORDINGS_DIR)

# Live assistant bridges, newest last. Used by the /assistant/say test hook.
ACTIVE_BRIDGES: list = []


def _safe_component(value: str, fallback: str) -> str:
    """Reduce a user-supplied string to a safe single path component.

    Keeps only [A-Za-z0-9._-]; everything else (including '/', '..', spaces)
    becomes '-'. The result can never traverse directories.
    """
    value = (value or "").strip()
    cleaned = re.sub(r"[^A-Za-z0-9._-]", "-", value)
    cleaned = cleaned.strip(".-") or fallback
    return cleaned[:128]


@app.get("/health")
def health() -> dict:
    return {"ok": True}


@app.post("/upload")
async def upload(
    request: Request,
    file: UploadFile,
    device: str = Form("unknown"),
    started_at: str = Form(""),
    duration_ms: int = Form(0),
    x_jarvis_token: str | None = Header(default=None),
) -> JSONResponse:
    # Auth gate (only when a token is configured).
    if JARVIS_TOKEN and x_jarvis_token != JARVIS_TOKEN:
        raise HTTPException(status_code=401, detail="bad or missing token")

    # Reject obviously-too-big uploads early using Content-Length when present.
    content_length = request.headers.get("content-length")
    if content_length and content_length.isdigit() and int(content_length) > MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=413, detail="file too large")

    device_c = _safe_component(device, "device")
    started_c = _safe_component(started_at, "")
    if not started_c:
        started_c = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")

    # Day folder from started_at when it looks like an ISO date, else today.
    day = _day_from_started(started_at)
    day_dir = RECORDINGS_DIR / day
    day_dir.mkdir(parents=True, exist_ok=True)

    rec_id = uuid.uuid4().hex[:12]
    ext = _safe_component(Path(file.filename or "").suffix.lstrip("."), "m4a") or "m4a"
    fname = f"{started_c}_{device_c}_{rec_id}.{ext}"
    dest = day_dir / fname

    # Stream to disk with a running size cap.
    written = 0
    try:
        with dest.open("wb") as out:
            while True:
                chunk = await file.read(CHUNK)
                if not chunk:
                    break
                written += len(chunk)
                if written > MAX_UPLOAD_BYTES:
                    out.close()
                    dest.unlink(missing_ok=True)
                    raise HTTPException(status_code=413, detail="file too large")
                out.write(chunk)
    except HTTPException:
        raise
    except Exception as exc:  # noqa: BLE001 - surface any IO error cleanly
        dest.unlink(missing_ok=True)
        raise HTTPException(status_code=500, detail=f"write failed: {exc}") from exc

    if written == 0:
        dest.unlink(missing_ok=True)
        raise HTTPException(status_code=400, detail="empty file")

    # JSON sidecar next to the clip with the metadata we were given.
    sidecar = dest.with_suffix(dest.suffix + ".json")
    meta = {
        "id": rec_id,
        "device": device,
        "started_at": started_at,
        "duration_ms": duration_ms,
        "bytes": written,
        "filename": fname,
        "received_at": datetime.now(timezone.utc).isoformat(),
        "content_type": file.content_type,
    }
    sidecar.write_text(json.dumps(meta, indent=2))

    rel = dest.relative_to(RECORDINGS_DIR).as_posix()
    return JSONResponse({"id": rec_id, "path": rel, "bytes": written})


def _day_from_started(started_at: str) -> str:
    """Return a YYYY-MM-DD folder name derived from started_at, else today."""
    s = (started_at or "").strip()
    # Accept ISO-8601 like 2026-10-23T13:00:00Z or compact 20261023T...
    m = re.match(r"(\d{4})-?(\d{2})-?(\d{2})", s)
    if m:
        return f"{m.group(1)}-{m.group(2)}-{m.group(3)}"
    return datetime.now(timezone.utc).strftime("%Y-%m-%d")


# ---------------------------------------------------------------------------
# Front-camera live stream (Volume Down). Two concurrent streaming POSTs per
# session feed one ffmpeg that saves an mp4 and opens a live window.
# ---------------------------------------------------------------------------


def _check_token(token: str | None) -> None:
    if JARVIS_TOKEN and token != JARVIS_TOKEN:
        raise HTTPException(status_code=401, detail="bad or missing token")


@app.post("/cam/video/{sid}")
async def cam_video(
    sid: str,
    request: Request,
    x_device: str = Header(default="pixel"),
    x_jarvis_token: str | None = Header(default=None),
) -> JSONResponse:
    """Ingest the MJPEG video stream for a session and feed ffmpeg."""
    _check_token(x_jarvis_token)
    session = cam_registry.get_or_create(sid, x_device)
    try:
        session.ensure_started()
    except RuntimeError as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc

    # Parse multipart MJPEG as it streams in; feed each JPEG frame to ffmpeg.
    # We scan for JPEG SOI/EOI markers rather than trusting multipart framing.
    buf = bytearray()
    async for chunk in request.stream():
        if not chunk:
            continue
        buf.extend(chunk)
        while True:
            start = buf.find(JPEG_SOI)
            if start < 0:
                if len(buf) > 1:
                    del buf[:-1]
                break
            end = buf.find(JPEG_EOI, start + 2)
            if end < 0:
                if start > 0:
                    del buf[:start]
                break
            frame = bytes(buf[start:end + 2])
            del buf[:end + 2]
            session.write_video(frame)

    return JSONResponse({"ok": True, "sid": session.sid})


@app.post("/cam/audio/{sid}")
async def cam_audio(
    sid: str,
    request: Request,
    x_device: str = Header(default="pixel"),
    x_jarvis_token: str | None = Header(default=None),
) -> JSONResponse:
    """Ingest the AAC audio stream for a session and feed ffmpeg's FIFO."""
    _check_token(x_jarvis_token)
    session = cam_registry.get_or_create(sid, x_device)
    try:
        session.ensure_started()
    except RuntimeError as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc

    if not session.has_audio():
        # Audio disabled for this session; drain the body so the client isn't
        # left blocking on a half-open connection.
        async for _ in request.stream():
            pass
        return JSONResponse({"ok": True, "sid": session.sid, "audio": False})

    # Enqueue chunks; the session's feeder thread does the blocking FIFO write.
    async for chunk in request.stream():
        if chunk:
            session.write_audio(chunk)

    return JSONResponse({"ok": True, "sid": session.sid})


@app.post("/cam/stop/{sid}")
def cam_stop(
    sid: str,
    x_jarvis_token: str | None = Header(default=None),
) -> JSONResponse:
    """Finalize a session: close ffmpeg, write the mp4 + sidecar, close window."""
    _check_token(x_jarvis_token)
    meta = cam_registry.finish(sid)
    if meta is None:
        raise HTTPException(status_code=404, detail="unknown session")
    return JSONResponse(meta)


@app.get("/cam/view/{sid}")
def cam_view(sid: str):
    """A tiny HTML page that shows the live MJPEG stream in an <img>.

    Browsers render multipart/x-mixed-replace in an <img> tag natively and
    reliably (unlike VLC), so this is the live window we open."""
    from fastapi.responses import HTMLResponse

    safe = re.sub(r"[^A-Za-z0-9._-]", "", sid)[:64]
    html = f"""<!doctype html><html><head><meta charset="utf-8">
<title>Jarvis cam {safe}</title>
<style>html,body{{margin:0;background:#000;height:100%}}
img{{display:block;width:100vw;height:100vh;object-fit:contain}}</style>
</head><body>
<img src="/cam/live/{safe}" alt="live camera">
</body></html>"""
    return HTMLResponse(html)


@app.post("/assistant/say")
async def assistant_say(
    text: str = Form(...),
    x_jarvis_token: str | None = Header(default=None),
) -> JSONResponse:
    """Debug/test only: inject a user turn as text into the live session.

    Lets the assistant flow (tools, charts, replies) be driven automatically
    without speaking into the phone. Returns 409 when no session is active.
    """
    _check_token(x_jarvis_token)
    bridge = ACTIVE_BRIDGES[-1] if ACTIVE_BRIDGES else None
    if bridge is None:
        raise HTTPException(status_code=409, detail="no active assistant session")
    ok = await bridge.inject_text(text)
    if not ok:
        raise HTTPException(status_code=409, detail="session not ready")
    return JSONResponse({"ok": True, "sent": text})


@app.get("/assistant/status")
async def assistant_status(
    x_jarvis_token: str | None = Header(default=None),
) -> JSONResponse:
    """Debug/test only: counters of the active session for the e2e harness.

    Reading these numbers directly is more reliable than parsing logs, e.g.
    "how many camera frames reached Gemini" vs "how many the phone sent".
    """
    _check_token(x_jarvis_token)
    bridge = ACTIVE_BRIDGES[-1] if ACTIVE_BRIDGES else None
    if bridge is None:
        return JSONResponse({"active": False})
    return JSONResponse({"active": True, **bridge.stats()})


@app.websocket("/assistant/ws")
async def assistant_ws(websocket: WebSocket) -> None:
    """Realtime voice-assistant bridge.

    The phone sends mic PCM (opcode 0x01) and camera JPEG (0x02) as binary
    frames, plus JSON control (start/stop). The server runs a Gemini Live
    session and sends back assistant PCM (0x11) and JSON control (state,
    transcript, tool, show_image, flush).
    """
    # Optional token gate (query param, since WS has no custom headers easily).
    if JARVIS_TOKEN and websocket.query_params.get("token") != JARVIS_TOKEN:
        await websocket.close(code=4401)
        return

    await websocket.accept()

    send_lock = asyncio.Lock()

    async def send_json(obj: dict) -> None:
        async with send_lock:
            await websocket.send_text(json.dumps(obj))

    async def send_bytes(data: bytes) -> None:
        async with send_lock:
            await websocket.send_bytes(data)

    link = PhoneLink(send_json, send_bytes)
    tools = ToolRouter(link)
    bridge = GeminiLiveBridge(link, tools)

    # Run the Gemini session concurrently with the phone receive loop.
    ACTIVE_BRIDGES.append(bridge)
    run_task = asyncio.create_task(bridge.run())
    try:
        while True:
            msg = await websocket.receive()
            if msg.get("type") == "websocket.disconnect":
                break
            data = msg.get("bytes")
            if data is not None and len(data) >= 1:
                op = data[0]
                payload = data[1:]
                if op == OP_MIC_PCM:
                    bridge.push_mic(payload)
                elif op == OP_CAM_JPEG:
                    bridge.push_cam(payload)
                continue
            text = msg.get("text")
            if text:
                try:
                    ctl = json.loads(text)
                except ValueError:
                    continue
                # Reserved for future client control (e.g. {"type":"bye"}).
                if ctl.get("type") == "bye":
                    break
    except WebSocketDisconnect:
        pass
    except Exception:  # noqa: BLE001
        pass
    finally:
        if bridge in ACTIVE_BRIDGES:
            ACTIVE_BRIDGES.remove(bridge)
        bridge.close()
        run_task.cancel()
        try:
            await run_task
        except Exception:  # noqa: BLE001
            pass


@app.get("/cam/live/{sid}")
async def cam_live(sid: str) -> StreamingResponse:
    """Serve the live view as an MJPEG stream (multipart/x-mixed-replace).

    Re-serves the session's latest JPEG frame, so VLC or a browser can attach
    at any time and immediately see the current frame - no UDP/TCP join race.
    """
    session = cam_registry.get(sid)
    if session is None:
        raise HTTPException(status_code=404, detail="unknown session")

    boundary = "jarvislive"

    async def gen():
        import anyio

        last_seq = -1
        idle = 0
        while True:
            s = cam_registry.get(sid)
            if s is None or s.stopped:
                break
            frame = s.latest_jpeg
            seq = s._frame_seq
            if frame is not None and seq != last_seq:
                last_seq = seq
                idle = 0
                yield (
                    b"--" + boundary.encode() + b"\r\n"
                    b"Content-Type: image/jpeg\r\n"
                    b"Content-Length: " + str(len(frame)).encode() + b"\r\n\r\n"
                    + frame + b"\r\n"
                )
            else:
                idle += 1
                if idle > 600:  # ~30 s with no new frame -> end
                    break
            await anyio.sleep(0.05)

    return StreamingResponse(
        gen(),
        media_type=f"multipart/x-mixed-replace; boundary={boundary}",
    )
