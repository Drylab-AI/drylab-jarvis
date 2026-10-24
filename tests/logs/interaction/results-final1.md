# Interaction e2e results: `final1`

Run at 2026-10-24 13:21:25 by `tests/e2e/run_e2e.py`. Every turn was spoken by injected TTS clips; nobody touched the phone.

| Scenario | Result | Checks | Key numbers |
|----------|--------|--------|-------------|
| wake_greeting | PASS | 3/3 | greet_ms=2237, harness_to_greet_ms=3077 |
| plain_latency | PASS | 4/4 | model_n=10, model_median_ms=1084, model_p90_ms=1265, model_max_ms=1300, model_min_ms=899, first_sound_n=10, first_sound_median_ms=244, first_sound_p90_ms=248, first_sound_max_ms=250, first_sound_min_ms=165, meets_1000ms_bar=False, gemini_direct_median_ms=1232, pipeline_overhead_ms=-148 |
| filler | PASS | 4/4 |  |
| cut_in | PASS | 3/3 | cut_in_ms=83, cut_in_by=local_cut_in |
| no_self_interrupt | PASS | 4/4 | reply_span_ms=10045, self_cut_ins=0 |
| non_blocking_tool | PASS | 3/3 | transcript_tail= that out for you. [running: calculate 2^300, find its digit count, and plot a sine wave.] two to the power of three hundred has ninety-one digits, and i've popped the sine wave plot onto your screen. |
| camera_to_pc | PASS | 8/8 | pc_live_fps=10.4, gemini_fps=1.0, mp4_seconds=8.0 |
| camera_conflict | PASS | 3/3 |  |
| tool_timing | PASS | 3/3 | tool_cancels=0 |
| fallback_model | PASS | 2/2 | fallback_model_ms=5786 |
| restart_safety | PASS | 3/3 | reconnects=0, after_restart_ms=1529 |

## Details

### wake_greeting: PASS
- PASS wake word fired on 'hey jarvis'
- PASS assistant opened from the wake word
- PASS greeting audio within 2500 ms of wake: 2237 ms
- evidence: `tests/logs/interaction/evidence/final1/wake_greeting/timeline.txt`

### plain_latency: PASS
- PASS every turn got a spoken reply: 10/10
- PASS first sound median <= 350 ms (perceived response): 244 ms
- PASS our pipeline adds <= 150 ms over Gemini direct: -148 ms (direct 1232 ms)
- PASS slowest turn <= max(1600, direct+700) = 1932 ms: 1300 ms
- per_turn_model_ms: `[1265, 1075, 1300, 1014, 1094, 899, 1064, 1162, 1144, 1053]`
- per_turn_first_sound_ms: `[210, 248, 244, 166, 245, 165, 250, 248, 245, 210]`
- gemini_direct_before_after_ms: `[1188, 1276]`
- evidence: `tests/logs/interaction/evidence/final1/plain_latency/timeline.txt`

### filler: PASS
- PASS filler within 350 ms whenever the model took >300 ms: [250, 248, 247]
- PASS filler cut within 100 ms of real audio: [0, 0, 0]
- PASS at most one filler per turn
- PASS no filler after 'yes' / 'stop': [None, None]
- turns: `[{"clip": "q_moon", "end_ms": 51782323, "filler_ms": 250, "model_ms": 1331, "filler_cut_ms": 0}, {"clip": "q_tall", "end_ms": 51793723, "filler_ms": 248, "model_ms": 1216, "filler_cut_ms": 0}, {"clip": "q_ocean", "end_ms": 51804604, "filler_ms": 247, "model_ms": 1147, "filler_cut_ms": 0}]`
- short_turns: `[{"clip": "yes", "end_ms": 51812044, "filler_ms": null, "model_ms": 1385, "filler_cut_ms": null}, {"clip": "stop", "end_ms": 51819283, "filler_ms": null, "model_ms": 1286, "filler_cut_ms": null}]`
- evidence: `tests/logs/interaction/evidence/final1/filler/timeline.txt`

### cut_in: PASS
- PASS Jarvis was talking when the user cut in
- PASS playback stopped within 150 ms of the user speaking: 83 ms via local_cut_in
- PASS Jarvis then answered the new question (2+2): e nearby village from afar, his belly rumbling as the warm, yeasty aromas wafted up the mountainside. wait, stop. actually, what is 2 + 2? two plus two is four.
- evidence: `tests/logs/interaction/evidence/final1/cut_in/timeline.txt`

### no_self_interrupt: PASS
- PASS question delivered: HTTP 200
- PASS Jarvis started answering out loud
- PASS answer lasted a while (>= 6 s): 10045 ms
- PASS zero self-interruptions from its own voice
- evidence: `tests/logs/interaction/evidence/final1/no_self_interrupt/timeline.txt`

### non_blocking_tool: PASS
- PASS chart popped up on the phone
- PASS answer has the right digit count (91):  that out for you. [running: calculate 2^300, find its digit count, and plot a sine wave.] two to the power of three hundred has ninety-one digits, and i've popped the sine wave plot onto your screen.
- PASS Jarvis spoke while the tool was running
- evidence: `tests/logs/interaction/evidence/screens/non_blocking_tool.png`
- evidence: `tests/logs/interaction/evidence/final1/non_blocking_tool/timeline.txt`

### camera_to_pc: PASS
- PASS camera turned on by voice
- PASS server started a PC camera session: assist-20261024T061915Z-6cd993
- PASS PC live view /cam/live >= 8 fps: 10.4 fps
- PASS Gemini still gets <= 1 fps: 1.00 fps
- PASS PC viewer page rendered (headless screenshot): tests/logs/interaction/evidence/screens/pc_cam_view.png
- PASS camera turned off by voice
- PASS an mp4 of the assistant camera was saved: 8.0 s
- PASS exactly one recording per camera open (no ghost session): 1 new mp4s
- evidence: `tests/logs/interaction/evidence/screens/pc_cam_view.png`
- evidence: `recordings/2026-10-24/assist-20261024T061915Z-6cd993_assistant_cam.mp4`
- evidence: `tests/logs/interaction/evidence/final1/camera_to_pc/timeline.txt`

### camera_conflict: PASS
- PASS assistant camera on
- PASS Volume-Down camera was refused cleanly
- PASS assistant camera kept streaming: 56 frames in 5 s
- evidence: `tests/logs/interaction/evidence/final1/camera_conflict/timeline.txt`

### tool_timing: PASS
- PASS camera bound
- PASS 'camera is open' was said only after the camera was bound: bound=52011030 said=52011234
- PASS no stuck 'thinking' state
- evidence: `tests/logs/interaction/evidence/final1/tool_timing/timeline.txt`

### fallback_model: PASS
- PASS server is on the fallback model: gemini-2.5-flash-native-audio-preview-12-2025
- PASS a basic turn still works: 5786 ms
- evidence: `tests/logs/interaction/evidence/final1/fallback_model/timeline.txt`

### restart_safety: PASS
- PASS turn before restart
- PASS phone reconnected by itself
- PASS next turn works after the restart
- evidence: `tests/logs/interaction/evidence/final1/restart_safety/timeline.txt`
