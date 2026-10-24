"""Offline tests for the Gemini Live bridge (no network, no phone).

A fake Live session replays scripted server messages so queueing, turn
looping, background tools and quota handling can be checked exactly.

Run: server/.venv/bin/python tests/test_assistant_bridge.py
"""

from __future__ import annotations

import asyncio
import os
import sys
import time
from types import SimpleNamespace as NS

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))
os.environ.setdefault("GEMINI_API_KEY", "test-key")

import assistant  # noqa: E402
from assistant import GeminiLiveBridge, PhoneLink  # noqa: E402
from assistant_tools import ToolRouter  # noqa: E402
from google.genai import types  # noqa: E402

FAIL: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  ({detail})" if detail and not ok else ""))
    if not ok:
        FAIL.append(name)


class Recorder:
    def __init__(self) -> None:
        self.json: list[dict] = []
        self.audio = 0

    async def send_json(self, o: dict) -> None:
        self.json.append(o)

    async def send_bytes(self, b: bytes) -> None:
        self.audio += 1


def msg(**kw):
    base = dict(data=None, server_content=None, tool_call=None, tool_call_cancellation=None,
                session_resumption_update=None, go_away=None)
    base.update(kw)
    return NS(**base)


def sc(**kw):
    base = dict(interrupted=False, output_transcription=None, input_transcription=None,
                model_turn=None, turn_complete=False)
    base.update(kw)
    return NS(**base)


class FakeSession:
    """receive() yields one scripted turn per call, like the real SDK."""

    def __init__(self, turns: list[list]) -> None:
        self.turns = list(turns)
        self.sent_audio = 0
        self.sent_video = 0
        self.tool_responses: list = []
        self.receive_calls = 0

    async def send_realtime_input(self, audio=None, video=None, text=None):
        if audio is not None:
            self.sent_audio += 1
        if video is not None:
            self.sent_video += 1

    async def send_tool_response(self, function_responses):
        self.tool_responses.extend(function_responses)

    async def receive(self):
        self.receive_calls += 1
        if not self.turns:
            return
        for m in self.turns.pop(0):
            if callable(m):
                await m()
                continue
            yield m


def make_bridge():
    rec = Recorder()
    link = PhoneLink(rec.send_json, rec.send_bytes)
    tools = ToolRouter(link)
    return GeminiLiveBridge(link, tools), tools, rec


# -- 1. mic queue cap + drain ---------------------------------------------------
br, _, _ = make_bridge()
for i in range(50):
    br.push_mic(bytes([i]) * 4)
check("mic queue capped at 10 chunks", br._mic_q.qsize() == assistant.MIC_QUEUE_CHUNKS,
      str(br._mic_q.qsize()))
newest = list(br._mic_q._queue)[-1]
check("mic queue keeps the newest audio", newest == bytes([49]) * 4)
br._drain_stale()
check("mic queue drained on connect", br._mic_q.qsize() == 0)


# -- 2. camera pump: newest frame, <= 1 fps -------------------------------------
async def t_cam():
    br, _, _ = make_bridge()
    s = FakeSession([])
    br._session = s
    task = asyncio.create_task(br._pump_cam())
    t0 = time.monotonic()
    while time.monotonic() - t0 < 2.6:
        br.push_cam(b"frame-%d" % int((time.monotonic() - t0) * 10))
        await asyncio.sleep(0.1)
    task.cancel()
    return s.sent_video, br.counters["cam_in"]

sent, got = asyncio.run(t_cam())
check("camera pump sends <= 1 fps of ~10 fps input", 2 <= sent <= 3, f"sent {sent} of {got}")


# -- 3. state only on change ----------------------------------------------------
async def t_state():
    br, _, rec = make_bridge()
    for _ in range(5):
        await br._state("speaking")
    await br._state("listening")
    await br._state("listening")
    return [o["state"] for o in rec.json if o.get("type") == "state"]

check("state sent only on change", asyncio.run(t_state()) == ["speaking", "listening"])


# -- 4. one connection across turns ---------------------------------------------
async def t_turns():
    br, _, rec = make_bridge()
    turn = [msg(data=b"\0" * 10), msg(server_content=sc(turn_complete=True))]
    s = FakeSession([turn, turn, turn])
    br._session = s
    await asyncio.wait_for(br._receive(types), timeout=2)
    return br.counters["turns"], s.receive_calls, rec.audio

turns, calls, audio = asyncio.run(t_turns())
check("_receive handles 3 turns on one connection", turns == 3 and audio == 3,
      f"turns={turns} audio={audio}")
check("_receive returns when the socket ends (no spin)", calls == 4, f"receive() calls={calls}")


# -- 5. background tool: audio keeps flowing, WHEN_IDLE ---------------------------
async def t_tool():
    br, tools, rec = make_bridge()
    gate = asyncio.Event()

    async def slow_dispatch(name, args):
        await gate.wait()
        return "done"
    tools.dispatch = slow_dispatch
    fc = NS(id="call-1", name="run_python", args={"code": "print(1)"})
    audio_after = [msg(data=b"\0" * 4) for _ in range(3)]
    s = FakeSession([[msg(tool_call=NS(function_calls=[fc]))] + audio_after
                     + [msg(server_content=sc(turn_complete=True))]])
    br._session = s
    await asyncio.wait_for(br._receive(types), timeout=2)
    audio_while_tool_ran = rec.audio
    pending = len(br._tool_tasks)
    gate.set()
    await asyncio.sleep(0.05)
    return audio_while_tool_ran, pending, s.tool_responses

a, pending, resp = asyncio.run(t_tool())
check("audio flowed while the tool was still running", a == 3 and pending == 1, f"audio={a} pending={pending}")
legacy = assistant.IS_LEGACY_MODEL
sched = getattr(resp[0], "scheduling", None) if resp else None
check("tool result sent back", len(resp) == 1 and resp[0].id == "call-1")
check("non-blocking result uses WHEN_IDLE (3.8)",
      legacy or sched == types.FunctionResponseScheduling.WHEN_IDLE, str(sched))


# -- 6. blocking phone tool: no WHEN_IDLE -----------------------------------------
async def t_block():
    br, tools, _ = make_bridge()

    async def quick(name, args):
        return "ok"
    tools.dispatch = quick
    fc = NS(id="c2", name="open_camera", args={})
    s = FakeSession([[msg(tool_call=NS(function_calls=[fc])), msg(server_content=sc(turn_complete=True))]])
    br._session = s
    await br._receive(types)
    await asyncio.sleep(0.05)
    return s.tool_responses

r = asyncio.run(t_block())
check("blocking phone tool has no scheduling", r and getattr(r[0], "scheduling", None) is None)


# -- 7. cancellation stops the task -----------------------------------------------
async def t_cancel():
    br, tools, _ = make_bridge()
    started = asyncio.Event()

    async def forever(name, args):
        started.set()
        await asyncio.sleep(30)
        return "late"
    tools.dispatch = forever
    fc = NS(id="c3", name="run_python", args={})

    async def wait_started():
        await started.wait()
    s = FakeSession([[msg(tool_call=NS(function_calls=[fc])), wait_started,
                      msg(tool_call_cancellation=NS(ids=["c3"])),
                      msg(server_content=sc(turn_complete=True))]])
    br._session = s
    await br._receive(types)
    await asyncio.sleep(0.05)
    return br.counters["tool_cancels"], len(br._tool_tasks), s.tool_responses

c, left, resp = asyncio.run(t_cancel())
check("cancellation stops the tool task", c == 1 and left == 0 and not resp,
      f"cancels={c} left={left} resp={len(resp)}")


# -- 8. declarations ------------------------------------------------------------
tr = ToolRouter(None)
d = {x["name"]: x for x in tr.declarations(non_blocking_ok=True)}
check("phone tools BLOCKING, PC tools NON_BLOCKING",
      d["open_camera"]["behavior"] == "BLOCKING" and d["run_python"]["behavior"] == "NON_BLOCKING"
      and d["draw_chart"]["behavior"] == "NON_BLOCKING")
check("legacy model gets no behavior field",
      all("behavior" not in x for x in tr.declarations(non_blocking_ok=False)))


# -- 9. quota handling ----------------------------------------------------------
class FakeConnect:
    def __init__(self, fail_with):
        self.fail_with = list(fail_with)
        self.configs = []

    def __call__(self, model, config):
        self.configs.append(config)
        outer = self

        class Ctx:
            async def __aenter__(self_inner):
                if outer.fail_with:
                    raise RuntimeError(outer.fail_with.pop(0))
                return FakeSession([])

            async def __aexit__(self_inner, *a):
                return False
        return Ctx()


async def t_quota(errors, close_after=0.6):
    assistant._SEARCH_QUOTA_OUT[0] = False
    br, _, rec = make_bridge()
    fc = FakeConnect(errors)
    import google.genai as genai_mod

    class FakeClient:
        def __init__(self, api_key):
            self.aio = NS(live=NS(connect=fc))
    orig = genai_mod.Client
    genai_mod.Client = FakeClient
    try:
        task = asyncio.create_task(br.run())
        await asyncio.sleep(close_after)
        br.close()
        try:
            await asyncio.wait_for(task, 2)
        except Exception:  # noqa: BLE001
            pass
    finally:
        genai_mod.Client = orig
    has_search = ["google_search" in str(c.tools) for c in fc.configs]
    errs = [o for o in rec.json if o.get("type") == "error"]
    return has_search, errs, br

hs, errs, br = asyncio.run(t_quota(["1011 You exceeded your current quota"]))
check("search quota -> reconnect without google_search",
      hs[:2] == [True, False] and br.counters["sessions"] >= 1 and not errs, f"{hs} errs={errs}")
hs, errs, br = asyncio.run(t_quota(["1011 You exceeded your current quota"] * 2))
check("model quota -> stop and tell the user (no retry loop)",
      len(hs) == 2 and any("quota" in e["message"].lower() for e in errs), f"{hs}")
assistant._SEARCH_QUOTA_OUT[0] = False

print()
if FAIL:
    print(f"{len(FAIL)} FAILED: {', '.join(FAIL)}")
    sys.exit(1)
print("all bridge cases passed")
