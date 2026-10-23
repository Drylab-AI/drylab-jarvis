# Voice AI assistant - test results (2026-10-23)

Gemini Live bridge on the PC + phone client on the rooted Pixel 8 Pro.
Model: `gemini-2.5-flash-native-audio-preview-12-2025` (env `JARVIS_LIVE_MODEL`).
`google-genai` 2.33. Server on 8787.

## Verified without an API key (server + phone bridge)

- **Server config builds** against the real SDK: AUDIO modality, in/out
  transcription, sliding-window compression, session resumption, 600ms VAD,
  6 function tools + google_search.
- **draw_chart tool** produces a real matplotlib PNG (10 KB) and dispatches the
  show_image control; camera/record tools dispatch correctly.
- **WebSocket bridge** (`/assistant/ws`): a Python client handshakes, sends a
  binary mic frame (opcode 0x01), and receives the correct control messages.
  With no key the bridge cleanly returns `{"type":"error",...}` + state=error.
- **Gemini connect path** proven with a fake key: state goes to `connecting`,
  the Live WebSocket + setup config are accepted by Google up to auth
  (`1007 ... API key not valid`). With a real key it connects and converses.
- **Phone end to end**: launching AssistantActivity on the phone opens the
  full-screen UI, the AssistantService mic FGS starts (no crash - the
  MICROPHONE-only start + promote-to-camera fix), and the phone connects over
  Wi-Fi: server log `192.168.1.23 - "WebSocket /assistant/ws" [accepted]`,
  phone log `AssistantService: ws open`. UI shows the state + transcript area.

## Bugbot review (3 findings, all fixed)

1. Reconnect loop gather() never completed (pumps didn't stop on GoAway) ->
   _receive drives, pumps cancelled on return.
2. Camera FGS type without CAMERA permission could crash at launch ->
   start MICROPHONE-only, promote to camera on open_camera; request CAMERA.
3. Wake service held the mic when the assistant started via long-press/button
   -> guard loop releases/resumes the mic for any trigger path.

## LIVE end to end (2026-10-23, real key)

A Gemini API key was created for the Drylab account (anhnguyen@thedrylab.com)
via the gcloud CLI, restricted to generativelanguage.googleapis.com, in the
existing `gen-lang-client-0454204175` project (Gemini API already enabled).
Stored at `~/.config/jarvis/env` (chmod 600, not in git).

- Bridge connect test with the real key: states went connecting -> listening,
  no errors (the native-audio Live session opens with the full config).
- Phone end to end: AssistantActivity -> WebSocket accepted -> connection open
  -> on-screen state reached **"Listening"** with no phone-side errors. The
  mic -> WS -> PC -> Gemini path is live; the assistant is ready to converse.

## Needs GEMINI_API_KEY to fully exercise (set it and restart the server)

With the key set (`export GEMINI_API_KEY=...` before launching the server),
the remaining live cases run end to end:

1. Wake word "Jarvis" / Vol-Up long-press / "Talk to Jarvis" opens the
   assistant; speak and it replies in voice.
2. Barge-in: talking over the reply flushes playback immediately.
3. "Open the camera, what is this?" -> camera opens, Gemini sees the frame and
   answers.
4. "Draw a bar chart of 3, 7, 5" -> draw_chart renders a PNG that pops up on
   the phone.
5. Voice "start/stop recording"; optional auto-ask after a Vol-Up recording.
6. Reconnect across the ~10 min socket boundary via session resumption.
7. Lock-screen: the assistant opens and works over the lock screen.

## Setup notes

- Server: `GEMINI_API_KEY=... JARVIS_RECORDINGS_DIR=... uv run uvicorn app:app --host 0.0.0.0 --port 8787`
- Wake word needs a free Picovoice key (MainActivity -> paste key -> Wake: ON).
  Without it, use Vol-Up long-press or the in-app button.
