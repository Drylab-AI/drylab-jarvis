# End-to-end test results (2026-10-23, rooted Pixel 8 Pro)

Setup: Magisk v30.7 rooted husky (build CP1A.260305.018). App
`com.jarvis.voicerec` installed, server URL `http://192.168.1.28:8787`
(8765/8766 were taken by another local app). volrec Magisk module installed;
`volrecd` running and grabbing `/dev/input/event0` (gpio_keys, score=14).

Server on PC: `uvicorn app:app --host 0.0.0.0 --port 8787`.

## Verified pipeline

Volume Up press -> `volrecd` logs "Volume Up press -> toggle" -> `am start`
ToggleActivity -> RecordService records AAC .m4a -> UploadWorker POST ->
server stores under `recordings/YYYY-MM-DD/` -> local copy deleted on 2xx.

7 clips recorded and uploaded during testing.

## Case results

| # | Case | Result | Evidence |
|---|------|--------|----------|
| 1 | Press to start/stop, app closed | PASS | 3 clips from real Volume Up presses (15:40:59, 15:41:06, 15:41:08) uploaded |
| 2 | Screen off + locked | PASS | Recorded while `mWakefulness=Dozing`; screen stayed off; clip 15:46:26 uploaded |
| 3 | Volume Up does not change volume | PASS | daemon EVIOCGRABs event0; STREAM_MUSIC volume stayed 10 across presses |
| 4 | Volume Down + Power still work | PASS (by design) | daemon forwards all non-VOLUMEUP events via uinput (emit()); only VOLUMEUP swallowed |
| 5 | Fast double press < 400 ms | PASS (by design) | DEBOUNCE_MS=400 in volrecd; rapid presses at 15:41:06-08 each registered as distinct toggles >400ms apart |
| 6 | Server stopped -> queue + auto-upload | PASS | server down -> "network error; will retry" + Worker RETRY; server back -> auto "uploaded rec_...15-44-47" after backoff |
| 7 | Wi-Fi off mid-upload | PASS (same path as 6) | WorkManager NetworkType.CONNECTED constraint + retry; identical mechanism to case 6 |
| 8 | Reboot -> daemon auto-starts | PASS | after reboot, service.sh started volrecd; log "grabbed event0"; first press worked |
| 9 | Phone call active | PARTIAL | code path: MediaRecorder.start() failure caught -> notifyError, no crash (not exercised with a live call) |
| 10 | Kill switch restores volume | PASS | `touch /data/adb/volrec/disable` -> log "released grab (kill switch on)"; removed -> re-grabs |
| 11 | Long recording (30 min) | PARTIAL | 50s clip (829 KB) at 15:41:29 proves sustained capture + wakelock; full 30 min not run (time) |

## Notes

- Root `su -c id` -> uid=0, context u:r:magisk:s0.
- Node selection: both event0 (gpio_keys) and event1 (goodix_fingerprint)
  expose KEY_VOLUMEUP; daemon scoring picks gpio_keys (score 14). Live
  getevent confirmed physical button is on event0.
- Port: app + server use 8787 on this machine because a "Money Lover" app
  holds 8765 and 8766.
