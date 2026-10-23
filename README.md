# drylab-jarvis

Pixel 8 Pro (husky) hardware-button voice recorder, camera streamer and voice
AI assistant, all talking to a FastAPI server on this PC.

A root daemon (Magisk module) grabs the physical keys, a companion Android app
does the recording/streaming, and the server stores clips and bridges the
assistant to Gemini Live.

| Trigger | What happens |
|---------|--------------|
| Volume Up (tap) | Toggle mic recording; clip uploads to `/upload` |
| Volume Down (tap) | Toggle back-camera live stream to the PC, muxed to `.mp4` |
| "Hey Jarvis" (or Volume Up long-press) | Open the voice assistant over the lockscreen |

## Layout

| Path | What |
|------|------|
| `server/` | FastAPI upload server (`/health`, `/upload`). Runs with `uv`. |
| `android-app/` | Kotlin app: trampoline activity, mic foreground service, AAC recorder, WorkManager upload queue, settings screen. |
| `magisk-module/` | `volrecd` C daemon (grabs Volume Up, forwards other keys) + module scripts + `build.sh`. |
| `firmware/` | Stock `init_boot.img` backups (gitignored). |
| `recordings/` | Uploaded clips, `YYYY-MM-DD/` (gitignored). |
| `tests/` | Test matrix (`cases.md`) and proof logs. |

## Quick start (software)

```bash
# Server (port 8787 here; 8765/8766 are taken by another local app)
cd server && uv run uvicorn app:app --host 0.0.0.0 --port 8787

# Daemon + Magisk module zip
cd magisk-module && ANDROID_HOME=~/Android/Sdk ./build.sh   # -> build/volrec.zip

# App APK
cd android-app && JAVA_HOME=~/.local/jdk-17 ANDROID_HOME=~/Android/Sdk \
  ./gradlew :app:assembleDebug   # -> app/build/outputs/apk/debug/
```

## Phone bring-up

Unlock the bootloader (wipes the phone), patch `init_boot.img` with Magisk,
flash it, then install the module zip and the app APK. Full step-by-step lives
in the plan. The daemon finds the Volume Up input node at runtime, so reboots
that renumber `/dev/input/eventN` do not break it.

Kill switch: `touch /data/adb/volrec/disable` restores normal volume behaviour.

## Voice assistant

Say "Hey Jarvis" (detected on-device by openWakeWord + ONNX Runtime, no cloud,
no API key) and the assistant opens over the lockscreen. It streams mic audio
up and speech back down over one WebSocket (`/assistant/ws`), bridged on this
PC to the Gemini Live native-audio model.

### What it can do

| Tool | Runs on | Does |
|------|---------|------|
| `open_camera` / `close_camera` | Phone | Turns the back camera on so the model can see (~1 fps) |
| `start_recording` / `stop_recording` | Phone | Mic recording, same pipeline as the Volume Up key |
| `draw_chart` | PC | Bar/line/pie/scatter chart from values, pops up on the phone |
| `run_python` | PC | Runs Python and reports what it prints; a matplotlib figure is shown on the phone automatically |
| web search | Google | `google_search` grounding, for anything that changes over time |

Beyond the tools it is a normal conversation: ask questions, point the camera
at something and ask what it is, or have it read numbers off the camera and
chart them.

### Adding a tool

Two edits in `server/assistant_tools.py`: append a JSON schema in
`declarations()` and handle the name in `dispatch()`. Server-side tools need no
app rebuild — only new *phone* actions do. The description field is the prompt
the model uses to decide when to call it, so make it concrete.

### Config

| Env var | Default | Meaning |
|---------|---------|---------|
| `GEMINI_API_KEY` | — | Required; the assistant refuses to start without it |
| `JARVIS_LIVE_MODEL` | `gemini-2.5-flash-native-audio-preview-12-2025` | Live model |
| `JARVIS_ALLOW_CODE` | `1` | Set `0` to disable `run_python` |
| `JARVIS_CODE_TIMEOUT` | `20` | Seconds before a snippet is killed |
| `JARVIS_SYSTEM_PROMPT` / `JARVIS_GREETING` | built-in | Override behaviour |

**`run_python` runs real code as your user**, in a temp cwd with a hard
timeout and process-group kill, but with your file permissions. That is the
point (it reads your disk, files and machine state), but set
`JARVIS_ALLOW_CODE=0` if you ever expose this server beyond your LAN.

Run the server so it survives the launching shell:

```bash
cd server && setsid nohup .venv/bin/python -m uvicorn app:app \
  --host 0.0.0.0 --port 8787 > /tmp/jarvis-server.log 2>&1 &
```

Test without speaking: `curl -X POST -F "text=your question" \
http://127.0.0.1:8787/assistant/say`
