# Interaction e2e results: `phase4e`

Run at 2026-10-24 12:50:38 by `tests/e2e/run_e2e.py`. Every turn was spoken by injected TTS clips; nobody touched the phone.

| Scenario | Result | Checks | Key numbers |
|----------|--------|--------|-------------|
| camera_to_pc | PASS | 8/8 | pc_live_fps=8.0, gemini_fps=1.0, mp4_seconds=5.6 |
| camera_conflict | PASS | 3/3 |  |

## Details

### camera_to_pc: PASS
- PASS camera turned on by voice
- PASS server started a PC camera session: assist-20261024T054952Z-f494c5
- PASS PC live view /cam/live >= 8 fps: 8.0 fps
- PASS Gemini still gets <= 1 fps: 1.00 fps
- PASS PC viewer page rendered (headless screenshot): tests/logs/interaction/evidence/screens/pc_cam_view.png
- PASS camera turned off by voice
- PASS an mp4 of the assistant camera was saved: 5.6 s
- PASS exactly one recording per camera open (no ghost session): 1 new mp4s
- evidence: `tests/logs/interaction/evidence/screens/pc_cam_view.png`
- evidence: `recordings/2026-10-24/assist-20261024T054952Z-f494c5_assistant_cam.mp4`
- evidence: `tests/logs/interaction/evidence/phase4e/camera_to_pc/timeline.txt`

### camera_conflict: PASS
- PASS assistant camera on
- PASS Volume-Down camera was refused cleanly
- PASS assistant camera kept streaming: 40 frames in 5 s
- evidence: `tests/logs/interaction/evidence/phase4e/camera_conflict/timeline.txt`
