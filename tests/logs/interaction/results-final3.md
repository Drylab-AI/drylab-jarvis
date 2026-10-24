# Interaction e2e results: `final3`

Run at 2026-10-24 13:36:31 by `tests/e2e/run_e2e.py`. Every turn was spoken by injected TTS clips; nobody touched the phone.

| Scenario | Result | Checks | Key numbers |
|----------|--------|--------|-------------|
| wake_greeting | FAIL | 2/3 | greet_ms=2516, harness_to_greet_ms=3363 |
| plain_latency | PASS | 4/4 | model_n=10, model_median_ms=1380, model_p90_ms=1460, model_max_ms=1490, model_min_ms=1253, first_sound_n=10, first_sound_median_ms=246, first_sound_p90_ms=251, first_sound_max_ms=291, first_sound_min_ms=169, meets_1000ms_bar=False, gemini_direct_median_ms=1269, pipeline_overhead_ms=111 |
| filler | PASS | 4/4 |  |
| cut_in | PASS | 3/3 | cut_in_ms=91, cut_in_by=local_cut_in |
| no_self_interrupt | PASS | 4/4 | reply_span_ms=8928, self_cut_ins=0 |
| non_blocking_tool | PASS | 3/3 | transcript_tail=re thing, let me work on that. [running: calculate 2^300 and its digits, and plot a sine wave.] two to the power of three hundred has ninety-one digits, and your sine wave plot is ready on the screen. |
| camera_to_pc | PASS | 8/8 | pc_live_fps=10.6, gemini_fps=1.0, mp4_seconds=8.2 |
| camera_conflict | PASS | 3/3 |  |
| tool_timing | PASS | 3/3 | tool_cancels=0 |
| fallback_model | PASS | 2/2 | fallback_model_ms=5095 |
| restart_safety | PASS | 3/3 | reconnects=0, after_restart_ms=1430 |

## Details

### wake_greeting: FAIL
- PASS wake word fired on 'hey jarvis'
- PASS assistant opened from the wake word
- FAIL greeting audio within 2500 ms of wake: 2516 ms
- evidence: `tests/logs/interaction/evidence/final3/wake_greeting/timeline.txt`

### plain_latency: PASS
- PASS every turn got a spoken reply: 10/10
- PASS first sound median <= 350 ms (perceived response): 246 ms
- PASS our pipeline adds <= 150 ms over Gemini direct: 111 ms (direct 1269 ms)
- PASS slowest turn <= max(1600, direct+700) = 1969 ms: 1490 ms
- per_turn_model_ms: `[1413, 1441, 1490, 1326, 1287, 1253, 1348, 1449, 1460, 1263]`
- per_turn_first_sound_ms: `[291, 246, 251, 245, 241, 248, 244, 169, 246, 249]`
- gemini_direct_before_after_ms: `[1349, 1189]`
- evidence: `tests/logs/interaction/evidence/final3/plain_latency/timeline.txt`

### filler: PASS
- PASS filler within 350 ms whenever the model took >300 ms: [248, 251, 243]
- PASS filler cut within 100 ms of real audio: [0, 0, 0]
- PASS at most one filler per turn
- PASS no filler after 'yes' / 'stop': [None, None]
- turns: `[{"clip": "q_moon", "end_ms": 52695367, "filler_ms": 248, "model_ms": 1482, "filler_cut_ms": 0}, {"clip": "q_tall", "end_ms": 52708526, "filler_ms": 251, "model_ms": 1332, "filler_cut_ms": 0}, {"clip": "q_ocean", "end_ms": 52719207, "filler_ms": 243, "model_ms": 1207, "filler_cut_ms": 0}]`
- short_turns: `[{"clip": "yes", "end_ms": 52728207, "filler_ms": null, "model_ms": 1446, "filler_cut_ms": null}, {"clip": "stop", "end_ms": 52733806, "filler_ms": null, "model_ms": 1313, "filler_cut_ms": null}]`
- evidence: `tests/logs/interaction/evidence/final3/filler/timeline.txt`

### cut_in: PASS
- PASS Jarvis was talking when the user cut in
- PASS playback stopped within 150 ms of the user speaking: 91 ms via local_cut_in
- PASS Jarvis then answered the new question (2+2): nd craved the smell of freshly baked bread. with claws not meant for kneading, he gently learned to fold yeast wait, stop. actually, what is 2 + 2? that's four.
- evidence: `tests/logs/interaction/evidence/final3/cut_in/timeline.txt`

### no_self_interrupt: PASS
- PASS question delivered: HTTP 200
- PASS Jarvis started answering out loud
- PASS answer lasted a while (>= 6 s): 8928 ms
- PASS zero self-interruptions from its own voice
- evidence: `tests/logs/interaction/evidence/final3/no_self_interrupt/timeline.txt`

### non_blocking_tool: PASS
- PASS chart popped up on the phone
- PASS answer has the right digit count (91): re thing, let me work on that. [running: calculate 2^300 and its digits, and plot a sine wave.] two to the power of three hundred has ninety-one digits, and your sine wave plot is ready on the screen.
- PASS Jarvis spoke while the tool was running
- evidence: `tests/logs/interaction/evidence/screens/non_blocking_tool.png`
- evidence: `tests/logs/interaction/evidence/final3/non_blocking_tool/timeline.txt`

### camera_to_pc: PASS
- PASS camera turned on by voice
- PASS server started a PC camera session: assist-20261024T063418Z-236aa7
- PASS PC live view /cam/live >= 8 fps: 10.6 fps
- PASS Gemini still gets <= 1 fps: 1.00 fps
- PASS PC viewer page rendered (headless screenshot): tests/logs/interaction/evidence/screens/pc_cam_view.png
- PASS camera turned off by voice
- PASS an mp4 of the assistant camera was saved: 8.2 s
- PASS exactly one recording per camera open (no ghost session): 1 new mp4s
- evidence: `tests/logs/interaction/evidence/screens/pc_cam_view.png`
- evidence: `recordings/2026-10-24/assist-20261024T063418Z-236aa7_assistant_cam.mp4`
- evidence: `tests/logs/interaction/evidence/final3/camera_to_pc/timeline.txt`

### camera_conflict: PASS
- PASS assistant camera on
- PASS Volume-Down camera was refused cleanly
- PASS assistant camera kept streaming: 54 frames in 5 s
- evidence: `tests/logs/interaction/evidence/final3/camera_conflict/timeline.txt`

### tool_timing: PASS
- PASS camera bound
- PASS 'camera is open' was said only after the camera was bound: bound=52917352 said=52918028
- PASS no stuck 'thinking' state
- evidence: `tests/logs/interaction/evidence/final3/tool_timing/timeline.txt`

### fallback_model: PASS
- PASS server is on the fallback model: gemini-2.5-flash-native-audio-preview-12-2025
- PASS a basic turn still works: 5095 ms
- evidence: `tests/logs/interaction/evidence/final3/fallback_model/timeline.txt`

### restart_safety: PASS
- PASS turn before restart
- PASS phone reconnected by itself
- PASS next turn works after the restart
- evidence: `tests/logs/interaction/evidence/final3/restart_safety/timeline.txt`
