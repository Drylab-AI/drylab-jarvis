# Interaction e2e results: `final2`

Run at 2026-10-24 13:28:37 by `tests/e2e/run_e2e.py`. Every turn was spoken by injected TTS clips; nobody touched the phone.

| Scenario | Result | Checks | Key numbers |
|----------|--------|--------|-------------|
| wake_greeting | PASS | 3/3 | greet_ms=2108, harness_to_greet_ms=2991 |
| plain_latency | FAIL | 3/4 | model_n=10, model_median_ms=1099, model_p90_ms=1246, model_max_ms=2125, model_min_ms=958, first_sound_n=10, first_sound_median_ms=247, first_sound_p90_ms=252, first_sound_max_ms=283, first_sound_min_ms=209, meets_1000ms_bar=False, gemini_direct_median_ms=1164, pipeline_overhead_ms=-65 |
| filler | PASS | 4/4 |  |
| cut_in | PASS | 3/3 | cut_in_ms=124, cut_in_by=local_cut_in |
| no_self_interrupt | PASS | 4/4 | reply_span_ms=9714, self_cut_ins=0 |
| non_blocking_tool | PASS | 3/3 | transcript_tail=ork on that. [running: calculate 2^300, find its number of digits, and plot a sine wave.] two to the power of three hundred has ninety-one digits, and i've popped that sine wave plot onto your screen. |
| camera_to_pc | PASS | 8/8 | pc_live_fps=10.4, gemini_fps=1.0, mp4_seconds=7.8 |
| camera_conflict | PASS | 3/3 |  |
| tool_timing | PASS | 3/3 | tool_cancels=0 |
| fallback_model | PASS | 2/2 | fallback_model_ms=6867 |
| restart_safety | PASS | 3/3 | reconnects=0, after_restart_ms=1484 |

## Details

### wake_greeting: PASS
- PASS wake word fired on 'hey jarvis'
- PASS assistant opened from the wake word
- PASS greeting audio within 2500 ms of wake: 2108 ms
- evidence: `tests/logs/interaction/evidence/final2/wake_greeting/timeline.txt`

### plain_latency: FAIL
- PASS every turn got a spoken reply: 10/10
- PASS first sound median <= 350 ms (perceived response): 247 ms
- PASS our pipeline adds <= 150 ms over Gemini direct: -65 ms (direct 1164 ms)
- FAIL slowest turn <= max(1600, direct+700) = 1864 ms: 2125 ms
- per_turn_model_ms: `[1085, 2125, 1246, 1140, 1056, 1027, 1161, 1113, 1078, 958]`
- per_turn_first_sound_ms: `[252, 248, 244, 209, 283, 247, 245, 247, 252, 250]`
- gemini_direct_before_after_ms: `[1261, 1067]`
- evidence: `tests/logs/interaction/evidence/final2/plain_latency/timeline.txt`

### filler: PASS
- PASS filler within 350 ms whenever the model took >300 ms: [210, 247, 246]
- PASS filler cut within 100 ms of real audio: [0, 0, 0]
- PASS at most one filler per turn
- PASS no filler after 'yes' / 'stop': [None, None]
- turns: `[{"clip": "q_moon", "end_ms": 52231274, "filler_ms": 210, "model_ms": 1204, "filler_cut_ms": 0}, {"clip": "q_tall", "end_ms": 52242473, "filler_ms": 247, "model_ms": 793, "filler_cut_ms": 0}, {"clip": "q_ocean", "end_ms": 52252156, "filler_ms": 246, "model_ms": 773, "filler_cut_ms": 0}]`
- short_turns: `[{"clip": "yes", "end_ms": 52259873, "filler_ms": null, "model_ms": 1182, "filler_cut_ms": null}, {"clip": "stop", "end_ms": 52265514, "filler_ms": null, "model_ms": 887, "filler_cut_ms": null}]`
- evidence: `tests/logs/interaction/evidence/final2/filler/timeline.txt`

### cut_in: PASS
- PASS Jarvis was talking when the user cut in
- PASS playback stopped within 150 ms of the user speaking: 124 ms via local_cut_in
- PASS Jarvis then answered the new question (2+2): gnis loved the aroma of fresh bread drifting up from the village below. his fiery wait, stop. actually, what is 2 + 2? that's an easy one: two plus two is four.
- evidence: `tests/logs/interaction/evidence/final2/cut_in/timeline.txt`

### no_self_interrupt: PASS
- PASS question delivered: HTTP 200
- PASS Jarvis started answering out loud
- PASS answer lasted a while (>= 6 s): 9714 ms
- PASS zero self-interruptions from its own voice
- evidence: `tests/logs/interaction/evidence/final2/no_self_interrupt/timeline.txt`

### non_blocking_tool: PASS
- PASS chart popped up on the phone
- PASS answer has the right digit count (91): ork on that. [running: calculate 2^300, find its number of digits, and plot a sine wave.] two to the power of three hundred has ninety-one digits, and i've popped that sine wave plot onto your screen.
- PASS Jarvis spoke while the tool was running
- evidence: `tests/logs/interaction/evidence/screens/non_blocking_tool.png`
- evidence: `tests/logs/interaction/evidence/final2/non_blocking_tool/timeline.txt`

### camera_to_pc: PASS
- PASS camera turned on by voice
- PASS server started a PC camera session: assist-20261024T062633Z-94a359
- PASS PC live view /cam/live >= 8 fps: 10.4 fps
- PASS Gemini still gets <= 1 fps: 1.00 fps
- PASS PC viewer page rendered (headless screenshot): tests/logs/interaction/evidence/screens/pc_cam_view.png
- PASS camera turned off by voice
- PASS an mp4 of the assistant camera was saved: 7.8 s
- PASS exactly one recording per camera open (no ghost session): 1 new mp4s
- evidence: `tests/logs/interaction/evidence/screens/pc_cam_view.png`
- evidence: `recordings/2026-10-24/assist-20261024T062633Z-94a359_assistant_cam.mp4`
- evidence: `tests/logs/interaction/evidence/final2/camera_to_pc/timeline.txt`

### camera_conflict: PASS
- PASS assistant camera on
- PASS Volume-Down camera was refused cleanly
- PASS assistant camera kept streaming: 56 frames in 5 s
- evidence: `tests/logs/interaction/evidence/final2/camera_conflict/timeline.txt`

### tool_timing: PASS
- PASS camera bound
- PASS 'camera is open' was said only after the camera was bound: bound=52441891 said=52442502
- PASS no stuck 'thinking' state
- evidence: `tests/logs/interaction/evidence/final2/tool_timing/timeline.txt`

### fallback_model: PASS
- PASS server is on the fallback model: gemini-2.5-flash-native-audio-preview-12-2025
- PASS a basic turn still works: 6867 ms
- evidence: `tests/logs/interaction/evidence/final2/fallback_model/timeline.txt`

### restart_safety: PASS
- PASS turn before restart
- PASS phone reconnected by itself
- PASS next turn works after the restart
- evidence: `tests/logs/interaction/evidence/final2/restart_safety/timeline.txt`
