"""Measure Gemini Live directly, outside the Jarvis pipeline.

Streams a clip at real-time speed (40 ms chunks) followed by silence, and
times from the end of the clip to the first reply audio. Comparing this with
the on-phone number separates "the model is slow" from "our code is slow".

  server/.venv/bin/python tests/e2e/probe_live.py gemini-3.8-live q_capital q_moon
Env: VAD_MS (silence_duration_ms, default 450), SENS (HIGH|LOW|default).
"""

from __future__ import annotations

import asyncio
import os
import statistics
import sys
import time
from pathlib import Path

from google import genai
from google.genai import types

CLIPS = Path(__file__).resolve().parent / "clips"


async def one(client, model: str, clip: str, cfg) -> float | None:
    pcm = (CLIPS / f"{clip}.raw").read_bytes()
    chunk = 1280  # 40 ms at 16 kHz s16le
    async with client.aio.live.connect(model=model, config=cfg) as s:
        t_end: float | None = None
        first: float | None = None

        async def send() -> None:
            nonlocal t_end
            start = time.monotonic()
            n = 0
            for i in range(0, len(pcm), chunk):
                await s.send_realtime_input(
                    audio=types.Blob(data=pcm[i:i + chunk], mime_type="audio/pcm;rate=16000"))
                n += 1
                await asyncio.sleep(max(0, start + n * 0.04 - time.monotonic()))
            t_end = time.monotonic()
            silence = b"\0" * chunk
            for _ in range(250):  # 10 s of silence, like an open mic
                await s.send_realtime_input(
                    audio=types.Blob(data=silence, mime_type="audio/pcm;rate=16000"))
                n += 1
                await asyncio.sleep(max(0, start + n * 0.04 - time.monotonic()))

        task = asyncio.create_task(send())
        try:
            async def recv() -> None:
                nonlocal first
                while first is None:
                    async for r in s.receive():
                        if r.data and t_end is not None:
                            first = time.monotonic()
                            return
            await asyncio.wait_for(recv(), timeout=20)
        except asyncio.TimeoutError:
            pass
        finally:
            task.cancel()
        return None if first is None or t_end is None else (first - t_end) * 1000


def config(model: str):
    vad = int(os.environ.get("VAD_MS", "450"))
    sens = os.environ.get("SENS", "")
    aad = types.AutomaticActivityDetection(silence_duration_ms=vad)
    if sens:
        aad.end_of_speech_sensitivity = getattr(types.EndSensitivity, f"END_SENSITIVITY_{sens}")
    return types.LiveConnectConfig(
        response_modalities=["AUDIO"],
        system_instruction=types.Content(parts=[types.Part(text=(
            "You are a concise voice assistant. Answer in one short sentence."))]),
        realtime_input_config=types.RealtimeInputConfig(automatic_activity_detection=aad),
    )


async def main() -> None:
    model = sys.argv[1]
    clips = sys.argv[2:] or ["q_capital", "q_moon", "q_legs"]
    client = genai.Client(api_key=os.environ["GEMINI_API_KEY"])
    res = []
    for c in clips:
        ms = await one(client, model, c, config(model))
        res.append(ms)
        print(f"{model} {c}: {'none' if ms is None else f'{ms:.0f} ms'}", flush=True)
    ok = [x for x in res if x is not None]
    if ok:
        print(f"{model} median {statistics.median(ok):.0f} ms  (n={len(ok)})")


if __name__ == "__main__":
    asyncio.run(main())
