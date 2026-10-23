# openWakeWord on the phone

The "Jarvis" wake word runs on the phone with ONNX Runtime, using the
openWakeWord models in `android-app/app/src/main/assets/oww/`. It replaced
Picovoice Porcupine, whose free tier ended on June 30, 2026.

## Models and license

| File | Source |
|---|---|
| `melspectrogram.onnx` | openWakeWord feature model |
| `embedding_model.onnx` | openWakeWord port of Google's speech_embedding |
| `hey_jarvis_v0.1.onnx` | openWakeWord pretrained "hey jarvis" model |

openWakeWord (https://github.com/dscripka/openWakeWord) by David Scripka. The
code is Apache-2.0. The pretrained models are **CC BY-NC-SA 4.0**, so they can
be used for personal, non-commercial projects with attribution. A commercial
build needs a model trained on permissively licensed data.

## How the Kotlin port was checked

`OpenWakeWord.kt` reproduces openWakeWord's streaming pipeline (80 ms frames:
mel -> 76-frame embedding window -> 16-embedding classifier, first 5 scores
zeroed). Three scripts check it against the real library:

| Script | What it does |
|---|---|
| `oww_lib_reference.py` | Runs the real `openwakeword` library (v0.4.0) on a clip |
| `oww_reference.py` | Runs a short from-scratch port (onnxruntime only), the spec for the Kotlin code |
| `kt-check/` | Compiles the app's `OpenWakeWord.kt` unchanged on the PC JVM and runs it |

All three give the same per-frame scores on the test clips:

| Clip | Max score | Frame |
|---|---|---|
| `jarvis.raw` ("hey jarvis", gTTS, 16 kHz s16le) | 0.9987 | 27 |
| `other.raw` (an unrelated sentence) | 0.0001 | - |

The Kotlin port runs at about 1.2 ms per 80 ms frame on the PC.

Re-run the Kotlin check from the repo root:

```bash
JAVA_HOME=~/.local/jdk-17 ./android-app/gradlew -p tools/oww/kt-check run \
  --args="$PWD/android-app/app/src/main/assets/oww $PWD/tools/oww/jarvis.raw $PWD/tools/oww/other.raw"
```

The Python scripts need `pip install openwakeword onnxruntime numpy` and the
models next to them (see the path at the top of each script).
