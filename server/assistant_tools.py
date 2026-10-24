"""Tool declarations + dispatch for the Jarvis voice assistant.

Tools fall into two groups:
  - PHONE tools (open_camera, close_camera, start_recording, stop_recording,
    show_image): the PC asks the phone to do something via the PhoneLink
    control channel. These return a short status string to Gemini.
  - PC tools (draw_chart, run_python): computed on the PC and pushed to the
    phone as an image via show_image. Used because native code_execution is
    not available on the native-audio Live model.

dispatch() returns a JSON-serialisable result that is sent back to Gemini as
the function response.

run_python executes real code as the logged-in user, so it is gated by
JARVIS_ALLOW_CODE (default on) and bounded by JARVIS_CODE_TIMEOUT seconds.
"""

from __future__ import annotations

import asyncio
import io
import json
import os
import shutil
import signal
import sys
import tempfile
import textwrap
import threading
from typing import Any

# run_python limits. Kept small so a runaway script can never wedge the
# assistant: the model just gets a timeout string and can tell the user.
CODE_TIMEOUT = float(os.environ.get("JARVIS_CODE_TIMEOUT", "20"))
CODE_ENABLED = os.environ.get("JARVIS_ALLOW_CODE", "1").strip().lower() not in (
    "0",
    "false",
    "no",
)
# Cap what we feed back to the model: stdout goes into the prompt, and a
# runaway print loop would otherwise blow up the context and the WebSocket.
MAX_OUTPUT_CHARS = 4000

# Wrapper around user code: if the snippet leaves a matplotlib figure open we
# save it to a known path so the chart can be pushed to the phone without the
# model needing a second tool call.
_CODE_WRAPPER = """\
import os, sys
os.environ.setdefault("MPLBACKEND", "Agg")
__jarvis_fig = os.environ["JARVIS_FIG_PATH"]

{body}

# Auto-capture a figure the snippet drew but never saved.
try:
    import matplotlib.pyplot as _plt
    if _plt.get_fignums():
        _plt.savefig(__jarvis_fig, dpi=140, bbox_inches="tight")
except Exception:
    pass
"""

# matplotlib is imported lazily inside draw_chart so the server still starts
# if it is not installed.


class ToolRouter:
    def __init__(self, link: "PhoneLink") -> None:  # noqa: F821 - runtime type
        self.link = link
        # Latest camera state, so the model can be told whether it can see.
        self.camera_on = False
        # Optional context about a just-recorded file (post-recording hook).
        self.pending_file: str | None = None
        # Set by the server: called when the camera is closed, to finish the
        # PC live window / recording of the assistant camera.
        self.on_camera_closed = None
        self.on_camera_opened = None

    # -- declarations -------------------------------------------------------

    # Phone actions finish in well under a second and the reply depends on
    # them ("the camera is open" must not be said before it is), so the model
    # waits for them. On gemini-3.8-live tools are NON_BLOCKING by default,
    # so this has to be stated explicitly.
    BLOCKING = {"open_camera", "close_camera", "start_recording",
                "stop_recording", "show_image"}

    def is_blocking(self, name: str) -> bool:
        return name in self.BLOCKING

    def declarations(self, non_blocking_ok: bool = True) -> list[dict]:
        """Tool schemas. With non_blocking_ok (3.8 models) slow PC tools are
        NON_BLOCKING, so Jarvis keeps talking while they run, like the
        paper's background model; the 2.5 models don't accept `behavior`."""
        decls = self._schemas()
        if non_blocking_ok:
            for d in decls:
                d["behavior"] = "BLOCKING" if d["name"] in self.BLOCKING else "NON_BLOCKING"
        return decls

    def _schemas(self) -> list[dict]:
        return [
            {
                "name": "open_camera",
                "description": "Turn on the phone's back camera so you can see. "
                "Call this before answering questions about what the camera "
                "sees. Frames stream to you at about 1 fps.",
                "parameters": {"type": "object", "properties": {}},
            },
            {
                "name": "close_camera",
                "description": "Turn off the phone camera when you no longer "
                "need to see.",
                "parameters": {"type": "object", "properties": {}},
            },
            {
                "name": "start_recording",
                "description": "Start recording audio on the phone to a file.",
                "parameters": {"type": "object", "properties": {}},
            },
            {
                "name": "stop_recording",
                "description": "Stop the current phone audio recording.",
                "parameters": {"type": "object", "properties": {}},
            },
            {
                "name": "show_image",
                "description": "Not usually needed directly; prefer draw_chart. "
                "Shows a previously generated image on the phone screen.",
                "parameters": {"type": "object", "properties": {}},
            },
            {
                "name": "draw_chart",
                "description": "Draw a chart from data and pop it up on the "
                "phone screen. Use this whenever the user asks to visualise "
                "numbers or 'draw a chart/graph'. Provide the data you read "
                "(e.g. from the camera).",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "chart_type": {
                            "type": "string",
                            "enum": ["bar", "line", "pie", "scatter"],
                            "description": "The kind of chart.",
                        },
                        "title": {"type": "string"},
                        "x_label": {"type": "string"},
                        "y_label": {"type": "string"},
                        "labels": {
                            "type": "array",
                            "items": {"type": "string"},
                            "description": "Category/x labels.",
                        },
                        "values": {
                            "type": "array",
                            "items": {"type": "number"},
                            "description": "Numeric values aligned with labels.",
                        },
                    },
                    "required": ["chart_type", "values"],
                },
            },
            {
                "name": "run_python",
                "description": (
                    "Run Python code on the user's PC and get back whatever it "
                    "prints. Use this for anything you cannot reliably do in "
                    "your head or that needs real data from the machine: exact "
                    "or large arithmetic, statistics, date maths, parsing, "
                    "reading files, checking disk space / CPU / memory, or "
                    "running a shell command via subprocess. "
                    "IMPORTANT: print() what you want to know - only stdout "
                    "comes back. If you draw a matplotlib figure it is shown on "
                    "the user's phone automatically. Say you are working on it "
                    "before calling this, then report the result in one or two "
                    "short sentences."
                ),
                "parameters": {
                    "type": "object",
                    "properties": {
                        "code": {
                            "type": "string",
                            "description": (
                                "Python 3 source to execute. Use print() for "
                                "any value you need back."
                            ),
                        },
                        "purpose": {
                            "type": "string",
                            "description": (
                                "One short phrase on what this computes, for "
                                "the user-visible log."
                            ),
                        },
                    },
                    "required": ["code"],
                },
            },
        ]

    # -- dispatch -----------------------------------------------------------

    async def dispatch(self, name: str, args: dict) -> Any:
        try:
            if name == "open_camera":
                self.camera_on = True
                if self.on_camera_opened is not None:
                    self.on_camera_opened()
                await self.link.tool("open_camera")
                # The result text is what the model speaks from, so tell it to
                # confirm out loud and keep listening.
                return (
                    "The camera is now open and you can see through it. "
                    "Tell the user out loud that the camera is open, then keep "
                    "listening for what they want you to do."
                )
            if name == "close_camera":
                self.camera_on = False
                await self.link.tool("close_camera")
                if self.on_camera_closed is not None:
                    # Finalise the PC live window + mp4 in the background so
                    # Jarvis can confirm right away.
                    asyncio.create_task(self.on_camera_closed())
                return "camera turned off"
            if name == "start_recording":
                await self.link.tool("start_recording")
                return "recording started"
            if name == "stop_recording":
                await self.link.tool("stop_recording")
                return "recording stopped"
            if name == "show_image":
                return "nothing to show; use draw_chart to create an image"
            if name == "draw_chart":
                return await self._draw_chart(args)
            if name == "run_python":
                return await self._run_python(args)
            return f"unknown tool: {name}"
        except Exception as exc:  # noqa: BLE001
            return f"tool error: {exc}"

    async def _draw_chart(self, args: dict) -> str:
        """Render in a worker thread: matplotlib takes 100s of ms (seconds on
        first import) and used to block the event loop, freezing audio for
        every connected phone while a chart was drawn."""
        chart_type = str(args.get("chart_type", "bar"))
        try:
            png = await asyncio.to_thread(_render_chart, args)
        except Exception as exc:  # noqa: BLE001
            return f"could not draw the chart: {exc}"
        if png is None:
            return "no values provided"
        await self.link.show_image(png, caption=str(args.get("title", "chart")))
        return (
            f"The {chart_type} chart is drawn and is now showing on the user's "
            "phone screen. Tell them it is on screen and briefly describe what "
            "it shows."
        )

    # -- run_python ---------------------------------------------------------

    async def _run_python(self, args: dict) -> str:
        """Execute model-written Python in a subprocess and return its stdout.

        Deliberately a separate interpreter process, not exec(): it gives a
        hard timeout, keeps a crash or sys.exit() away from the server's event
        loop, and lets the whole process group be killed if the snippet spawns
        children.
        """
        if not CODE_ENABLED:
            return (
                "Code execution is disabled on this server (JARVIS_ALLOW_CODE=0). "
                "Tell the user it is turned off."
            )

        code = str(args.get("code") or "").strip()
        if not code:
            return "no code provided"

        purpose = str(args.get("purpose") or "running code")
        # Surface the code on the phone so the user always sees what ran.
        await self.link.transcript("jarvis", f"[running: {purpose}]")

        workdir = tempfile.mkdtemp(prefix="jarvis-code-")
        fig_path = os.path.join(workdir, "figure.png")
        script = os.path.join(workdir, "snippet.py")
        try:
            with open(script, "w", encoding="utf-8") as fh:
                # textwrap.indent keeps the model's own indentation valid
                # inside the wrapper's module-level body.
                fh.write(_CODE_WRAPPER.format(body=textwrap.dedent(code)))

            env = dict(os.environ)
            env["JARVIS_FIG_PATH"] = fig_path
            env["MPLBACKEND"] = "Agg"

            proc = await asyncio.create_subprocess_exec(
                sys.executable,
                "-I",  # isolated: ignore PYTHON* env and the cwd on sys.path
                script,
                cwd=workdir,
                env=env,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                # Own process group, so a timeout kills any children too.
                start_new_session=True,
            )
            def kill_group() -> None:
                # Kill the group, not just the child: a snippet that spawned a
                # subprocess would otherwise leave it running forever.
                try:
                    os.killpg(proc.pid, signal.SIGKILL)
                except (ProcessLookupError, PermissionError):
                    try:
                        proc.kill()
                    except ProcessLookupError:
                        pass

            try:
                raw_out, raw_err = await asyncio.wait_for(
                    proc.communicate(), timeout=CODE_TIMEOUT
                )
            except asyncio.CancelledError:
                # Tools now run as tasks: the model can cancel the call
                # (tool_call_cancellation) or the chat can end mid-run.
                # CancelledError is not TimeoutError, so without this the
                # snippet kept running while `finally` deleted its cwd.
                kill_group()
                raise
            except asyncio.TimeoutError:
                kill_group()
                await proc.wait()
                return (
                    f"The code was still running after {CODE_TIMEOUT:.0f} seconds "
                    "and was stopped. Tell the user it took too long, and try a "
                    "cheaper approach if you can."
                )

            out = _clip(raw_out.decode("utf-8", "replace").strip())
            err = _clip(raw_err.decode("utf-8", "replace").strip())

            # A drawn figure is worth more than words: push it to the phone.
            shown = False
            if os.path.isfile(fig_path) and os.path.getsize(fig_path) > 0:
                with open(fig_path, "rb") as fh:
                    await self.link.show_image(fh.read(), caption=purpose)
                shown = True

            if proc.returncode != 0:
                # Last lines of the traceback are the useful part for a retry.
                detail = err or out or "no output"
                # A missing third-party package is recoverable: the standard
                # library can almost always do the same job. Without this nudge
                # the model apologises and claims it has no access at all.
                if "ModuleNotFoundError" in detail or "ImportError" in detail:
                    return (
                        f"The code failed because a package is not installed:\n"
                        f"{detail}\n"
                        "Do NOT tell the user you lack access. Call run_python "
                        "again using only the standard library (os, shutil, "
                        "subprocess, platform, pathlib, statistics, datetime, "
                        "json, math) to get the same answer."
                    )
                return (
                    f"The code failed:\n{detail}\n"
                    "Explain the problem to the user in one short sentence, or "
                    "fix the code and try once more."
                )

            parts = []
            if out:
                parts.append(f"Output:\n{out}")
            if err:
                # Non-fatal stderr (warnings) - useful context, not a failure.
                parts.append(f"Warnings:\n{err}")
            if shown:
                parts.append(
                    "A figure from this code is now showing on the user's phone "
                    "screen."
                )
            if not parts:
                return (
                    "The code ran fine but printed nothing. If you needed a "
                    "value, call run_python again with a print() around it."
                )
            return (
                "\n".join(parts)
                + "\nNow tell the user the answer out loud, briefly and in "
                "plain language. Do not read out the code."
            )
        except Exception as exc:  # noqa: BLE001
            return f"could not run the code: {exc}"
        finally:
            shutil.rmtree(workdir, ignore_errors=True)


def _clip(text: str, limit: int = MAX_OUTPUT_CHARS) -> str:
    """Trim runaway output, keeping the head and the tail.

    The tail matters: for a traceback the last lines name the actual error.
    """
    if len(text) <= limit:
        return text
    head = text[: limit // 2]
    tail = text[-limit // 2 :]
    return f"{head}\n...[{len(text) - limit} chars truncated]...\n{tail}"


_plot_lock = threading.Lock()


def warm_matplotlib() -> None:
    """Import matplotlib once at startup so the first chart isn't slow."""
    try:
        import matplotlib

        matplotlib.use("Agg")
        import matplotlib.pyplot  # noqa: F401
    except Exception:  # noqa: BLE001
        pass


def _render_chart(args: dict) -> bytes | None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    chart_type = str(args.get("chart_type", "bar"))
    values = [float(v) for v in (args.get("values") or [])]
    labels = [str(x) for x in (args.get("labels") or [])]
    if not values:
        return None
    if not labels or len(labels) != len(values):
        labels = [str(i + 1) for i in range(len(values))]

    # pyplot keeps global state, so charts are drawn one at a time.
    with _plot_lock:
        fig, ax = plt.subplots(figsize=(6, 4), dpi=140)
        try:
            if chart_type == "line":
                ax.plot(labels, values, marker="o")
            elif chart_type == "pie":
                ax.pie(values, labels=labels, autopct="%1.0f%%")
            elif chart_type == "scatter":
                ax.scatter(range(len(values)), values)
                ax.set_xticks(range(len(values)))
                ax.set_xticklabels(labels)
            else:  # bar
                ax.bar(labels, values)
            if args.get("title"):
                ax.set_title(str(args["title"]))
            if chart_type != "pie":
                if args.get("x_label"):
                    ax.set_xlabel(str(args["x_label"]))
                if args.get("y_label"):
                    ax.set_ylabel(str(args["y_label"]))
            fig.tight_layout()
            buf = io.BytesIO()
            fig.savefig(buf, format="png")
            return buf.getvalue()
        finally:
            plt.close(fig)
