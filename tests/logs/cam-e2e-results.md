# Volume Down back-camera stream: end-to-end results (2026-10-23)

Rooted Pixel 8 Pro, app `com.jarvis.voicerec`, server on 8787 with the live
VLC window enabled. volrec daemon grabs event0 and handles both volume keys.

## Verified pipeline

Volume Down press -> volrecd logs "Volume Down press -> cam toggle" -> am start
CamToggleActivity (showWhenLocked) -> CameraService (FGS camera|microphone) ->
CameraX BACK camera -> JPEG frames streamed as MJPEG to POST /cam/video/<sid>,
mic -> AAC (ADTS) to POST /cam/audio/<sid> -> one ffmpeg per session muxes both
into recordings/YYYY-MM-DD/<sid>_<device>_cam.mp4 AND tees mpegts over UDP to a
VLC live window. Second press stops; ffmpeg finalizes the mp4 + JSON sidecar.

## Evidence

- Real physical Volume Down presses logged by the daemon:
  `Volume Down press -> cam toggle` (16:40:24, 16:41:50, 16:41:53), distinct
  from `Volume Up press -> toggle` (audio recorder) - both keys work.
- Saved clips validated with ffmpeg:
  - `<sid>_Pixel-8-Pro_cam.mp4`: Video H.264 1080x1080 ~15 fps + Audio AAC-LC
    44.1 kHz mono. Multiple runs (13-27 MB) all valid, with audio.
- Live VLC window opened automatically on stream start (UDP mpegts).
- JSON sidecar records sid, device, started_at, path, bytes, bytes_video_in,
  bytes_audio_in, ended_at.

## Bugs found and fixed during testing

1. Post-reboot "app does not exist": the phone was in Before-First-Unlock
   (RUNNING_LOCKED); the app's CE storage is unmounted then. Resolved by a
   manual unlock. (Android security guarantee, not a code bug.)
2. Pipeline deadlock: the async video endpoint wrote to ffmpeg stdin on the
   event loop; when ffmpeg blocked opening the audio FIFO the loop stalled.
   Fixed with per-session queues + dedicated feeder threads (StreamPump on the
   phone, queue+threads on the server).
3. ffmpeg stderr PIPE never drained -> full pipe froze ffmpeg. Now written to
   a per-session log file.
4. AAC "Input buffer exhausted": the MediaCodec CODEC_CONFIG (CSD) buffer was
   wrapped in ADTS. Fixed by skipping BUFFER_FLAG_CODEC_CONFIG.
5. tee muxer "Output file does not contain any stream": the tee outputs need
   explicit `-map 0:v:0 -map 1:a:0`. Added.

## Notes

- Switched from front to BACK camera per request (CameraSelector
  DEFAULT_BACK_CAMERA, camera ID 0). Resolution 1080x1080 from the analysis
  use case.
- Locked-screen use works during normal lock; NOT in Before-First-Unlock right
  after a reboot (no app can run until the first manual unlock).
- Port 8787 (8765/8766 held by another local app).
