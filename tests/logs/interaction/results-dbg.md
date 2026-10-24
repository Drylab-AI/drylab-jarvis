# Interaction e2e results: `dbg`

Run at 2026-10-24 12:29:12 by `tests/e2e/run_e2e.py`. Every turn was spoken by injected TTS clips; nobody touched the phone.

| Scenario | Result | Checks | Key numbers |
|----------|--------|--------|-------------|
| cut_in | FAIL | 2/3 | cut_in_ms=207, cut_in_by=local_cut_in |

## Details

### cut_in: FAIL
- PASS Jarvis was talking when the user cut in
- FAIL playback stopped within 150 ms of the user speaking: 207 ms via local_cut_in
- PASS Jarvis then answered the new question (2+2): ad, he was fascinated by the warm, yeasty smell wafting from the nearby village bakery every morning. wait, stop. actually, what is 2 + 2? two plus two is four.
- evidence: `tests/logs/interaction/evidence/dbg/cut_in/timeline.txt`
