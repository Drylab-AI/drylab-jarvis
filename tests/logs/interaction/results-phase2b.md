# Interaction e2e results: `phase2b`

Run at 2026-10-24 12:34:07 by `tests/e2e/run_e2e.py`. Every turn was spoken by injected TTS clips; nobody touched the phone.

| Scenario | Result | Checks | Key numbers |
|----------|--------|--------|-------------|
| wake_greeting | FAIL | 2/3 | greet_ms=5226 |
| plain_latency | FAIL | 1/3 | model_n=10, model_median_ms=1359, model_p90_ms=1578, model_max_ms=1637, model_min_ms=1203, first_sound_n=10, first_sound_median_ms=404, first_sound_p90_ms=409, first_sound_max_ms=446, first_sound_min_ms=328 |
| filler | FAIL | 3/4 |  |
| cut_in | PASS | 3/3 | cut_in_ms=84, cut_in_by=local_cut_in |
| no_self_interrupt | PASS | 4/4 | reply_span_ms=12851, self_cut_ins=0 |

## Details

### wake_greeting: FAIL
- PASS wake word fired on 'hey jarvis'
- PASS assistant opened from the wake word
- FAIL greeting audio within 2500 ms of wake: 5226 ms
- evidence: `tests/logs/interaction/evidence/phase2b/wake_greeting/timeline.txt`

### plain_latency: FAIL
- PASS every turn got a spoken reply: 10/10
- FAIL median end-of-speech -> model audio <= 1000 ms: 1359 ms
- FAIL slowest turn <= 1600 ms: 1637 ms
- per_turn_model_ms: `[1371, 1637, 1320, 1455, 1578, 1203, 1280, 1348, 1314, 1382]`
- per_turn_first_sound_ms: `[446, 365, 404, 406, 409, 404, 406, 365, 402, 328]`
- evidence: `tests/logs/interaction/evidence/phase2b/plain_latency/timeline.txt`

### filler: FAIL
- FAIL filler within 350 ms whenever the model took >300 ms: [410, 405, 287]
- PASS filler cut within 100 ms of real audio: [0, 0, 0]
- PASS at most one filler per turn
- PASS no filler after 'yes' / 'stop': [None, None]
- turns: `[{"clip": "q_moon", "end_ms": 49127997, "filler_ms": 410, "model_ms": 1046, "filler_cut_ms": 0}, {"clip": "q_tall", "end_ms": 49140796, "filler_ms": 405, "model_ms": 1149, "filler_cut_ms": 0}, {"clip": "q_ocean", "end_ms": 49152797, "filler_ms": 287, "model_ms": 952, "filler_cut_ms": 0}]`
- short_turns: `[{"clip": "yes", "end_ms": 49162476, "filler_ms": null, "model_ms": 1059, "filler_cut_ms": null}, {"clip": "stop", "end_ms": 49170196, "filler_ms": null, "model_ms": 1155, "filler_cut_ms": null}]`
- evidence: `tests/logs/interaction/evidence/phase2b/filler/timeline.txt`

### cut_in: PASS
- PASS Jarvis was talking when the user cut in
- PASS playback stopped within 150 ms of the user speaking: 84 ms via local_cut_in
- PASS Jarvis then answered the new question (2+2): sion for pastry. while other dragons hoarded gold, ignis collected rare grains and dreamed of the perfect loaf wait, stop. actually, what is 2 + 2? that's four.
- evidence: `tests/logs/interaction/evidence/phase2b/cut_in/timeline.txt`

### no_self_interrupt: PASS
- PASS question delivered: HTTP 200
- PASS Jarvis started answering out loud
- PASS answer lasted a while (>= 8 s): 12851 ms
- PASS zero self-interruptions from its own voice
- evidence: `tests/logs/interaction/evidence/phase2b/no_self_interrupt/timeline.txt`
