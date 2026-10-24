# Interaction e2e results: `phase2`

Run at 2026-10-24 12:27:09 by `tests/e2e/run_e2e.py`. Every turn was spoken by injected TTS clips; nobody touched the phone.

| Scenario | Result | Checks | Key numbers |
|----------|--------|--------|-------------|
| wake_greeting | FAIL | 2/3 | greet_ms=5156 |
| plain_latency | FAIL | 1/3 | model_n=10, model_median_ms=1298, model_p90_ms=1450, model_max_ms=8434, model_min_ms=1113, first_sound_n=10, first_sound_median_ms=384, first_sound_p90_ms=407, first_sound_max_ms=410, first_sound_min_ms=324 |
| filler | FAIL | 2/4 |  |
| cut_in | FAIL | 2/3 | cut_in_ms=199, cut_in_by=server_flush |

## Details

### wake_greeting: FAIL
- PASS wake word fired on 'hey jarvis'
- PASS assistant opened from the wake word
- FAIL greeting audio within 2500 ms of wake: 5156 ms
- evidence: `tests/logs/interaction/evidence/phase2/wake_greeting/timeline.txt`

### plain_latency: FAIL
- PASS every turn got a spoken reply: 10/10
- FAIL median end-of-speech -> model audio <= 1000 ms: 1298 ms
- FAIL slowest turn <= 1600 ms: 8434 ms
- per_turn_model_ms: `[8434, 1378, 1450, 1372, 1293, 1119, 1231, 1303, 1179, 1113]`
- per_turn_first_sound_ms: `[401, 327, 407, 367, 405, 326, 402, 324, 410, 363]`
- evidence: `tests/logs/interaction/evidence/phase2/plain_latency/timeline.txt`

### filler: FAIL
- FAIL filler within 350 ms whenever the model took >300 ms: [368, 367, 367]
- FAIL filler cut within 100 ms of real audio: [None, 0, -3]
- PASS at most one filler per turn
- PASS no filler after 'yes' / 'stop': [None, None]
- turns: `[{"clip": "q_moon", "end_ms": 48743519, "filler_ms": 368, "model_ms": 1151, "filler_cut_ms": null}, {"clip": "q_tall", "end_ms": 48754559, "filler_ms": 367, "model_ms": 1214, "filler_cut_ms": 0}, {"clip": "q_ocean", "end_ms": 48763600, "filler_ms": 367, "model_ms": 1071, "filler_cut_ms": -3}]`
- short_turns: `[{"clip": "yes", "end_ms": 48771039, "filler_ms": null, "model_ms": 1219, "filler_cut_ms": null}, {"clip": "stop", "end_ms": 48777359, "filler_ms": null, "model_ms": 1195, "filler_cut_ms": null}]`
- evidence: `tests/logs/interaction/evidence/phase2/filler/timeline.txt`

### cut_in: FAIL
- PASS Jarvis was talking when the user cut in
- FAIL playback stopped within 150 ms of the user speaking: 199 ms via server_flush
- PASS Jarvis then answered the new question (2+2): d gold, ignis loved the aroma of yeast and the warmth of a perfectly baked loaf. wait stop actually what is 2 + 2? that's an easy one, two plus two equals four.
- evidence: `tests/logs/interaction/evidence/phase2/cut_in/timeline.txt`
