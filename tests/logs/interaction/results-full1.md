# Interaction e2e results: `full1`

Run at 2026-10-24 13:03:54 by `tests/e2e/run_e2e.py`. Every turn was spoken by injected TTS clips; nobody touched the phone.

| Scenario | Result | Checks | Key numbers |
|----------|--------|--------|-------------|
| wake_greeting | PASS | 3/3 | greet_ms=2377, harness_to_greet_ms=3203 |
| plain_latency | FAIL | 2/4 | model_n=10, model_median_ms=1348, model_p90_ms=1416, model_max_ms=1814, model_min_ms=1222, first_sound_n=10, first_sound_median_ms=246, first_sound_p90_ms=248, first_sound_max_ms=290, first_sound_min_ms=203, meets_1000ms_bar=False, gemini_direct_median_ms=1187, pipeline_overhead_ms=161 |
| filler | PASS | 4/4 |  |
| cut_in | PASS | 3/3 | cut_in_ms=125, cut_in_by=local_cut_in |
| no_self_interrupt | FAIL | 3/4 | reply_span_ms=7582, self_cut_ins=0 |
| non_blocking_tool | PASS | 3/3 | transcript_tail=let me work on that. [running: calculate 2^300, find its number of digits, and plot a sine wave.] two to the power of three hundred has ninety-one digits, and i've also plotted that sine wave for you. |
| camera_to_pc | PASS | 8/8 | pc_live_fps=10.4, gemini_fps=1.0, mp4_seconds=7.9 |
| camera_conflict | PASS | 3/3 |  |
| tool_timing | PASS | 3/3 | tool_cancels=0 |
| fallback_model | PASS | 2/2 | fallback_model_ms=6016 |
| restart_safety | FAIL | 1/2 |  |

## Details

### wake_greeting: PASS
- PASS wake word fired on 'hey jarvis'
- PASS assistant opened from the wake word
- PASS greeting audio within 2500 ms of wake: 2377 ms
- evidence: `tests/logs/interaction/evidence/full1/wake_greeting/timeline.txt`

### plain_latency: FAIL
- PASS every turn got a spoken reply: 10/10
- PASS first sound median <= 350 ms (perceived response): 246 ms
- FAIL our pipeline adds <= 150 ms over Gemini direct: 161 ms (direct 1187 ms)
- FAIL slowest turn <= 1600 ms: 1814 ms
- per_turn_model_ms: `[1814, 1353, 1416, 1381, 1364, 1247, 1244, 1344, 1303, 1222]`
- per_turn_first_sound_ms: `[209, 248, 247, 247, 244, 248, 290, 245, 246, 203]`
- evidence: `tests/logs/interaction/evidence/full1/plain_latency/timeline.txt`

### filler: PASS
- PASS filler within 350 ms whenever the model took >300 ms: [248, 247, 246]
- PASS filler cut within 100 ms of real audio: [0, 0, 0]
- PASS at most one filler per turn
- PASS no filler after 'yes' / 'stop': [None, None]
- turns: `[{"clip": "q_moon", "end_ms": 50706492, "filler_ms": 248, "model_ms": 1290, "filler_cut_ms": 0}, {"clip": "q_tall", "end_ms": 50717171, "filler_ms": 247, "model_ms": 1195, "filler_cut_ms": 0}, {"clip": "q_ocean", "end_ms": 50726131, "filler_ms": 246, "model_ms": 1128, "filler_cut_ms": 0}]`
- short_turns: `[{"clip": "yes", "end_ms": 50732932, "filler_ms": null, "model_ms": 1357, "filler_cut_ms": null}, {"clip": "stop", "end_ms": 50738732, "filler_ms": null, "model_ms": 1364, "filler_cut_ms": null}]`
- evidence: `tests/logs/interaction/evidence/full1/filler/timeline.txt`

### cut_in: PASS
- PASS Jarvis was talking when the user cut in
- PASS playback stopped within 150 ms of the user speaking: 125 ms via local_cut_in
- PASS Jarvis then answered the new question (2+2): en who loved hoarding gold, ignis was obsessed with the smell of warm, fresh bread. his journey began wait, stop. actually, what is 2 + 2? two plus two is four.
- evidence: `tests/logs/interaction/evidence/full1/cut_in/timeline.txt`

### no_self_interrupt: FAIL
- PASS question delivered: HTTP 200
- PASS Jarvis started answering out loud
- FAIL answer lasted a while (>= 8 s): 7582 ms
- PASS zero self-interruptions from its own voice
- evidence: `tests/logs/interaction/evidence/full1/no_self_interrupt/timeline.txt`

### non_blocking_tool: PASS
- PASS chart popped up on the phone
- PASS answer has the right digit count (91): let me work on that. [running: calculate 2^300, find its number of digits, and plot a sine wave.] two to the power of three hundred has ninety-one digits, and i've also plotted that sine wave for you.
- PASS Jarvis spoke while the tool was running
- evidence: `tests/logs/interaction/evidence/screens/non_blocking_tool.png`
- evidence: `tests/logs/interaction/evidence/full1/non_blocking_tool/timeline.txt`

### camera_to_pc: PASS
- PASS camera turned on by voice
- PASS server started a PC camera session: assist-20261024T060054Z-61d346
- PASS PC live view /cam/live >= 8 fps: 10.4 fps
- PASS Gemini still gets <= 1 fps: 1.00 fps
- PASS PC viewer page rendered (headless screenshot): tests/logs/interaction/evidence/screens/pc_cam_view.png
- PASS camera turned off by voice
- PASS an mp4 of the assistant camera was saved: 7.9 s
- PASS exactly one recording per camera open (no ghost session): 1 new mp4s
- evidence: `tests/logs/interaction/evidence/screens/pc_cam_view.png`
- evidence: `recordings/2026-10-24/assist-20261024T060054Z-61d346_assistant_cam.mp4`
- evidence: `tests/logs/interaction/evidence/full1/camera_to_pc/timeline.txt`

### camera_conflict: PASS
- PASS assistant camera on
- PASS Volume-Down camera was refused cleanly
- PASS assistant camera kept streaming: 53 frames in 5 s
- evidence: `tests/logs/interaction/evidence/full1/camera_conflict/timeline.txt`

### tool_timing: PASS
- PASS camera bound
- PASS 'camera is open' was said only after the camera was bound: bound=50911786 said=50912303
- PASS no stuck 'thinking' state
- evidence: `tests/logs/interaction/evidence/full1/tool_timing/timeline.txt`

### fallback_model: PASS
- PASS server is on the fallback model: gemini-2.5-flash-native-audio-preview-12-2025
- PASS a basic turn still works: 6016 ms
- evidence: `tests/logs/interaction/evidence/full1/fallback_model/timeline.txt`

### restart_safety: FAIL
- PASS turn before restart
- FAIL phone reconnected by itself
- evidence: `tests/logs/interaction/evidence/full1/restart_safety/timeline.txt`
