"""Tool declarations + dispatch for the Jarvis voice assistant.

Tools fall into two groups:
  - PHONE tools (open_camera, close_camera, start_recording, stop_recording,
    show_image): the PC asks the phone to do something via the PhoneLink
    control channel. These return a short status string to Gemini.
  - PC tools (draw_chart): computed on the PC (matplotlib) and pushed to the
    phone as an image via show_image. Used because native code_execution is
    not available on the native-audio Live model.

dispatch() returns a JSON-serialisable result that is sent back to Gemini as
the function response.
"""

from __future__ import annotations

import io
import json
from typing import Any

# matplotlib is imported lazily inside draw_chart so the server still starts
# if it is not installed.


class ToolRouter:
    def __init__(self, link: "PhoneLink") -> None:  # noqa: F821 - runtime type
        self.link = link
        # Latest camera state, so the model can be told whether it can see.
        self.camera_on = False
        # Optional context about a just-recorded file (post-recording hook).
        self.pending_file: str | None = None

    # -- declarations -------------------------------------------------------

    def declarations(self) -> list[dict]:
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
        ]

    # -- dispatch -----------------------------------------------------------

    async def dispatch(self, name: str, args: dict) -> Any:
        try:
            if name == "open_camera":
                self.camera_on = True
                await self.link.tool("open_camera")
                return "camera turned on; frames incoming"
            if name == "close_camera":
                self.camera_on = False
                await self.link.tool("close_camera")
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
            return f"unknown tool: {name}"
        except Exception as exc:  # noqa: BLE001
            return f"tool error: {exc}"

    async def _draw_chart(self, args: dict) -> str:
        try:
            import matplotlib

            matplotlib.use("Agg")
            import matplotlib.pyplot as plt
        except Exception as exc:  # noqa: BLE001
            return f"matplotlib unavailable: {exc}"

        chart_type = str(args.get("chart_type", "bar"))
        values = [float(v) for v in (args.get("values") or [])]
        labels = [str(x) for x in (args.get("labels") or [])]
        if not values:
            return "no values provided"
        if not labels or len(labels) != len(values):
            labels = [str(i + 1) for i in range(len(values))]

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
            png = buf.getvalue()
        finally:
            plt.close(fig)

        await self.link.show_image(png, caption=str(args.get("title", "chart")))
        return "chart drawn and shown on the phone"
