# Interaction e2e results: `phase4`

Run at 2026-10-24 12:45:11 by `tests/e2e/run_e2e.py`. Every turn was spoken by injected TTS clips; nobody touched the phone.

| Scenario | Result | Checks | Key numbers |
|----------|--------|--------|-------------|
| camera_to_pc | ERROR | 5/5 | pc_live_fps=8.0, gemini_fps=1.0 |
| camera_conflict | PASS | 3/3 |  |
| tool_timing | PASS | 3/3 | tool_cancels=0 |

## Details

### camera_to_pc: FAIL
- error: `FileNotFoundError: [Errno 2] No such file or directory: 'ffprobe'`
- PASS camera turned on by voice
- PASS server started a PC camera session: assist-20261024T054400Z-050081
- PASS PC live view /cam/live >= 8 fps: 8.0 fps
- PASS Gemini still gets <= 1 fps: 1.00 fps
- PASS camera turned off by voice
- evidence: `tests/logs/interaction/evidence/phase4/camera_to_pc/timeline.txt`

### camera_conflict: PASS
- PASS assistant camera on
- PASS Volume-Down camera was refused cleanly
- PASS assistant camera kept streaming: 40 frames in 5 s
- evidence: `tests/logs/interaction/evidence/phase4/camera_conflict/timeline.txt`

### tool_timing: PASS
- PASS camera bound
- PASS 'camera is open' was said only after the camera was bound: bound=49889662 said=49890208
- PASS no stuck 'thinking' state
- evidence: `tests/logs/interaction/evidence/phase4/tool_timing/timeline.txt`
