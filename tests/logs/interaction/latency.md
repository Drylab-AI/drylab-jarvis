# Jarvis turn latency

Measured by `tests/e2e/run_e2e.py`: the phone plays an injected TTS clip
into the assistant mic. We time from the end of the clip (`mic_clip_end`) to
the first reply audio handed to the speaker (`reply_audio_start`), both on
the phone's `elapsedRealtime` clock. Nobody spoke.

## Baseline (before any change)

Run label `baseline`, model `gemini-2.5-flash-native-audio-preview-12-2025`,
VAD silence 600 ms, PC relay, 10 turns:

| Metric | Value |
|--------|-------|
| Median end of speech -> reply audio | **4870 ms** |
| p90 | 5450 ms |
| Worst | 10249 ms |
| Turns with no reply | 1 of 10 |
| Wake -> greeting audio | 2789 ms |

Per turn (ms): 10249, 4870, 4401, 4431, 5234, 5450, 4615, 4457, 5438, none.

### Where the time goes

`tests/e2e/probe_live.py` streams the same clips straight to Gemini from the
PC (no phone, no bridge), at real-time speed with 450 ms VAD:

| Model | Median, direct | Clips |
|-------|----------------|-------|
| gemini-2.5-flash-native-audio-preview-12-2025 | 3335 ms | 3650, 3233, 2882, 3438 |
| gemini-3.8-live | **1218 ms** | 1113, 1521, 1132, 1305 |

So of the 4.9 s:
- About 3.3 s is the old model on this route. The model upgrade alone cuts
  that to about 1.2 s.
- About 1.5 s is our own pipeline. The main cause is in the server log:
  `live_connected` comes after every `model_turn_complete`. The SDK's
  `session.receive()` ends at each turn complete, so `_receive` returned and
  the bridge reconnected to Gemini (with a resume handle) after every reply.
  The next turn's audio went into a fresh connection.
- One turn got no reply: Gemini closed with
  `1011 Deadline expired before operation could complete` during a reconnect.

Results after each phase are added below.
