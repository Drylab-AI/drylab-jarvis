# Interaction e2e results: `phase2c`

Run at 2026-10-24 12:38:29 by `tests/e2e/run_e2e.py`. Every turn was spoken by injected TTS clips; nobody touched the phone.

| Scenario | Result | Checks | Key numbers |
|----------|--------|--------|-------------|
| wake_greeting | PASS | 3/3 | greet_ms=2243, harness_to_greet_ms=3085 |
| plain_latency | FAIL | 2/3 | model_n=10, model_median_ms=1105, model_p90_ms=1183, model_max_ms=1191, model_min_ms=1033, first_sound_n=10, first_sound_median_ms=246, first_sound_p90_ms=249, first_sound_max_ms=249, first_sound_min_ms=205 |
| filler | PASS | 4/4 |  |

## Details

### wake_greeting: PASS
- PASS wake word fired on 'hey jarvis'
- PASS assistant opened from the wake word
- PASS greeting audio within 2500 ms of wake: 2243 ms
- evidence: `tests/logs/interaction/evidence/phase2c/wake_greeting/timeline.txt`

### plain_latency: FAIL
- PASS every turn got a spoken reply: 10/10
- FAIL median end-of-speech -> model audio <= 1000 ms: 1105 ms
- PASS slowest turn <= 1600 ms: 1191 ms
- per_turn_model_ms: `[1069, 1183, 1191, 1095, 1117, 1093, 1132, 1116, 1072, 1033]`
- per_turn_first_sound_ms: `[247, 249, 245, 209, 249, 249, 246, 246, 244, 205]`
- evidence: `tests/logs/interaction/evidence/phase2c/plain_latency/timeline.txt`

### filler: PASS
- PASS filler within 350 ms whenever the model took >300 ms: [211, 243, 244]
- PASS filler cut within 100 ms of real audio: [0, 0, 0]
- PASS at most one filler per turn
- PASS no filler after 'yes' / 'stop': [None, None]
- turns: `[{"clip": "q_moon", "end_ms": 49457013, "filler_ms": 211, "model_ms": 1140, "filler_cut_ms": 0}, {"clip": "q_tall", "end_ms": 49467133, "filler_ms": 243, "model_ms": 1906, "filler_cut_ms": 0}, {"clip": "q_ocean", "end_ms": 49476852, "filler_ms": 244, "model_ms": 948, "filler_cut_ms": 0}]`
- short_turns: `[{"clip": "yes", "end_ms": 49484173, "filler_ms": null, "model_ms": 2302, "filler_cut_ms": null}, {"clip": "stop", "end_ms": 49490532, "filler_ms": null, "model_ms": 1344, "filler_cut_ms": null}]`
- evidence: `tests/logs/interaction/evidence/phase2c/filler/timeline.txt`
