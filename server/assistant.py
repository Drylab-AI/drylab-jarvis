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
  JARVIS_LIVE_MODEL         default gemini-2.5-flash-native-audio-preview-12-2025
"""

from __future__ import annotations

import asyncio
import base64
import json
import os
import traceback
from typing import Any, Awaitable, Callable

# Opcodes for binary frames on the phone WebSocket.
OP_MIC_PCM = 0x01
OP_CAM_JPEG = 0x02
OP_ASSISTANT_PCM = 0x11

LIVE_MODEL = os.environ.get(
    "JARVIS_LIVE_MODEL", "gemini-2.5-flash-native-audio-preview-12-2025"
)
GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY", "").strip()

SYSTEM_INSTRUCTION = os.environ.get(
    "JARVIS_SYSTEM_PROMPT",
    "You are Jarvis, a concise, friendly voice assistant running on the user's "
    "phone. Keep spoken replies short and natural. You can see through the "
    "phone camera when it is on. You have tools to open/close the camera, "
    "start/stop audio recording, draw a chart, and show an image on the phone "
    "screen. When the user asks you to visualise data or 'draw a chart', call "
    "the draw_chart tool. When you want to show the user a picture, call "
    "show_image. Only turn the camera on when you actually need to see "
    "something, and turn it off when done.",
)


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
        self._mic_q: asyncio.Queue[bytes] = asyncio.Queue(maxsize=200)
        self._cam_q: asyncio.Queue[bytes] = asyncio.Queue(maxsize=8)
        self._session = None
        self._resume_handle: str | None = None
        self._closed = False

    # -- phone -> bridge: the WS handler calls these ------------------------

    def push_mic(self, pcm: bytes) -> None:
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
        try:
            self._cam_q.put_nowait(jpeg)
        except asyncio.QueueFull:
            try:
                self._cam_q.get_nowait()
            except asyncio.QueueEmpty:
                pass
            try:
                self._cam_q.put_nowait(jpeg)
            except asyncio.QueueFull:
                pass

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
                    self._session = session
                    await self.link.state("listening")
                    await asyncio.gather(
                        self._pump_mic(),
                        self._pump_cam(),
                        self._receive(types),
                    )
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001
                if self._closed:
                    break
                traceback.print_exc()
                await self.link.error(f"live session error: {exc}")
                # brief backoff then reconnect (resume handle reused if any)
                await asyncio.sleep(1.5)
            finally:
                self._session = None
        await self.link.state("closed")

    def _build_config(self, types: Any):
        tool_decls = self.tools.declarations()
        tools_cfg: list[dict] = []
        if tool_decls:
            tools_cfg.append({"function_declarations": tool_decls})
        # Search grounding is supported on the native-audio model. (Native
        # code_execution is NOT, so charts are a custom function tool instead.)
        tools_cfg.append({"google_search": {}})

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
                    silence_duration_ms=600,
                )
            ),
        )

    async def _pump_mic(self) -> None:
        from google.genai import types

        while not self._closed:
            pcm = await self._mic_q.get()
            if self._session is None:
                continue
            try:
                await self._session.send_realtime_input(
                    audio=types.Blob(data=pcm, mime_type="audio/pcm;rate=16000")
                )
            except Exception:  # noqa: BLE001 - session may be reconnecting
                await asyncio.sleep(0.05)

    async def _pump_cam(self) -> None:
        from google.genai import types

        while not self._closed:
            jpeg = await self._cam_q.get()
            if self._session is None:
                continue
            try:
                await self._session.send_realtime_input(
                    video=types.Blob(data=jpeg, mime_type="image/jpeg")
                )
            except Exception:  # noqa: BLE001
                await asyncio.sleep(0.05)
            # Cap at ~1 fps regardless of how fast frames arrive.
            await asyncio.sleep(1.0)

    async def _receive(self, types: Any) -> None:
        assert self._session is not None
        session = self._session
        async for response in session.receive():
            if self._closed:
                return

            # Assistant audio out (24 kHz PCM).
            if response.data is not None:
                await self.link.state("speaking")
                await self.link.audio(response.data)

            sc = getattr(response, "server_content", None)
            if sc is not None:
                if getattr(sc, "interrupted", False):
                    # Barge-in: user spoke over the assistant.
                    await self.link.flush_playback()
                    await self.link.state("listening")
                out_tx = getattr(sc, "output_transcription", None)
                if out_tx and getattr(out_tx, "text", None):
                    await self.link.transcript("jarvis", out_tx.text)
                in_tx = getattr(sc, "input_transcription", None)
                if in_tx and getattr(in_tx, "text", None):
                    await self.link.transcript("you", in_tx.text)
                # Inline images from the model turn (e.g. a chart).
                model_turn = getattr(sc, "model_turn", None)
                if model_turn:
                    for part in getattr(model_turn, "parts", []) or []:
                        inline = getattr(part, "inline_data", None)
                        if inline and getattr(inline, "data", None):
                            await self.link.show_image(inline.data)
                if getattr(sc, "turn_complete", False):
                    await self.link.state("listening")

            # Tool calls.
            tc = getattr(response, "tool_call", None)
            if tc is not None:
                await self.link.state("thinking")
                responses = []
                for fc in tc.function_calls:
                    result = await self.tools.dispatch(fc.name, dict(fc.args or {}))
                    responses.append(
                        types.FunctionResponse(
                            id=getattr(fc, "id", None),
                            name=fc.name,
                            response={"result": result},
                        )
                    )
                try:
                    await session.send_tool_response(function_responses=responses)
                except Exception:  # noqa: BLE001
                    pass

            # Session resumption handle (for seamless reconnect).
            sru = getattr(response, "session_resumption_update", None)
            if sru is not None and getattr(sru, "resumable", False):
                self._resume_handle = sru.new_handle

            # GoAway: server will close soon; break so the run loop reconnects.
            if getattr(response, "go_away", None) is not None:
                return

    def close(self) -> None:
        self._closed = True
        # Unblock the pumps.
        for q in (self._mic_q, self._cam_q):
            try:
                q.put_nowait(b"")
            except Exception:  # noqa: BLE001
                pass
