# Interaction e2e results: `phase4d`

Run at 2026-10-24 12:48:56 by `tests/e2e/run_e2e.py`. Every turn was spoken by injected TTS clips; nobody touched the phone.

| Scenario | Result | Checks | Key numbers |
|----------|--------|--------|-------------|
| camera_to_pc | FAIL | 7/8 | pc_live_fps=7.8, gemini_fps=1.0, mp4_seconds=5.9 |

## Details

### camera_to_pc: FAIL
- PASS camera turned on by voice
- PASS server started a PC camera session: assist-20261024T054833Z-48dea7
- FAIL PC live view /cam/live >= 8 fps: 7.8 fps
- PASS Gemini still gets <= 1 fps: 1.00 fps
- PASS PC viewer page rendered (headless screenshot): tests/logs/interaction/evidence/screens/pc_cam_view.png
- PASS camera turned off by voice
- PASS an mp4 of the assistant camera was saved: 5.9 s
- PASS exactly one recording per camera open (no ghost session): 1 new mp4s
- evidence: `tests/logs/interaction/evidence/screens/pc_cam_view.png`
- evidence: `recordings/2026-10-24/assist-20261024T054833Z-48dea7_assistant_cam.mp4`
- evidence: `tests/logs/interaction/evidence/phase4d/camera_to_pc/timeline.txt`
