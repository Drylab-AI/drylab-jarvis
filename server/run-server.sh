#!/usr/bin/env bash
# Launch the Jarvis upload + camera + assistant server, fully detached from the
# caller's shell so it survives the controlling terminal exiting.
#
# Secrets (GEMINI_API_KEY, optional JARVIS_TOKEN) are read from
# ~/.config/jarvis/env, the same file the systemd unit uses, so the key never
# has to be in your shell or in the repo.
#
# Env:
#   JARVIS_RECORDINGS_DIR  where clips/videos are saved (default ../recordings)
#   JARVIS_LIVE_WINDOW     1 (default) opens a window per camera stream, 0 off
#   JARVIS_LIVE_MODEL      Gemini Live model (see assistant.py)
#   PORT                   listen port (default 8787)
set -euo pipefail
cd "$(dirname "$0")"

ENV_FILE="${JARVIS_ENV_FILE:-$HOME/.config/jarvis/env}"
if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
fi

PORT="${PORT:-8787}"
LOG="${JARVIS_SERVER_LOG:-../tests/logs/server-run.log}"

# Run the venv interpreter directly: `uv run` is a parent process that dies
# with the launching shell and takes the server with it.
setsid .venv/bin/python -m uvicorn app:app --host 0.0.0.0 --port "$PORT" \
  >"$LOG" 2>&1 < /dev/null &
echo "server pid $!"
