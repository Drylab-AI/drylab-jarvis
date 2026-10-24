"""Generate the spoken "user" lines and Jarvis's filler clips with Gemini TTS.

User clips (tests/e2e/clips/<name>.raw, 16 kHz mono s16le) are injected into
the phone's assistant mic by run_e2e.py, so the whole conversation can be
tested without anybody speaking.

Filler clips (android-app/app/src/main/assets/fillers/<name>.pcm, 24 kHz mono
s16le) are shipped in the APK and played by the on-phone interaction layer
the instant the user stops talking.

Both are trimmed of leading/trailing silence: for user clips the end of the
file is then exactly "the user stopped speaking", which is where turn latency
is measured from.

Generated once and cached; re-run with --force to regenerate.

  server/.venv/bin/python tests/e2e/make_clips.py
"""

from __future__ import annotations

import argparse
import io
import os
import re
import subprocess
import sys
import time
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CLIPS = Path(__file__).resolve().parent / "clips"
FILLERS = ROOT / "android-app/app/src/main/assets/fillers"

# Free-tier keys get only ~3 TTS requests/minute PER MODEL, so requests are
# spread across every TTS model the key can reach and a 429 waits the delay
# the API asks for. Same voice names exist on all of them.
TTS_MODELS = [
    m.strip()
    for m in os.environ.get(
        "JARVIS_TTS_MODELS",
        "gemini-3.8-flash-tts,gemini-3.8-flash-lite-tts,"
        "gemini-2.5-flash-preview-tts,gemini-3.1-flash-tts-preview",
    ).split(",")
    if m.strip()
]
# A user voice different from Jarvis, so a transcript never confuses the two.
USER_VOICE = "Puck"
# The voice the Live session speaks with (see assistant.py JARVIS_VOICE).
JARVIS_VOICE = os.environ.get("JARVIS_VOICE", "Charon")

# name -> text. Kept short and natural, like real requests.
#
# The wake clip (clips/hey_jarvis.raw) is NOT generated: it is a copy of
# tools/oww/jarvis.raw, the recording the on-device wake word was verified
# with. A TTS "hey jarvis" came out 0.5 s long and scored 0.0 on the detector.
USER_LINES: dict[str, str] = {
    "q_capital": "What is the capital of Japan?",
    "q_moon": "How far away is the Moon?",
    "q_color": "What color do you get when you mix blue and yellow?",
    "q_legs": "How many legs does a spider have?",
    "q_boil": "At what temperature does water boil?",
    "q_ocean": "What is the biggest ocean on Earth?",
    "q_planet": "Which planet is closest to the Sun?",
    "q_week": "How many days are there in a week?",
    "q_tall": "What is the tallest mountain in the world?",
    "q_hello": "Tell me a fun fact about cats.",
    "q_long": (
        "Please tell me a long story about a dragon who learns to bake bread. "
        "Take your time and give me lots of detail."
    ),
    "q_echo_long": (
        "Please describe, in about twenty seconds, how a cup of coffee is "
        "made, from the bean to the cup."
    ),
    "interrupt_stop": "Wait, stop. Actually, what is two plus two?",
    "yes": "Yes.",
    "stop": "Stop.",
    "open_camera": "Open the camera, please.",
    "close_camera": "Okay, close the camera.",
    "what_see": "What do you see right now?",
    "tool_math": (
        "Work out two to the power of three hundred and tell me how many "
        "digits it has, and also plot a sine wave on my screen."
    ),
    "tool_disk": "How much free disk space do I have on my computer?",
}

# Jarvis's own fillers / backchannels, said in its voice.
# Every filler is checked by transcribing it back (verify_fillers); the TTS
# models sometimes add or drop words on such short inputs, so a clip that
# doesn't transcribe to its text is regenerated.
FILLER_LINES: dict[str, str] = {
    "f_hmm": "Hmm.",
    "f_uhm": "Um.",
    "f_okay": "Okay.",
    "f_right": "Right.",
    "f_letmesee": "Let me see.",
    "f_sure": "Sure.",
    "f_ah": "Ah.",
    "f_sowell": "So, well.",
}


def _norm(s: str) -> str:
    return re.sub(r"[^a-z ]", "", s.lower()).replace("umm", "um").replace("hmmm", "hmm").strip()


def transcribe(client, path: Path, rate: int) -> str:
    from google.genai import types

    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(path.read_bytes())
    r = client.models.generate_content(
        model="gemini-3.1-flash-lite",
        contents=[types.Part.from_bytes(data=buf.getvalue(), mime_type="audio/wav"),
                  "Transcribe verbatim, including fillers like um or hmm. Output only the words."],
    )
    return (r.text or "").strip()


_model_turn = 0


def _retry_delay(exc: Exception) -> float:
    m = re.search(r"retry in ([0-9.]+)s", str(exc))
    return float(m.group(1)) + 1 if m else 20.0


def tts(client, text: str, voice: str) -> bytes:
    """Return WAV bytes for text, rotating models and honouring 429 delays.

    Only the text itself is sent: a style instruction in the prompt gets read
    out loud by these models and ends up inside the clip.
    """
    global _model_turn
    from google.genai import types

    cfg = types.GenerateContentConfig(
        response_modalities=["AUDIO"],
        speech_config=types.SpeechConfig(
            voice_config=types.VoiceConfig(
                prebuilt_voice_config=types.PrebuiltVoiceConfig(voice_name=voice)
            )
        ),
    )
    last: Exception | None = None
    for attempt in range(len(TTS_MODELS) * 4):
        model = TTS_MODELS[_model_turn % len(TTS_MODELS)]
        _model_turn += 1
        try:
            r = client.models.generate_content(model=model, contents=text, config=cfg)
            part = r.candidates[0].content.parts[0].inline_data
            data = part.data
            if not (part.mime_type or "").endswith("wav"):
                # Some TTS models return bare PCM (24 kHz s16le); wrap it.
                buf = io.BytesIO()
                with wave.open(buf, "wb") as w:
                    w.setnchannels(1)
                    w.setsampwidth(2)
                    w.setframerate(24000)
                    w.writeframes(data)
                data = buf.getvalue()
            return data
        except Exception as exc:  # noqa: BLE001
            last = exc
            if "429" in str(exc) or "RESOURCE_EXHAUSTED" in str(exc):
                # Another model may still have quota; only wait once every
                # model has been tried in this round.
                if (attempt + 1) % len(TTS_MODELS) == 0:
                    time.sleep(_retry_delay(exc))
            else:
                time.sleep(2)
    raise RuntimeError(f"TTS failed for {text!r}: {last}")


def live_say(text: str) -> bytes:
    """Fallback voice source: have the Live model itself say the text.

    Used when the TTS daily quota (10/day/model on free keys) runs out. Live
    has its own quota, and the result is in the exact voice Jarvis answers
    with, so a filler and the reply that follows sound like one speaker.
    Returns 24 kHz WAV bytes.
    """
    import asyncio

    from google import genai
    from google.genai import types

    client = genai.Client(api_key=os.environ["GEMINI_API_KEY"])
    model = os.environ.get("JARVIS_LIVE_MODEL", "gemini-3.8-live")

    async def go() -> bytes:
        cfg = types.LiveConnectConfig(
            response_modalities=["AUDIO"],
            system_instruction=types.Content(parts=[types.Part(text=(
                "You are a voice actor. Say exactly the words you are given, "
                "softly and casually, nothing before or after."))]),
        )
        pcm = bytearray()
        async with client.aio.live.connect(model=model, config=cfg) as s:
            await s.send_realtime_input(text=f"Say exactly: {text}")
            async for r in s.receive():
                if r.data:
                    pcm += r.data
                sc = getattr(r, "server_content", None)
                if sc is not None and getattr(sc, "turn_complete", False):
                    break
        buf = io.BytesIO()
        with wave.open(buf, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(24000)
            w.writeframes(bytes(pcm))
        return buf.getvalue()

    return asyncio.run(go())


def to_raw(wav: bytes, rate: int, out: Path) -> float:
    """Resample WAV to mono s16le at rate, trimming silence at both ends.

    Returns the duration in seconds.
    """
    # 1. Trim leading silence and shorten every internal pause to <= 0.25 s.
    #    TTS can pause 0.7 s mid-sentence; with the assistant's end-of-speech
    #    detection at ~450 ms that would end the "user's" turn half way.
    # 2. Reverse, trim (= trailing silence), reverse back, so the file ends
    #    exactly when speech ends.
    trim = (
        "silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.02:"
        "stop_periods=-1:stop_threshold=-45dB:stop_duration=0.25,"
        "areverse,"
        "silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.02,"
        "areverse"
    )
    proc = subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", "pipe:0",
         "-af", trim, "-ac", "1", "-ar", str(rate), "-f", "s16le", "pipe:1"],
        input=wav, capture_output=True, check=True,
    )
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(proc.stdout)
    return len(proc.stdout) / 2 / rate


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--live", action="store_true",
                    help="voice clips with the Live model instead of TTS")
    args = ap.parse_args()

    key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not key:
        print("GEMINI_API_KEY not set (source ~/.config/jarvis/env)", file=sys.stderr)
        return 2
    from google import genai

    client = genai.Client(api_key=key)

    jobs = [(n, t, CLIPS / f"{n}.raw", 16000, USER_VOICE) for n, t in USER_LINES.items()]
    jobs += [(n, t, FILLERS / f"{n}.pcm", 24000, JARVIS_VOICE) for n, t in FILLER_LINES.items()]

    for name, text, out, rate, voice in jobs:
        if out.exists() and not args.force:
            continue
        for attempt in range(5):
            try:
                wav = tts(client, text, voice) if not args.live else live_say(text)
            except RuntimeError as exc:
                if "per day" not in str(exc) and "PerDay" not in str(exc):
                    raise
                print(f"  TTS daily quota used up; using the Live voice for {name}", flush=True)
                args.live = True
                wav = live_say(text)
            dur = to_raw(wav, rate, out)
            heard = transcribe(client, out, rate)
            if _norm(heard) == _norm(text):
                break
            print(f"  retry {name}: heard {heard!r}", flush=True)
        else:
            print(f"  WARNING {name} never transcribed cleanly", flush=True)
        print(f"{out.relative_to(ROOT)}  {dur:.2f}s  {text!r}  heard={heard!r}", flush=True)
    print("clips ready")
    return 0


if __name__ == "__main__":
    sys.exit(main())
