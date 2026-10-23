# `run_python` tool — case matrix

Lets Jarvis run real Python on the PC and speak/show the result. Verified by
driving the live session with `POST /assistant/say` and reading the phone.

## Happy path

| # | Case | Spoken request | Expected |
|---|------|----------------|----------|
| 1 | Arithmetic beyond an LLM's head | "What is 2 to the power of 300?" | Exact 91-digit integer, spoken/correct |
| 2 | Real machine data | "How much disk space is free on my PC?" | Figure matching `df -h` |
| 3 | Plot straight from code | "Plot a sine wave" | PNG auto-captured from matplotlib, pops up on phone |
| 4 | Multi-step compute + chart | "Fit a line through these points and chart it" | Computes, then chart on screen |

## Edge cases

| # | Case | Trigger | Expected |
|---|------|---------|----------|
| 5 | Code raises | code with a `ZeroDivisionError` | Traceback tail returned to model, Jarvis explains instead of crashing |
| 6 | Infinite loop | `while True: pass` | Killed at `JARVIS_CODE_TIMEOUT` (20 s), returns timeout string, session survives |
| 7 | Huge stdout | printing 10 MB | Output truncated, WebSocket not flooded |
| 8 | Syntax error | malformed code | Error returned, model can retry |
| 9 | Tool disabled | `JARVIS_ALLOW_CODE=0` | Refuses with a clear message; Jarvis tells the user it is off |
| 10 | No matplotlib figure | code that only prints | No bogus image pushed to phone |
| 11 | Missing/empty code arg | `{}` | "no code provided", no subprocess spawned |
| 12 | Unicode in output | prints emoji/CJK | Survives round-trip, no decode crash |

## Safety notes

- Runs as the user, so it can touch the user's files. Gated by `JARVIS_ALLOW_CODE`
  (default on, since the user explicitly asked for it) and documented in the README.
- Isolated to a fresh temp cwd per call, killed by process group on timeout so
  child processes cannot outlive it.
