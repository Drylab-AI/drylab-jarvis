# On-phone "Jarvis" wake word: results (2026-10-23)

openWakeWord + ONNX Runtime running on the rooted Pixel 8 Pro. Free, no
account, no key, no expiry; audio stays on the phone until "Jarvis" is heard.
Replaced Picovoice Porcupine, whose free tier ended 2026-06-30.

## Port verification (PC, before touching the phone)

The Kotlin detector is checked against the real Python library on the same
clips. All three implementations agree frame for frame:

| Implementation | jarvis.raw max | frame | other.raw max |
|---|---|---|---|
| `openwakeword` library (v0.4.0) | 0.9987 | 27 | 0.0001 |
| From-scratch Python spec (onnxruntime only) | 0.9987 | 27 | 0.0001 |
| App's `OpenWakeWord.kt` on the PC JVM | 0.9987 | 27 | 0.0001 |

About 1.0-1.2 ms per 80 ms frame on the PC, so roughly 1.5% of one core.
Re-run with the command in `tools/oww/README.md`.

## On-device results

| # | Case | Result | Evidence |
|---|------|--------|----------|
| 1 | Say "Jarvis" -> assistant opens | PASS | `wake word detected (score=0.837)`, `AssistantActivity Displayed +87ms`, assistant reached "Listening" |
| 2 | No false accepts | PASS | 90 s of ambient audio, 0 detections; heartbeat maxScore stayed <= 0.313 vs the 0.5 threshold |
| 3 | Mic contention both ways | PASS | `stopped listening` immediately before `AssistantService: ws open`; after ending the chat, `listening for 'Jarvis'` resumed by itself |
| 4 | Detection is local | PASS by design | the models run in-process from `assets/oww/`; nothing leaves the phone until a wake |
| 5 | Survives a reboot | PASS | after reboot: `WakeService start requested` in service.log and `alive rms=117` with no interaction |
| 6 | Other triggers still work | PASS | the running daemon contains `Volume Up long press -> assistant` and `Volume Up tap -> toggle`; the daemon's component+action launches the assistant |

Models load once per service; ONNX Runtime arm64 lib loads cleanly
(`libonnxruntime4j_jni.so ... ok`). The `ORTTelemetry` UnsatisfiedLinkError in
logcat is harmless: Microsoft's optional telemetry HttpClient is not wired on
Android and inference is unaffected.

## Bugs found and fixed during this work

1. Bugbot: a microphone-type foreground service may only start while
   RECORD_AUDIO is granted, but `onStartCommand` called `startForeground`
   unconditionally - the first toggle crashed the app. Now it bails out first,
   and MainActivity starts the service only after the grant lands.
2. Bugbot: listening state was shared across sessions, so a thread that
   outlived its stop could run a second capture or leak a detector. Listening
   is now per-session and the detector is created/closed under a lock.
3. Boot start fired before the user was unlocked and failed silently (the
   app's code and settings live in credential-encrypted storage). `service.sh`
   now retries in the background until the start succeeds.

## Notes

- Threshold 0.5 with a 2 s debounce and a 5 s mic handoff after a wake.
  Lower it toward 0.3 if detection misses in noise; raise it if false accepts
  appear. The 10 s `alive rms=... maxScore=...` heartbeat is there for tuning.
- APK is 51 MB: ONNX Runtime ships ~35 MB of arm64 native code. Other ABIs are
  excluded (`abiFilters`), which saved about 105 MB.
- Models are CC BY-NC-SA 4.0 - fine for personal use, attributed in
  `tools/oww/README.md`.
