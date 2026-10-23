"""Reference: stream 1280-sample frames through the real openWakeWord and
print the per-frame hey_jarvis score. Ground truth for the Kotlin port."""
import sys

import numpy as np
from openwakeword.model import Model

d = ".venv/lib/python3.12/site-packages/openwakeword/resources/models/"
m = Model(wakeword_model_paths=[d + "hey_jarvis_v0.1.onnx"])


def run(path):
    pcm = np.fromfile(path, dtype=np.int16)
    # 1 s silence padding before/after, like predict_clip
    pad = np.zeros(16000, dtype=np.int16)
    pcm = np.concatenate([pad, pcm, pad])
    m.reset()
    scores = []
    for i in range(0, len(pcm) - 1280 + 1, 1280):
        p = m.predict(pcm[i : i + 1280])
        scores.append(float(p["hey_jarvis_v0.1"]))
    return scores


for f in sys.argv[1:]:
    s = run(f)
    print(f, "frames", len(s), "max %.4f" % max(s), "at", int(np.argmax(s)))
    print("  ", " ".join("%.3f" % x for x in s))
