# Interaction e2e results: `rs`

Run at 2026-10-24 13:05:41 by `tests/e2e/run_e2e.py`. Every turn was spoken by injected TTS clips; nobody touched the phone.

| Scenario | Result | Checks | Key numbers |
|----------|--------|--------|-------------|
| restart_safety | PASS | 3/3 | reconnects=0, after_restart_ms=1041 |

## Details

### restart_safety: PASS
- PASS turn before restart
- PASS phone reconnected by itself
- PASS next turn works after the restart
- evidence: `tests/logs/interaction/evidence/rs/restart_safety/timeline.txt`
