"""Scenario runner, turn measurement and the results report."""

from __future__ import annotations

import json
import statistics
import time
import traceback
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import phone
import server

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "tests/logs/interaction"


@dataclass
class Check:
    name: str
    ok: bool
    detail: str = ""


@dataclass
class Result:
    scenario: str
    checks: list[Check] = field(default_factory=list)
    numbers: dict = field(default_factory=dict)
    evidence: list[str] = field(default_factory=list)
    error: str = ""

    @property
    def ok(self) -> bool:
        return not self.error and bool(self.checks) and all(c.ok for c in self.checks)

    def check(self, name: str, ok: bool, detail: str = "") -> bool:
        self.checks.append(Check(name, bool(ok), detail))
        mark = "PASS" if ok else "FAIL"
        print(f"    {mark}  {name}  {detail}", flush=True)
        return bool(ok)


SCENARIOS: list[tuple[str, Callable[[Result], None]]] = []


def scenario(name: str):
    def deco(fn: Callable[[Result], None]):
        SCENARIOS.append((name, fn))
        return fn
    return deco


# -- conversation primitives ----------------------------------------------------

def fresh_session(r: Result, via_wake: bool = True, real_mic: bool = False,
                  greet_timeout: float = 15) -> int:
    """Stop any session, then open the assistant (by the wake word by
    default). Returns the phone time the wake/launch happened; waits for the
    greeting audio to finish so the next turn starts clean."""
    phone.stop_assistant()
    phone.set_test_mode(True, real_mic=real_mic)
    # The activity stays on screen after the service stops. Launching it
    # again then only "restarts" the visible activity, which does not start
    # a new service (seen as result code=3 with no assist_start), so close
    # it first: every scenario starts from a clean slate.
    phone.go_home()
    time.sleep(2.5)
    phone.wake_screen()
    phone.logcat_clear()
    t0 = phone.device_ms()
    if via_wake:
        phone.wake_test()
    else:
        phone.start_assistant()
    start = phone.wait_event("assist_start", t0, 10)
    if start is None and via_wake:
        # The wake path ran but the activity was not relaunched; fall back
        # to an explicit start so one flaky launch doesn't sink the scenario.
        phone.start_assistant()
    greet = phone.wait_event("reply_audio_start", t0, greet_timeout)
    r.numbers.setdefault("_greet", []).append(None if greet is None else greet.t - t0)
    if greet is not None:
        wait_quiet(greet.t)
    return t0


def last_reply_audio(since: int) -> int:
    """Phone time the most recent reply audio chunk was played (approx.)."""
    evs = [e for e in phone.timeline(since) if e.name in ("reply_audio_start", "reply_audio_end")]
    return evs[-1].t if evs else since


def wait_quiet(since: int, quiet_s: float = 1.5, timeout_s: float = 40) -> None:
    """Wait until the server says the model finished its turn, or audio has
    stopped for quiet_s."""
    end = time.time() + timeout_s
    while time.time() < end:
        evs = phone.timeline(since)
        states = [e for e in evs if e.name == "state"]
        if states and states[-1].detail == "listening" and any(
            e.name == "reply_audio_start" for e in evs
        ):
            time.sleep(quiet_s)
            later = [e for e in phone.timeline(since) if e.name == "reply_audio_start"
                     and e.t > states[-1].t]
            if not later:
                return
        time.sleep(0.4)


@dataclass
class Turn:
    clip: str
    end_ms: int | None          # user stopped speaking (phone clock)
    filler_ms: int | None       # first filler played, ms after end
    model_ms: int | None        # first model audio played, ms after end
    filler_cut_ms: int | None   # filler stopped, ms after model audio arrived


def turn(clip: str, reply_timeout: float = 20) -> Turn:
    """Speak one clip and measure the reply from the phone's own timeline."""
    since = phone.device_ms()
    phone.say(clip)
    end = phone.wait_event("mic_clip_end", since, 30, detail=f"name={clip}")
    if end is None:
        return Turn(clip, None, None, None, None)
    deadline = time.time() + reply_timeout
    model = filler = cut = None
    while time.time() < deadline and model is None:
        for e in phone.timeline(end.t):
            if e.name == "reply_audio_start" and "source=model" in e.detail and model is None:
                model = e.t - end.t
            if e.name == "filler_start" and filler is None:
                filler = e.t - end.t
            if e.name == "filler_cut" and cut is None:
                cut = e
        time.sleep(0.25)
    cut_ms = None
    if cut is not None and model is not None:
        cut_ms = cut.t - (end.t + model)
    wait_quiet(end.t)
    return Turn(clip, end.t, filler, model, cut_ms)


def latency_stats(values: list[int]) -> dict:
    v = [x for x in values if x is not None]
    if not v:
        return {"n": 0}
    return {
        "n": len(v),
        "median_ms": int(statistics.median(v)),
        "p90_ms": int(sorted(v)[max(0, int(len(v) * 0.9) - 1)]),
        "max_ms": max(v),
        "min_ms": min(v),
    }


# -- running + report -------------------------------------------------------------

def run(selected: list[str] | None, label: str) -> list[Result]:
    results: list[Result] = []
    for name, fn in SCENARIOS:
        if selected and name not in selected:
            continue
        print(f"\n=== {name}", flush=True)
        r = Result(name)
        try:
            fn(r)
        except Exception as exc:  # noqa: BLE001
            r.error = f"{type(exc).__name__}: {exc}"
            print("    ERROR", r.error, flush=True)
            traceback.print_exc()
        ev = OUT / "evidence" / label / name
        ev.mkdir(parents=True, exist_ok=True)
        (ev / "timeline.txt").write_text(
            "\n".join(f"{e.t} {e.name} {e.detail}" for e in phone.timeline(0))
        )
        r.evidence.append(str((ev / "timeline.txt").relative_to(ROOT)))
        results.append(r)
    return results


def write_report(results: list[Result], label: str) -> Path:
    OUT.mkdir(parents=True, exist_ok=True)
    data = [
        {"scenario": r.scenario, "ok": r.ok, "error": r.error,
         "checks": [c.__dict__ for c in r.checks],
         "numbers": {k: v for k, v in r.numbers.items() if not k.startswith("_")},
         "evidence": r.evidence}
        for r in results
    ]
    (OUT / f"results-{label}.json").write_text(json.dumps(data, indent=2))

    lines = [f"# Interaction e2e results: `{label}`", "",
             f"Run at {time.strftime('%Y-%m-%d %H:%M:%S')} by `tests/e2e/run_e2e.py`. "
             "Every turn was spoken by injected TTS clips; nobody touched the phone.", "",
             "| Scenario | Result | Checks | Key numbers |",
             "|----------|--------|--------|-------------|"]
    for r in results:
        nums = ", ".join(f"{k}={v}" for k, v in r.numbers.items()
                         if not k.startswith("_") and not isinstance(v, (list, dict)))
        passed = sum(c.ok for c in r.checks)
        res = "PASS" if r.ok else ("ERROR" if r.error else "FAIL")
        lines.append(f"| {r.scenario} | {res} | {passed}/{len(r.checks)} | {nums} |")
    lines += ["", "## Details", ""]
    for r in results:
        lines.append(f"### {r.scenario}: {'PASS' if r.ok else 'FAIL'}")
        if r.error:
            lines.append(f"- error: `{r.error}`")
        for c in r.checks:
            lines.append(f"- {'PASS' if c.ok else 'FAIL'} {c.name}{': ' + c.detail if c.detail else ''}")
        for k, v in r.numbers.items():
            if not k.startswith("_") and isinstance(v, (list, dict)):
                lines.append(f"- {k}: `{json.dumps(v)}`")
        for e in r.evidence:
            lines.append(f"- evidence: `{e}`")
        lines.append("")
    path = OUT / f"results-{label}.md"
    path.write_text("\n".join(lines))
    return path
