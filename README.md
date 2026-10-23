# drylab-jarvis

Pixel 8 Pro (husky) Volume-Up voice recorder that uploads clips to this PC.

Press Volume Up to start recording through the phone mic; press again to stop.
A root daemon (Magisk module) captures the key, a companion Android app records
and uploads, and a FastAPI server on this PC stores the clips.

## Layout

| Path | What |
|------|------|
| `server/` | FastAPI upload server (`/health`, `/upload`). Runs with `uv`. |
| `android-app/` | Kotlin app: trampoline activity, mic foreground service, AAC recorder, WorkManager upload queue, settings screen. |
| `magisk-module/` | `volrecd` C daemon (grabs Volume Up, forwards other keys) + module scripts + `build.sh`. |
| `firmware/` | Stock `init_boot.img` backups (gitignored). |
| `recordings/` | Uploaded clips, `YYYY-MM-DD/` (gitignored). |
| `tests/` | Test matrix (`cases.md`) and proof logs. |

## Quick start (software)

```bash
# Server
cd server && uv run uvicorn app:app --host 0.0.0.0 --port 8765

# Daemon + Magisk module zip
cd magisk-module && ANDROID_HOME=~/Android/Sdk ./build.sh   # -> build/volrec.zip

# App APK
cd android-app && JAVA_HOME=~/.local/jdk-17 ANDROID_HOME=~/Android/Sdk \
  ./gradlew :app:assembleDebug   # -> app/build/outputs/apk/debug/
```

## Phone bring-up

Unlock the bootloader (wipes the phone), patch `init_boot.img` with Magisk,
flash it, then install the module zip and the app APK. Full step-by-step lives
in the plan. The daemon finds the Volume Up input node at runtime, so reboots
that renumber `/dev/input/eventN` do not break it.

Kill switch: `touch /data/adb/volrec/disable` restores normal volume behaviour.
