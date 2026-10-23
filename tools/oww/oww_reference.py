"""Minimal from-scratch port of openWakeWord streaming inference using only
onnxruntime + plain arrays (no openwakeword import). This is the exact spec
for the Kotlin port; it must reproduce ref.py's per-frame scores.

Pipeline per 1280-sample (80 ms) frame:
  1. Append raw int16 samples to a raw buffer (keep last 16000*10).
  2. mel = melspectrogram.onnx(float32(raw[-(1280+480):]))  -> (T, 32); transform x/10+2
     (1280 + 160*3 = 1760 samples -> 8 new mel frames)
  3. Append mel frames to a mel buffer (init: 76 rows of 1.0; cap 970 rows).
  4. emb = embedding.onnx(mel_buffer[-76:]) shape (1,76,32,1) -> 96 floats
  5. Append emb to feature buffer (init from 10 s of zeros; cap 120 rows).
  6. score = classifier.onnx(feature_buffer[-16:]) shape (1,16,96)
  7. First 5 predictions are forced to 0.
"""
import sys

import numpy as np
import onnxruntime as ort

d = ".venv/lib/python3.12/site-packages/openwakeword/resources/models/"
mel = ort.InferenceSession(d + "melspectrogram.onnx")
emb = ort.InferenceSession(d + "embedding_model.onnx")
clf = ort.InferenceSession(d + "hey_jarvis_v0.1.onnx")


def melspec(samples_int16):
    x = np.asarray(samples_int16, dtype=np.float32)[None, :]
    out = mel.run(None, {"input": x})[0]  # (T,1,?,32)
    spec = np.squeeze(out)  # (T,32)
    return spec / 10.0 + 2.0


def embed(window76x32):
    x = window76x32.astype(np.float32)[None, :, :, None]
    return emb.run(None, {"input_1": x})[0].reshape(96)


class Detector:
    def __init__(self):
        self.raw = np.zeros(0, dtype=np.int16)
        self.mel_buf = np.ones((76, 32), dtype=np.float32)
        # Initial feature buffer: embeddings of 10 s of zeros (batch, step 8)
        spec = melspec(np.zeros(160000, dtype=np.int16))
        feats = []
        for i in range(0, spec.shape[0], 8):
            w = spec[i : i + 76]
            if w.shape[0] == 76:
                feats.append(embed(w))
        self.feat_buf = np.array(feats, dtype=np.float32)
        self.n_pred = 0

    def accept(self, frame1280):
        self.raw = np.concatenate([self.raw, frame1280])[-160000:]
        new_mel = melspec(self.raw[-(1280 + 480):])
        self.mel_buf = np.vstack([self.mel_buf, new_mel])[-970:]
        e = embed(self.mel_buf[-76:])
        self.feat_buf = np.vstack([self.feat_buf, e])[-120:]
        x = self.feat_buf[-16:][None, :, :].astype(np.float32)
        s = float(clf.run(None, {"x.1": x})[0][0][0])
        self.n_pred += 1
        if self.n_pred <= 5:
            s = 0.0
        return s


def run(path):
    pcm = np.fromfile(path, dtype=np.int16)
    pad = np.zeros(16000, dtype=np.int16)
    pcm = np.concatenate([pad, pcm, pad])
    det = Detector()
    return [det.accept(pcm[i : i + 1280]) for i in range(0, len(pcm) - 1280 + 1, 1280)]


if __name__ == "__main__":
    print("init feat_buf rows:", Detector().feat_buf.shape)
    for f in sys.argv[1:]:
        s = run(f)
        print(f, "frames", len(s), "max %.4f" % max(s), "at", int(np.argmax(s)))
        print("  ", " ".join("%.3f" % x for x in s))
