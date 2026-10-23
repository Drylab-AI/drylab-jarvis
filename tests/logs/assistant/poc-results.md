# POC: "hey jarvis" -> greet -> open camera -> draw a line chart

Run automatically on the rooted Pixel 8 Pro (no manual speaking needed, see
"How to re-run" below). Screenshot: `poc-chart-on-phone.png`.

## The flow, as it actually ran

| Step | What happened | Evidence |
|---|---|---|
| 1 | "hey jarvis" detected on-device | `test: wake word detected (score=0.888)` |
| 1 | Assistant opened itself from the background | `BAL_ALLOW_SAW_PERMISSION`, `Displayed AssistantActivity +84ms`, one `ws open` |
| 1 | Jarvis speaks first and asks what you want | transcript: "Hello, how can I help you today?" |
| 2 | "open the camera" -> camera really opens | `tool from server: open_camera`, HAL: `camera_name=RearNormal ... PassiveFocused`, `camera_type: Back` |
| 2 | Jarvis confirms aloud | transcript: "Camera is open." |
| 3 | "draw a line chart ..." -> chart appears on the phone | transcript: "Working on it." then "A line chart has been drawn and is now visible on your screen."; full-screen `imageOverlay` with caption "User Provided Numbers" |

The conversation stays open the whole time (one Gemini session, no reconnects).

## Bugs found and fixed to get here

1. **Jarvis never spoke first.** Added a greeting sent as the session opens.
2. **Every audio chunk popped up as a bogus image.** On native-audio models the
   spoken reply also arrives as `inline_data`, so `show_image` fired ~74 times
   per reply. Now only parts with an `image/*` mime type are forwarded.
3. **Session died mid-conversation** with `1007 ... CONTENT_TYPE_AUDIO is not
   supported for this model configuration`. Mixing `send_client_content` with a
   live audio stream is rejected; greeting/injection now use
   `send_realtime_input(text=...)`. Socket errors went from 4 to 0.
4. **Wake word opened nothing.** Three layered causes:
   `isRunning` is static and a dying instance cleared it right after a new one
   set it, so `onWake` returned early; a background service cannot
   `startActivity` (`BAL_BLOCK`); and a full-screen intent only takes over a
   *locked* screen. Fixed by not gating `onWake` on `isRunning`, declaring
   `SYSTEM_ALERT_WINDOW` (gives the background-launch exemption,
   `BAL_ALLOW_SAW_PERMISSION`), and keeping the full-screen notification only
   as a fallback.
5. **Two Gemini sessions per wake.** The direct launch and the notification both
   started the activity. The notification is now a fallback only, and
   `AssistantActivity` skips starting the service when it is already running.
6. **Camera tool said "open" but never opened.** Control messages arrive on the
   OkHttp reader thread; CameraX binding must run on the main thread. Tool
   handling is now posted to the main looper.
7. **Mic-type foreground service crashed** when started from the background
   ("Foreground service started from background can not have ... microphone
   access"). Now falls back to a type-less FGS and the mic loop retries.
8. **Wake threshold too high for live speech.** Clean utterances score 0.6-0.99
   but quieter ones landed near 0.4, while ambient noise peaked at 0.31.
   Lowered 0.5 -> 0.35.

## How to re-run it automatically

Two test-only hooks make the whole flow scriptable:

```bash
# 1. simulate saying "hey jarvis" (feeds a raw 16 kHz file to the detector)
adb push tools/oww/jarvis.raw /data/local/tmp/jarvis.raw
adb shell su -c "am start-foreground-service -n com.jarvis.voicerec/.WakeService \
  -a com.jarvis.voicerec.WAKE_TEST --es pcm /data/local/tmp/jarvis.raw"

# 2 and 3. speak to the assistant as text
curl -X POST -F "text=Open the camera." http://127.0.0.1:8787/assistant/say
curl -X POST -F "text=Draw a line chart of 4, 11, 7, 18, 14 and show it on my screen." \
  http://127.0.0.1:8787/assistant/say
```

One-time per install (needs root): `appops set com.jarvis.voicerec
SYSTEM_ALERT_WINDOW allow` so the wake word can open the assistant from the
background.
