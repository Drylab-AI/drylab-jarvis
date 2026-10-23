#!/usr/bin/env bash
# Launch the Jarvis upload + camera server, fully detached from the caller's
# shell so it survives the controlling terminal exiting.
#
# Env:
#   JARVIS_RECORDINGS_DIR  where clips/videos are saved (default ../recordings)
#   JARVIS_LIVE_WINDOW     1 (default) opens a VLC window per camera stream, 0 off
#   PORT                   listen port (default 8787)
set -euo pipefail
cd "$(dirname "$0")"

PORT="${PORT:-8787}"
LOG="${JARVIS_SERVER_LOG:-../tests/logs/server-run.log}"

exec setsid bash -c "
  exec uv run uvicorn app:app --host 0.0.0.0 --port $PORT
" >"$LOG" 2>&1 < /dev/null &
echo "server pid $!"
