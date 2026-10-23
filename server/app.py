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

import json
import os
import re
import uuid
from datetime import datetime, timezone
from pathlib import Path

from fastapi import FastAPI, Form, Header, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse

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
