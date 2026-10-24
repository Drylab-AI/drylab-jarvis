"""Gemini Live bridge for the Jarvis voice assistant.

The phone opens ONE WebSocket to /assistant/ws and streams:
  - mic audio   (16 kHz, 16-bit, mono PCM)  up
  - camera JPEG (when enabled by a tool)    up at ~1 fps
and receives:
  - assistant audio (24 kHz, 16-bit, mono PCM) down
  - control JSON (state, transcript, tool actions, images) down

This module runs the Gemini Live session on the PC (which holds the API key),
pumps the phone's media into it, and forwards Gemini's audio / tool calls back.

Wire framing on the phone WebSocket (see app.py):
  - TEXT  frames: JSON control messages (see PhoneLink).
  - BINARY frames: 1 opcode byte + payload:
        0x01  mic PCM  (phone -> server)
        0x02  camera JPEG (phone -> server)
        0x11  assistant PCM (server -> phone)

Model + behaviour are configured via env so they are easy to update:
  GEMINI_API_KEY            required to actually connect
  JARVIS_LIVE_MODEL         default gemini-3.8-live (2.5 native-audio still
                            works; see IS_LEGACY_MODEL for what differs)
"""

from __future__ import annotations

import asyncio
import base64
import json
import os
import time
import traceback
from typing import Any, Awaitable, Callable

# Opcodes for binary frames on the phone WebSocket.
OP_MIC_PCM = 0x01
OP_CAM_JPEG = 0x02
OP_ASSISTANT_PCM = 0x11

# gemini-3.8-live: measured 1.2 s median from end of speech to first audio on
# this route, vs 3.3 s for the 2.5 native-audio preview (probe_live.py). It
# also runs tools without blocking speech (see assistant_tools.declarations).
# The 2.5 model stays usable as a fallback through this env var.
LIVE_MODEL = os.environ.get("JARVIS_LIVE_MODEL", "gemini-3.8-live")
GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY", "").strip()
# 3.8-generation Live models reject enable_affective_dialog / thinking_config
# and have proactive audio always on; only the 2.5 native-audio models take
# those settings.
IS_LEGACY_MODEL = "2.5" in LIVE_MODEL
# End-of-speech silence. Google's guidance: 500-800 ms; 100-200 ms splits a
# sentence into fragments. Tuned on the e2e harness (see latency.md).
VAD_SILENCE_MS = int(os.environ.get("JARVIS_VAD_SILENCE_MS", "450"))
VOICE = os.environ.get("JARVIS_VOICE", "Charon")
MIC_QUEUE_CHUNKS = 10
# Set once google_search grounding hits its quota (see GeminiLiveBridge.run).
_SEARCH_QUOTA_OUT = [False]

SYSTEM_INSTRUCTION = os.environ.get(
    "JARVIS_SYSTEM_PROMPT",
    "You are Jarvis, a quick, warm voice assistant on the user's phone. You "
    "are in a live spoken conversation, so talk like a person, not a document.\n"
    "How to speak:\n"
    "- Answer straight away. Keep replies short: one or two sentences unless "
    "asked for more.\n"
    "- Sound natural. A short filler or a 'hmm, let me think' now and then is "
    "fine, but don't open every reply with one, and never stack them.\n"
    "- If the user is clearly still thinking or mid-sentence, wait; a brief "
    "'mm-hm' is enough.\n"
    "- If the user talks over you, stop and listen.\n"
    "- After a reply keep listening; the conversation stays open.\n"
    "You can see through the phone camera when it is on, and you have tools "
    "to open/close the camera, start/stop audio recording, draw a chart, "
    "run Python on the user's PC and search the web.\n"
    "Rules:\n"
    "- Answer general knowledge from what you know, without tools. Search the "
    "web only for things that change over time (news, prices, weather, scores, "
    "who holds a role now).\n"
    "- Use run_python for exact or large arithmetic, statistics, date maths, "
    "and anything about the user's PC (disk space, files, CPU), and print() "
    "the answer. Simple sums you can do yourself.\n"
    "- Slow tools (run_python, draw_chart, search) run in the background: say "
    "a short 'one moment' and keep the conversation going; the result comes "
    "back to you when it is ready.\n"
    "- When asked to plot or visualise something, use draw_chart for simple "
    "category/value data, and run_python with matplotlib when it needs real "
    "computation first (fitting, maths functions, parsing). Either way the "
    "image pops up on the phone by itself.\n"
    "- Say a brief 'working on it' before a tool call that takes a moment, "
    "then give the result in one or two sentences. Never read code aloud.\n"
    "- You are speaking, not printing: never read out a long number, a list "
    "of more than about five items, or raw data. Summarise it instead (how "
    "many digits, the first few, the total, the trend) and offer to put the "
    "detail on screen.\n"
    "- When the user asks you to look at something or open the camera, call "
    "open_camera, then say out loud that the camera is open.\n"
    "- When the user asks for a chart or graph of what the camera sees, read "
    "the numbers or data points from the camera image and call draw_chart with "
    "chart_type (bar, line, pie or scatter), the values, and labels when you "
    "can read them. Say you are working on it first, then call the tool. If "
    "the image is unclear, say what you can see and ask one short question.\n"
    "- Turn the camera off with close_camera when it is no longer needed.",
)


def _tlog(event: str, **kw: Any) -> None:
    """One timeline line per turn event, for latency reports and tests.

    Uses wall time; per-turn latency itself is measured on the phone, so this
    only needs to be comparable with other server lines.
    """
    extra = " ".join(f"{k}={v}" for k, v in kw.items())
    print(f"JARVIS_T {time.time():.3f} {event} {extra}".rstrip(), flush=True)


class PhoneLink:
    """Abstracts sending control + audio back to the phone WebSocket.

    The WebSocket handler in app.py supplies the two async send callables so
    this module stays framework-light and unit-testable.
    """

    def __init__(
        self,
        send_json: Callable[[dict], Awaitable[None]],
        send_bytes: Callable[[bytes], Awaitable[None]],
    ) -> None:
        self._send_json = send_json
        self._send_bytes = send_bytes

    async def state(self, state: str) -> None:
        """One of: connecting, listening, thinking, speaking, error, closed."""
        await self._send_json({"type": "state", "state": state})

    async def transcript(self, who: str, text: str) -> None:
        await self._send_json({"type": "transcript", "who": who, "text": text})

    async def audio(self, pcm: bytes) -> None:
        await self._send_bytes(bytes([OP_ASSISTANT_PCM]) + pcm)

    async def flush_playback(self) -> None:
        """Barge-in: tell the phone to drop any queued assistant audio."""
        await self._send_json({"type": "flush"})

    async def tool(self, action: str, **kw: Any) -> None:
        """Ask the phone to do something (open_camera, start_recording, ...)."""
        await self._send_json({"type": "tool", "action": action, **kw})

    async def show_image(self, png: bytes, caption: str = "") -> None:
        await self._send_json(
            {
                "type": "show_image",
                "caption": caption,
                "png_b64": base64.b64encode(png).decode("ascii"),
            }
        )

    async def error(self, message: str) -> None:
        await self._send_json({"type": "error", "message": message})


class GeminiLiveBridge:
    """Owns one Gemini Live session for one phone connection."""

    def __init__(self, link: PhoneLink, tools: "ToolRouter") -> None:
        self.link = link
        self.tools = tools
        # ~10 x 40 ms chunks = 400 ms. A bigger backlog only adds latency: if
        # Gemini can't keep up, old audio is worth less than fresh audio.
        self._mic_q: asyncio.Queue[bytes] = asyncio.Queue(maxsize=MIC_QUEUE_CHUNKS)
        self._latest_cam: bytes | None = None
        self._cam_event = asyncio.Event()
        # Background tool calls in flight, by function-call id (for
        # cancellation and so _receive never waits on a tool).
        self._tool_tasks: dict[str, asyncio.Task] = {}
        self._last_state = ""
        # Process-wide: once the search-grounding quota is known to be used
        # up, every new session skips it instead of paying a failed connect
        # first (each failed attempt cost ~1 s plus a timeout on the next).
        self._use_search = (
            os.environ.get("JARVIS_SEARCH", "1") != "0" and not _SEARCH_QUOTA_OUT[0]
        )
        self._session = None
        self._resume_handle: str | None = None
        self._closed = False
        # Counters for /assistant/status (tests and latency reports).
        self.counters: dict[str, int] = {
            "mic_in": 0,
            "cam_in": 0,
            "cam_to_model": 0,
            "audio_out": 0,
            "turns": 0,
            "tool_calls": 0,
            "tool_cancels": 0,
            "sessions": 0,
        }
        self.connected = False
        self.last_error = ""
        self._in_reply = False
        # Live PC camera session for this chat (see app.AssistantCamToPc).
        self.cam_sid = ""

    def stats(self) -> dict:
        return {
            "model": LIVE_MODEL,
            "search": self._use_search,
            "cam_sid": self.cam_sid,
            "connected": self.connected,
            "last_error": self.last_error,
            **self.counters,
        }

    # -- phone -> bridge: the WS handler calls these ------------------------

    def push_mic(self, pcm: bytes) -> None:
        self.counters["mic_in"] += 1
        try:
            self._mic_q.put_nowait(pcm)
        except asyncio.QueueFull:
            # Drop oldest to keep latency bounded.
            try:
                self._mic_q.get_nowait()
            except asyncio.QueueEmpty:
                pass
            try:
                self._mic_q.put_nowait(pcm)
            except asyncio.QueueFull:
                pass

    def push_cam(self, jpeg: bytes) -> None:
        # Latest-frame slot, not a queue: see _pump_cam.
        self.counters["cam_in"] += 1
        self._latest_cam = jpeg
        self._cam_event.set()

    # -- main run loop ------------------------------------------------------

    async def run(self) -> None:
        if not GEMINI_API_KEY:
            await self.link.error(
                "GEMINI_API_KEY is not set on the server; cannot start the "
                "assistant. Set it and restart the server."
            )
            await self.link.state("error")
            return

        # Import here so the rest of the server runs even if google-genai is
        # not installed yet.
        try:
            from google import genai
            from google.genai import types
        except Exception as exc:  # noqa: BLE001
            await self.link.error(f"google-genai not available: {exc}")
            await self.link.state("error")
            return

        client = genai.Client(api_key=GEMINI_API_KEY)
        config = self._build_config(types)

        await self.link.state("connecting")
        first_connect = True
        failures = 0
        # Reconnect loop: survive the ~10 min socket reset via session
        # resumption. _closed is set when the phone disconnects.
        while not self._closed:
            try:
                if self._resume_handle:
                    config.session_resumption = types.SessionResumptionConfig(
                        handle=self._resume_handle
                    )
                async with client.aio.live.connect(
                    model=LIVE_MODEL, config=config
                ) as session:
                    self._drain_stale()
                    self._session = session
                    self.connected = True
                    failures = 0
                    self.counters["sessions"] += 1
                    _tlog("live_connected", model=LIVE_MODEL)
                    await self.link.state("listening")
                    # Greet first so the user hears that Jarvis is ready
                    # instead of an open mic. Only on a fresh session: after a
                    # resumed reconnect the conversation is already going.
                    if first_connect:
                        first_connect = False
                        await self._greet(types, session)
                    # Run the two pumps alongside the receive loop. When
                    # _receive returns (GoAway / stream end) we cancel the
                    # pumps so this connection tears down and the outer loop
                    # reconnects (with the latest resume handle).
                    mic_task = asyncio.create_task(self._pump_mic())
                    cam_task = asyncio.create_task(self._pump_cam())
                    t_open = time.monotonic()
                    try:
                        await self._receive(types)
                    finally:
                        for t in (mic_task, cam_task):
                            t.cancel()
                        await asyncio.gather(
                            mic_task, cam_task, return_exceptions=True
                        )
                    # A socket that closes right after opening (with no
                    # exception) would otherwise reconnect in a tight loop.
                    # GoAway-driven reconnects after a long session stay instant.
                    if not self._closed and time.monotonic() - t_open < 5:
                        failures += 1
                        await asyncio.sleep(min(0.3 * (2 ** (failures - 1)), 8.0))
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001
                if self._closed:
                    break
                self.last_error = str(exc)[:300]
                quota = "quota" in str(exc).lower() or "RESOURCE_EXHAUSTED" in str(exc)
                _tlog("live_error", quota=quota, err=str(exc)[:80].replace(" ", "_"))
                # Only a quota refusal AT CONNECT TIME points at search
                # grounding (measured: the session is refused before any
                # message). A quota/rate error mid-session is the model's own
                # limit and must not switch search off for everyone.
                if quota and self._use_search and self._session is None:
                    # Measured: on a free key the Google Search grounding
                    # quota runs out long before the Live model's, and the
                    # whole connection is refused. Keep the conversation;
                    # drop only web search.
                    self._use_search = False
                    _SEARCH_QUOTA_OUT[0] = True
                    config = self._build_config(types)
                    _tlog("search_disabled", reason="quota")
                    continue
                if quota:
                    # Retrying cannot help and every attempt burns more of
                    # the quota; tell the user plainly and stop.
                    await self.link.error(
                        "The Gemini API quota for this key is used up. "
                        "Try again later or use a key with billing enabled."
                    )
                    await self.link.state("error")
                    return
                traceback.print_exc()
                await self.link.error(f"live session error: {exc}")
                # Reconnect fast the first time (the user is mid-conversation),
                # then back off so a persistent failure doesn't spin.
                failures += 1
                await asyncio.sleep(min(0.3 * (2 ** (failures - 1)), 8.0))
            finally:
                self._session = None
                self.connected = False
        await self.link.state("closed")

    def _build_config(self, types: Any):
        tool_decls = self.tools.declarations(non_blocking_ok=not IS_LEGACY_MODEL)
        tools_cfg: list[dict] = []
        if tool_decls:
            tools_cfg.append({"function_declarations": tool_decls})
        # Search grounding works on both models. (Native code_execution does
        # not, so charts and Python are custom function tools instead.)
        if self._use_search:
            tools_cfg.append({"google_search": {}})

        kw: dict[str, Any] = {}
        if VOICE:
            kw["speech_config"] = types.SpeechConfig(
                voice_config=types.VoiceConfig(
                    prebuilt_voice_config=types.PrebuiltVoiceConfig(voice_name=VOICE)
                )
            )
        return types.LiveConnectConfig(
            response_modalities=["AUDIO"],
            system_instruction=types.Content(
                parts=[types.Part(text=SYSTEM_INSTRUCTION)]
            ),
            tools=tools_cfg,
            input_audio_transcription=types.AudioTranscriptionConfig(),
            output_audio_transcription=types.AudioTranscriptionConfig(),
            context_window_compression=types.ContextWindowCompressionConfig(
                sliding_window=types.SlidingWindow()
            ),
            session_resumption=types.SessionResumptionConfig(),
            realtime_input_config=types.RealtimeInputConfig(
                automatic_activity_detection=types.AutomaticActivityDetection(
                    silence_duration_ms=VAD_SILENCE_MS,
                    prefix_padding_ms=100,
                )
            ),
            **kw,
        )

    async def _greet(self, types: Any, session: Any) -> None:
        """Make Jarvis speak first and ask what the user wants.

        Sent as realtime text input: send_client_content would mix
        turn-structured content into a live audio session, which this
        native-audio model rejects with "1007 ... CONTENT_TYPE_AUDIO is not
        supported for this model configuration" and then drops the socket.
        """
        # Short on purpose: the greeting is the first thing the user waits for,
        # and a longer instruction measurably delays the first audio.
        prompt = os.environ.get(
            "JARVIS_GREETING",
            "Greet the user in under six words, e.g. 'Hey, what's up?'. No tools.",
        )
        try:
            await session.send_realtime_input(text=prompt)
        except Exception as exc:  # noqa: BLE001
            print(f"greeting failed: {exc}")

    async def inject_text(self, text: str) -> bool:
        """Debug/test only: send a user turn as text into the live session.

        Used by POST /assistant/say so the flow can be driven automatically
        without speaking. Returns False when no session is connected yet.
        """
        session = self._session
        if session is None:
            return False
        try:
            # Must be realtime input, not send_client_content: mixing
            # turn-structured content with a live audio stream makes the
            # native-audio model close the socket with
            # "1007 ... CONTENT_TYPE_AUDIO is not supported for this model
            # configuration", which drops the whole conversation.
            await session.send_realtime_input(text=text)
            return True
        except Exception as exc:  # noqa: BLE001
            print(f"inject_text failed: {exc}")
            return False

    def _drain_stale(self) -> None:
        """Drop audio/frames queued while no session was connected.

        Without this, mic audio captured during connect / reconnect (up to
        the whole queue) was sent to Gemini in one burst, so the model heard
        old audio first and answered late.
        """
        dropped = 0
        while True:
            try:
                self._mic_q.get_nowait()
                dropped += 1
            except asyncio.QueueEmpty:
                break
        self._latest_cam = None
        if dropped:
            _tlog("mic_drained", chunks=dropped)

    async def _pump_mic(self) -> None:
        from google.genai import types

        while not self._closed:
            pcm = await self._mic_q.get()
            if self._session is None or not pcm:
                continue
            try:
                await self._session.send_realtime_input(
                    audio=types.Blob(data=pcm, mime_type="audio/pcm;rate=16000")
                )
            except Exception:  # noqa: BLE001 - session may be reconnecting
                await asyncio.sleep(0.05)

    async def _pump_cam(self) -> None:
        """Send the NEWEST camera frame at most once per second.

        The phone sends ~10 fps for the PC preview; Gemini only needs 1 fps,
        and on 3.8 every frame sent is added to the model's context. The old
        queue sent the OLDEST frame each second, so the model could be looking
        at a picture up to 8 s old.
        """
        from google.genai import types

        while not self._closed:
            await self._cam_event.wait()
            self._cam_event.clear()
            jpeg = self._latest_cam
            self._latest_cam = None
            if self._session is None or not jpeg:
                continue
            try:
                await self._session.send_realtime_input(
                    video=types.Blob(data=jpeg, mime_type="image/jpeg")
                )
                self.counters["cam_to_model"] += 1
            except Exception:  # noqa: BLE001
                await asyncio.sleep(0.05)
            await asyncio.sleep(1.0)

    async def _state(self, s: str) -> None:
        """Send a state message only when it changes (it used to go out with
        every 40 ms audio chunk, doubling the downlink message count)."""
        if s != self._last_state:
            self._last_state = s
            await self.link.state(s)

    async def _receive(self, types: Any) -> None:
        """Forward everything Gemini sends, for the whole connection.

        session.receive() is a per-TURN iterator in the SDK: it stops after
        each turn_complete. Iterating it once (as before) made this method
        return after every reply, which tore the connection down and
        reconnected - the next turn's audio then went into a fresh socket.
        That was ~1.5 s of every turn and caused "1011 Deadline expired"
        drops. Loop over turns until the socket really closes.
        """
        assert self._session is not None
        session = self._session
        while not self._closed:
            got_any = False
            async for response in session.receive():
                got_any = True
                if self._closed:
                    return
                if await self._handle(types, session, response):
                    return  # GoAway: let the run loop reconnect
            if not got_any:
                # receive() ended with no message: the socket closed.
                return

    async def _handle(self, types: Any, session: Any, response: Any) -> bool:
        """Handle one server message. Returns True on GoAway."""
        # Assistant audio out (24 kHz PCM).
        if response.data is not None:
            self.counters["audio_out"] += 1
            if not self._in_reply:
                # First audio of a reply: the server-side half of the
                # latency measurement (the phone measures the full span).
                self._in_reply = True
                self.counters["turns"] += 1
                _tlog("model_audio_start", turn=self.counters["turns"])
            await self._state("speaking")
            await self.link.audio(response.data)

        sc = getattr(response, "server_content", None)
        if sc is not None:
            if getattr(sc, "interrupted", False):
                self._in_reply = False
                _tlog("model_interrupted")
                # Barge-in: user spoke over the assistant.
                await self.link.flush_playback()
                await self._state("listening")
            out_tx = getattr(sc, "output_transcription", None)
            if out_tx and getattr(out_tx, "text", None):
                await self.link.transcript("jarvis", out_tx.text)
            in_tx = getattr(sc, "input_transcription", None)
            if in_tx and getattr(in_tx, "text", None):
                await self.link.transcript("you", in_tx.text)
            # Inline IMAGES from the model turn (e.g. a generated chart).
            # Note: on native-audio models the spoken reply also arrives as
            # inline_data (audio/pcm), so the mime type must be checked or
            # every audio chunk would pop up as a bogus image.
            model_turn = getattr(sc, "model_turn", None)
            if model_turn:
                for part in getattr(model_turn, "parts", []) or []:
                    inline = getattr(part, "inline_data", None)
                    if not inline or not getattr(inline, "data", None):
                        continue
                    mime = (getattr(inline, "mime_type", "") or "").lower()
                    if mime.startswith("image/"):
                        await self.link.show_image(inline.data)
            if getattr(sc, "turn_complete", False):
                self._in_reply = False
                _tlog("model_turn_complete")
                await self._state("listening")

        # Tool calls run as tasks: this loop must keep forwarding audio
        # (and interruptions) while a slow tool such as run_python works.
        tc = getattr(response, "tool_call", None)
        if tc is not None:
            for fc in tc.function_calls:
                self.counters["tool_calls"] += 1
                _tlog("tool_call", name=fc.name)
                if self.tools.is_blocking(fc.name):
                    # Phone actions are quick and the model waits for
                    # them anyway; showing "thinking" is accurate.
                    await self._state("thinking")
                fid = getattr(fc, "id", None) or f"{fc.name}-{self.counters['tool_calls']}"
                task = asyncio.create_task(self._run_tool(types, session, fid, fc))
                self._tool_tasks[fid] = task
                task.add_done_callback(lambda _t, k=fid: self._tool_tasks.pop(k, None))

        # The model dropped tool calls it no longer wants (e.g. the user
        # changed their mind). Stop them; their result would be stale.
        tcc = getattr(response, "tool_call_cancellation", None)
        if tcc is not None:
            for fid in getattr(tcc, "ids", None) or []:
                self.counters["tool_cancels"] += 1
                _tlog("tool_cancelled", id=fid)
                t = self._tool_tasks.pop(fid, None)
                if t is not None:
                    t.cancel()
            await self._state("listening")

        # Session resumption handle (for seamless reconnect).
        sru = getattr(response, "session_resumption_update", None)
        if sru is not None and getattr(sru, "resumable", False):
            self._resume_handle = sru.new_handle

        # GoAway: server will close soon; break so the run loop reconnects.
        if getattr(response, "go_away", None) is not None:
            return True
        return False

    async def _run_tool(self, types: Any, session: Any, fid: str, fc: Any) -> None:
        try:
            result = await self.tools.dispatch(fc.name, dict(fc.args or {}))
        except asyncio.CancelledError:
            _tlog("tool_stopped", name=fc.name)
            raise
        resp_kw: dict[str, Any] = {}
        if not IS_LEGACY_MODEL and not self.tools.is_blocking(fc.name):
            # Background result: deliver when the model is idle instead of
            # cutting into whatever it is saying.
            resp_kw["scheduling"] = types.FunctionResponseScheduling.WHEN_IDLE
        try:
            await session.send_tool_response(function_responses=[
                types.FunctionResponse(
                    id=getattr(fc, "id", None),
                    name=fc.name,
                    response={"result": result},
                    **resp_kw,
                )
            ])
            _tlog("tool_done", name=fc.name)
        except Exception as exc:  # noqa: BLE001 - session may have reconnected
            _tlog("tool_response_failed", name=fc.name, err=type(exc).__name__)

    def close(self) -> None:
        self._closed = True
        # Unblock the pumps.
        try:
            self._mic_q.put_nowait(b"")
        except Exception:  # noqa: BLE001
            pass
        self._cam_event.set()
        for t in list(self._tool_tasks.values()):
            t.cancel()
