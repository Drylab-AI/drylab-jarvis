# Interaction e2e results: `phase4f`

Run at 2026-10-24 12:52:00 by `tests/e2e/run_e2e.py`. Every turn was spoken by injected TTS clips; nobody touched the phone.

| Scenario | Result | Checks | Key numbers |
|----------|--------|--------|-------------|
| camera_to_pc | PASS | 8/8 | pc_live_fps=8.0, gemini_fps=1.0, mp4_seconds=5.8 |

## Details

### camera_to_pc: PASS
- PASS camera turned on by voice
- PASS server started a PC camera session: assist-20261024T055136Z-8a2af3
- PASS PC live view /cam/live >= 8 fps: 8.0 fps
- PASS Gemini still gets <= 1 fps: 1.00 fps
- PASS PC viewer page rendered (headless screenshot): tests/logs/interaction/evidence/screens/pc_cam_view.png
- PASS camera turned off by voice
- PASS an mp4 of the assistant camera was saved: 5.8 s
- PASS exactly one recording per camera open (no ghost session): 1 new mp4s
- evidence: `tests/logs/interaction/evidence/screens/pc_cam_view.png`
- evidence: `recordings/2026-10-24/assist-20261024T055136Z-8a2af3_assistant_cam.mp4`
- evidence: `tests/logs/interaction/evidence/phase4f/camera_to_pc/timeline.txt`
