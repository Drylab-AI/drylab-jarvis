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
PC to Gemini Live (`gemini-3.8-live`).

### How it stays fast (an "interaction model", done in two layers)

Modelled on Thinking Machines' interaction models: a fast layer stays present
in the conversation while a smarter model does the thinking.

- **On the phone (instant).** `LocalTurnTaker.kt` runs Silero VAD on the mic.
  - It notices you've stopped talking and plays a short filler ("hmm", "let
    me see") about 250 ms later.
  - It stops Jarvis's playback within about 100 ms when you talk over it.
  - It never decides what to say.
- **In the cloud (smart).** Gemini 3.8 Live answers, sees the camera and
  calls tools.
  - Slow tools (`run_python`, `draw_chart`) run in the background, so Jarvis
    keeps talking and brings the result in when it's ready.
  - Phone actions (camera, recording) block, so it never says "the camera is
    open" before it is.

Measured on the phone (`tests/logs/interaction/latency.md`). The real reply
is now limited by Gemini itself (about 1.1-1.2 s from this route); our
pipeline adds about 0 ms on top.

| | Before | Now |
|-|--------|-----|
| First sound after you stop talking | 4.9 s | 0.25 s (filler) |
| Real answer, median | 4.9 s | 1.1-1.3 s |
| Cut-in: you talk, Jarvis stops | ~0.2 s via the server | ~0.1 s on the phone |

### What it can do

| Tool | Runs on | Does |
|------|---------|------|
| `open_camera` / `close_camera` | Phone | Turns the back camera on. Gemini sees it (newest frame, 1 fps), and the PC shows it live (~10 fps window) and saves `recordings/<day>/assist-*_assistant_cam.mp4` |
| `start_recording` / `stop_recording` | Phone | Mic recording, same pipeline as the Volume Up key |
| `draw_chart` | PC | Bar/line/pie/scatter chart from values, pops up on the phone |
| `run_python` | PC | Runs Python and reports what it prints; a matplotlib figure is shown on the phone automatically |
| web search | Google | `google_search` grounding, for anything that changes over time (dropped automatically if its quota runs out) |

The Volume-Down camera stream and the assistant camera can't both use the
camera. Whichever starts second is refused, with a vibration.

### Adding a tool

Two edits in `server/assistant_tools.py`: append a JSON schema in
`_schemas()` and handle the name in `dispatch()`.
- Add the name to `BLOCKING` if the reply depends on it finishing.
- Server-side tools need no app rebuild; only new *phone* actions do.
- The description field is the prompt the model uses to decide when to call
  it, so make it concrete.

### Config

Put secrets in `~/.config/jarvis/env` (read by `run-server.sh` and the
systemd unit).

| Env var | Default | Meaning |
|---------|---------|---------|
| `GEMINI_API_KEY` | (none) | Required. The assistant refuses to start without it |
| `JARVIS_LIVE_MODEL` | `gemini-3.8-live` | Live model. `gemini-2.5-flash-native-audio-preview-12-2025` still works as a fallback |
| `JARVIS_VOICE` | `Charon` | Jarvis's voice (the bundled fillers use the same one) |
| `JARVIS_VAD_SILENCE_MS` | `450` | Server end-of-speech silence |
| `JARVIS_SEARCH` | `1` | Set `0` to turn off web search |
| `JARVIS_ALLOW_CODE` | `1` | Set `0` to disable `run_python` |
| `JARVIS_CODE_TIMEOUT` | `20` | Seconds before a snippet is killed |
| `JARVIS_LIVE_WINDOW` | `1` | Set `0` to stop camera windows opening on the PC (recording still happens) |
| `JARVIS_SYSTEM_PROMPT` / `JARVIS_GREETING` | built-in | Override behaviour |

**`run_python` runs real code as your user**, in a temp cwd with a hard
timeout and process-group kill, but with your file permissions. That is the
point (it reads your disk, files and machine state), but set
`JARVIS_ALLOW_CODE=0` if you ever expose this server beyond your LAN.

Run the server so it survives the launching shell: `server/run-server.sh`.

## Self-test (no manual testing)

The e2e harness drives the real phone:
- It talks by injecting TTS clips into the assistant mic (debug builds only).
- It presses real hardware keys with root `sendevent`.
- It reads the phone's own `JARVIS_T` timeline, so latency comes from what
  was actually played.
- It checks the PC camera viewer headlessly, so nothing opens on your
  desktop.

```bash
set -a; . ~/.config/jarvis/env; set +a
server/.venv/bin/python tests/e2e/make_clips.py      # once: user lines + fillers
server/.venv/bin/python tests/e2e/run_e2e.py --label mine          # all 11 scenarios
server/.venv/bin/python tests/e2e/run_e2e.py --only cut_in filler  # some
server/.venv/bin/python tests/test_assistant_bridge.py             # offline, no phone
server/.venv/bin/python tests/test_run_python.py
```

Reports go to `tests/logs/interaction/results-<label>.md`, with an evidence
path per case. The case list is `tests/logs/interaction/cases.md`. The
`no_self_interrupt` case uses the real speaker and mic, so the phone speaks out
loud for about 10 s.
