# Interaction model + assistant camera to PC: case matrix

Every case is automated in `tests/e2e/run_e2e.py`. Clips are injected as the
user's voice, keys are sent with `sendevent`, and taps and screenshots go
through adb. No manual steps. Pass bars come from the plan. Server-only
logic is also covered offline by `tests/test_assistant_bridge.py`.

| # | Scenario | What it proves | Pass bar |
|---|----------|----------------|----------|
| 1 | `wake_greeting` | "hey jarvis" (the real wake-word detector) opens the assistant and Jarvis greets | Greeting within 2.5 s of the assistant opening |
| 2 | `plain_latency` | 10 ordinary questions | All answered. First sound (filler or reply) median <= 350 ms. Our pipeline adds <= 150 ms over Gemini direct, measured in the same run. Worst turn <= 1.6 s |
| 3 | `filler` | On-phone filler fills the gap | Within 350 ms when the model takes > 300 ms. Never overlaps the real reply by > 100 ms. At most one per turn. None after "yes" / "stop" |
| 4 | `cut_in` | User talks over a long answer | Playback stops <= 150 ms after the user starts, on the phone. Jarvis then answers the new question |
| 5 | `no_self_interrupt` | Real speaker + real mic, 8 s+ answer, nobody talks | Zero cut-ins from Jarvis's own voice |
| 6 | `non_blocking_tool` | "2^300 digits + plot a sine wave" | Jarvis speaks while `run_python` runs. 91 digits. Chart pops up |
| 7 | `camera_to_pc` | "open the camera" while talking | PC `/cam/live` >= 8 fps. Gemini <= 1 fps. Headless viewer screenshot. Exactly one mp4 after "close the camera" |
| 8 | `camera_conflict` | Real Volume-Down press while the assistant camera is on | Refused cleanly. The assistant camera keeps streaming |
| 9 | `tool_timing` | "open the camera" | Confirmed out loud only after the phone reports the camera bound. No stuck "thinking" |
| 10 | `fallback_model` | `JARVIS_LIVE_MODEL` = the 2.5 native-audio model | A basic turn still works |
| 11 | `restart_safety` | Server killed and restarted mid-conversation | The phone reconnects by itself and the next turn works |

## Offline unit cases (`tests/test_assistant_bridge.py`)

- The mic queue is capped at 10 chunks, keeps the newest audio, and is drained on connect.
- The camera pump sends only the newest frame, at most once per second.
- `state` is sent only on change.
- `_receive` stays on one connection across several turns, and returns when the socket ends.
- Tool calls run as tasks: audio keeps flowing during a slow tool. A cancellation stops the task.
- Non-blocking results go back with `WHEN_IDLE`. Blocking phone tools don't.
- Tool declarations: phone tools are BLOCKING, PC tools NON_BLOCKING; the legacy model gets no `behavior` field.
- A search-quota error drops `google_search` and reconnects. A model-quota error stops.
