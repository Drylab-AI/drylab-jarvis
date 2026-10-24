# Interaction e2e results: `baseline`

Run at 2026-10-24 12:10:37 by `tests/e2e/run_e2e.py`. Every turn was spoken by injected TTS clips; nobody touched the phone.

| Scenario | Result | Checks | Key numbers |
|----------|--------|--------|-------------|
| wake_greeting | FAIL | 2/3 | greet_ms=2789 |
| plain_latency | FAIL | 0/3 | model_n=9, model_median_ms=4870, model_p90_ms=5450, model_max_ms=10249, model_min_ms=4401, first_sound_n=9, first_sound_median_ms=4870, first_sound_p90_ms=5450, first_sound_max_ms=10249, first_sound_min_ms=4401 |

## Details

### wake_greeting: FAIL
- PASS wake word fired on 'hey jarvis'
- PASS assistant opened from the wake word
- FAIL greeting audio within 2500 ms of wake: 2789 ms
- evidence: `tests/logs/interaction/evidence/baseline/wake_greeting/timeline.txt`

### plain_latency: FAIL
- FAIL every turn got a spoken reply: 9/10
- FAIL median end-of-speech -> model audio <= 1000 ms: 4870 ms
- FAIL slowest turn <= 1600 ms: 10249 ms
- per_turn_model_ms: `[10249, 4870, 4401, 4431, 5234, 5450, 4615, 4457, 5438, null]`
- per_turn_first_sound_ms: `[10249, 4870, 4401, 4431, 5234, 5450, 4615, 4457, 5438, null]`
- evidence: `tests/logs/interaction/evidence/baseline/plain_latency/timeline.txt`
