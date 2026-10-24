# Interaction e2e results: `phase4b`

Run at 2026-10-24 12:46:08 by `tests/e2e/run_e2e.py`. Every turn was spoken by injected TTS clips; nobody touched the phone.

| Scenario | Result | Checks | Key numbers |
|----------|--------|--------|-------------|
| camera_to_pc | FAIL | 5/6 | pc_live_fps=7.8, gemini_fps=1.0, mp4_seconds=6.5 |

## Details

### camera_to_pc: FAIL
- PASS camera turned on by voice
- PASS server started a PC camera session: assist-20261024T054542Z-de5436
- FAIL PC live view /cam/live >= 8 fps: 7.8 fps
- PASS Gemini still gets <= 1 fps: 1.00 fps
- PASS camera turned off by voice
- PASS an mp4 of the assistant camera was saved: 6.5 s
- evidence: `recordings/2026-10-24/assist-20261024T054542Z-de5436_assistant_cam.mp4`
- evidence: `tests/logs/interaction/evidence/phase4b/camera_to_pc/timeline.txt`
