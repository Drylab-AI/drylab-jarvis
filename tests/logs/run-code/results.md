# `run_python` + web search — results

Server: `server/.venv/bin/python -m uvicorn app:app --port 8787`
Phone: Pixel 8 Pro `37061FDJG006EY`, live Gemini Live session.
Driven with `POST /assistant/say` (no manual speaking).

## Web search — already present, verified

`google_search` grounding was already configured (`assistant.py:229`); no code
was needed. Confirmed it returns live data rather than training memory:

| Asked | Jarvis answered | Independent check (open-meteo) | Verdict |
|-------|-----------------|-------------------------------|---------|
| Current weather in Ho Chi Minh City | "mostly cloudy, 81 °F (27 °C)" | 25 °C, 94 % cloud cover | PASS — matches conditions |

## `run_python` — unit tests

`server/.venv/bin/python tests/test_run_python.py` → **14/14 pass**
(must use the server venv; system python3 lacks matplotlib.)

Covers: big arithmetic, machine data, figure auto-capture, exceptions,
timeout, timeout killing child processes, output truncation, syntax errors,
the disable switch, no-bogus-image, empty args, unicode, indented snippets,
and the printed-nothing nudge.

## `run_python` — live on-device

| # | Asked | Jarvis did | Ground truth | Verdict |
|---|-------|-----------|--------------|---------|
| 1 | "2 to the power of 300, exact" | `[running: Calculate 2^300...]` → `203,703,597,633,448,...,397,376` | `2**300`, 91 digits, same value | PASS |
| 2 | "How much free disk space?" | `[running: Check free disk space]` → "138.28 GB free" | `df -h /` → 139G | PASS |
| 3 | "Plot a sine wave, tell me its peak" | `[running: Generate and plot a sine wave...]` → plot on screen, "peak ≈ 1.0" | sin peak = 1.0 | PASS |

Screenshots: `proof-sine-from-code.png`, `proof-damped-sine.png`.

## Bugs found and fixed during testing

1. **Model gave up on a missing package.** It reached for `psutil` (not
   installed) and told the user "I can't access your PC's system metrics" —
   a false capability denial. Fixed two ways: installed `psutil`, and made
   `ModuleNotFoundError` return an explicit instruction to retry with the
   standard library instead of apologising.
2. **Spoken 91-digit number.** Case 1 read the whole integer aloud and the
   streaming transcript garbled it. Added a system-prompt rule: summarise
   long numbers/lists rather than reading them out.

## Known flakiness (not a code bug)

Occasionally the Live model emits a `thought` part and replies "working on it"
without calling the tool; the server logs
`non-data parts in the response: ['text', 'thought']`. A restated request or a
fresh session recovers. This is upstream Live-API behaviour on the
native-audio preview model, not a fault in the tool.

Also note: a `uv run`-launched server dies when the launching shell exits —
start it with `setsid nohup .venv/bin/python -m uvicorn ...` to persist.
