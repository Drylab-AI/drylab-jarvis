# Interaction e2e results: `phase4c`

Run at 2026-10-24 12:47:43 by `tests/e2e/run_e2e.py`. Every turn was spoken by injected TTS clips; nobody touched the phone.

| Scenario | Result | Checks | Key numbers |
|----------|--------|--------|-------------|
| camera_to_pc | FAIL | 7/8 | pc_live_fps=8.0, gemini_fps=1.0, mp4_seconds=6.7 |

## Details

### camera_to_pc: FAIL
- PASS camera turned on by voice
- PASS server started a PC camera session: assist-20261024T054716Z-9f2914
- PASS PC live view /cam/live >= 8 fps: 8.0 fps
- PASS Gemini still gets <= 1 fps: 1.00 fps
- FAIL PC viewer page rendered (headless screenshot): tests/logs/interaction/evidence/screens/pc_cam_view.png
- PASS camera turned off by voice
- PASS an mp4 of the assistant camera was saved: 6.7 s
- PASS exactly one recording per camera open (no ghost session): 1 new mp4s
- evidence: `recordings/2026-10-24/assist-20261024T054716Z-9f2914_assistant_cam.mp4`
- evidence: `tests/logs/interaction/evidence/phase4c/camera_to_pc/timeline.txt`
