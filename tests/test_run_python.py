"""Edge-case tests for the run_python tool (no Gemini / phone needed).

Run: python3 tests/test_run_python.py
"""

from __future__ import annotations

import asyncio
import glob
import os
import sys
import tempfile
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))

import assistant_tools  # noqa: E402
from assistant_tools import ToolRouter  # noqa: E402


class FakeLink:
    """Captures what would have been sent to the phone."""

    def __init__(self) -> None:
        self.images: list[tuple[int, str]] = []
        self.transcripts: list[str] = []

    async def show_image(self, png: bytes, caption: str = "") -> None:
        self.images.append((len(png), caption))

    async def transcript(self, who: str, text: str) -> None:
        self.transcripts.append(text)

    async def tool(self, action: str, **kw) -> None:
        pass


def run(args: dict) -> tuple[str, FakeLink]:
    link = FakeLink()
    router = ToolRouter(link)
    result = asyncio.run(router.dispatch("run_python", args))
    return result, link


FAILURES: list[str] = []


def check(case: str, ok: bool, detail: str = "") -> None:
    print(f"{'PASS' if ok else 'FAIL'}  {case}")
    if not ok:
        print(f"      {detail[:400]}")
        FAILURES.append(case)


# 1 - exact big arithmetic
out, _ = run({"code": "print(2**300)"})
check("1 big arithmetic", str(2**300) in out, out)

# 2 - real machine data
out, _ = run({"code": "import shutil; print(shutil.disk_usage('/').free)"})
check("2 machine data", any(c.isdigit() for c in out), out)

# 3 - matplotlib figure auto-captured and pushed
out, link = run(
    {
        "code": (
            "import matplotlib.pyplot as plt, math\n"
            "xs=[i/10 for i in range(60)]\n"
            "plt.plot(xs,[math.sin(x) for x in xs])\n"
            "print('plotted')"
        ),
        "purpose": "sine wave",
    }
)
check("3 figure auto-captured", len(link.images) == 1 and link.images[0][0] > 1000, f"{link.images} {out}")

# 5 - exception surfaces, does not raise
out, _ = run({"code": "print(1/0)"})
check("5 exception handled", "failed" in out.lower() and "ZeroDivisionError" in out, out)

# 6 - infinite loop killed by timeout
assistant_tools.CODE_TIMEOUT = 3.0
t0 = time.monotonic()
out, _ = run({"code": "while True: pass"})
elapsed = time.monotonic() - t0
check("6 timeout kills loop", "too long" in out.lower() and elapsed < 10, f"{elapsed:.1f}s {out}")
assistant_tools.CODE_TIMEOUT = 20.0

# 6b - timeout kills CHILD processes too (process group)
assistant_tools.CODE_TIMEOUT = 3.0
out, _ = run(
    {
        "code": (
            "import subprocess,sys,time\n"
            "subprocess.Popen([sys.executable,'-c','import time; time.sleep(120)'])\n"
            "time.sleep(60)"
        )
    }
)
check("6b timeout kills children", "too long" in out.lower(), out)
assistant_tools.CODE_TIMEOUT = 20.0

# 7 - huge stdout truncated
out, _ = run({"code": "print('x'*200000)"})
check(
    "7 huge output clipped",
    len(out) < assistant_tools.MAX_OUTPUT_CHARS + 500 and "truncated" in out,
    f"len={len(out)}",
)

# 8 - syntax error
out, _ = run({"code": "def ("})
check("8 syntax error", "failed" in out.lower(), out)

# 9 - disabled via env
assistant_tools.CODE_ENABLED = False
out, _ = run({"code": "print(1)"})
check("9 disabled respected", "disabled" in out.lower(), out)
assistant_tools.CODE_ENABLED = True

# 10 - no figure => no bogus image
out, link = run({"code": "print('just text')"})
check("10 no bogus image", link.images == [] and "just text" in out, f"{link.images} {out}")

# 11 - missing code arg
out, _ = run({})
check("11 empty code", out == "no code provided", out)

# 12 - unicode round-trip
out, _ = run({"code": "print('xin chào 🌏 日本語')"})
check("12 unicode", "xin chào 🌏 日本語" in out, out)

# 13 - indented code block from model survives the wrapper
out, _ = run({"code": "    for i in range(3):\n        print(i*2)"})
check("13 indented code", "0" in out and "2" in out and "4" in out, out)

# 14 - code that prints nothing gets a nudge, not a silent success
out, _ = run({"code": "x = 5"})
check("14 silent code nudge", "printed nothing" in out.lower(), out)

# 15 - a missing package must not make the model claim it has no access.
# Regression: it once answered "I can't access your PC's system metrics"
# after reaching for psutil, which was not installed.
out, _ = run({"code": "import no_such_pkg_xyz"})
check(
    "15 missing module nudges retry",
    "standard library" in out and "Do NOT tell the user" in out,
    out,
)

# 16 - braces in model code must survive the _CODE_WRAPPER str.format
for label, snippet in (
    ("dict", 'print({"a": 1})'),
    ("fstring", 'n=7\nprint(f"v={n}")'),
    ("dictcomp", "print({k: k**2 for k in range(3)})"),
):
    out, _ = run({"code": snippet})
    check(f"16 braces/{label}", "failed" not in out.lower(), out)

# 17 - hostile snippets must not take the server down with them
for label, snippet in (
    ("sys.exit", "import sys; sys.exit(3)"),
    ("os._exit", "import os; os._exit(1)"),
    ("self SIGKILL", "import os,signal; os.kill(os.getpid(), signal.SIGKILL)"),
    ("stdin read", "print(input('?'))"),
):
    out, _ = run({"code": snippet})
    check(f"17 survives {label}", isinstance(out, str) and bool(out), out)

# 18 - the event loop must stay responsive while a snippet busy-loops:
# the subprocess must not be awaited in a blocking way.
assistant_tools.CODE_TIMEOUT = 4.0


async def _loop_responsive() -> int:
    ticks = 0

    async def ticker() -> None:
        nonlocal ticks
        while True:
            await asyncio.sleep(0.2)
            ticks += 1

    t = asyncio.create_task(ticker())
    await ToolRouter(FakeLink()).dispatch("run_python", {"code": "while True: pass"})
    t.cancel()
    return ticks


ticks = asyncio.run(_loop_responsive())
check("18 event loop not blocked", ticks > 10, f"only {ticks} ticks")
assistant_tools.CODE_TIMEOUT = 20.0


# 19 - concurrent snippets stay isolated from each other
async def _concurrent() -> list[str]:
    router = ToolRouter(FakeLink())
    return await asyncio.gather(
        *[router.dispatch("run_python", {"code": f"print({i}**2)"}) for i in range(8)]
    )


outs = asyncio.run(_concurrent())
check(
    "19 concurrent isolated",
    all(str(i**2) in o for i, o in enumerate(outs)),
    str(outs)[:300],
)

# 20 - no temp dirs or stray processes left behind
leftover = glob.glob(os.path.join(tempfile.gettempdir(), "jarvis-code-*"))
check("20 no temp dirs leaked", leftover == [], f"leftover: {leftover}")

print()
if FAILURES:
    print(f"{len(FAILURES)} FAILED: {', '.join(FAILURES)}")
    sys.exit(1)
print("all run_python cases passed")
