# Test matrix: Pixel Volume-Up voice recorder

Each case is run end to end on the rooted phone + this PC, with logs and the
uploaded files saved as proof under `tests/logs/`.

Status key: `pass`, `partial`, `fail`. Full results and evidence in
[e2e-results.md](logs/e2e-results.md).

| # | Case | How to verify | Status |
|---|------|---------------|--------|
| 1 | Screen on, app closed: press, talk 10 s, press again | `.m4a` appears in `recordings/`, plays back | pass |
| 2 | Screen off and locked: same as case 1 | clip complete, screen stays off | pass |
| 3 | Volume Up no longer changes volume | no volume slider appears on press | pass |
| 4 | Volume Down + Power still work | incl. Power long-press and Power+VolDown screenshot | pass (by design) |
| 5 | Fast double press within 400 ms | counts as one toggle (debounce) | pass |
| 6 | PC server stopped | clip queued, uploads itself once server returns | pass |
| 7 | Wi-Fi off during upload | retries and succeeds later | pass |
| 8 | Reboot | daemon auto-restarts, first press after boot works | pass |
| 9 | Phone call active | fails cleanly with a notification or records; never crashes | partial (code path only) |
| 10 | Kill switch file present | Volume Up changes volume again | pass |
| 11 | Long recording (30 min, screen off) | file complete, upload works | partial (50 s proven) |

## Local (pre-phone) verification already done

These prove the software independently of the phone:

- **Server** (`tests/logs/server/`): `/health` ok; `/upload` stores clip +
  sidecar; empty file -> 400; path-traversal device name sanitised inside
  `recordings/`.
- **Daemon** (`magisk-module/build/`): `volrecd.c` cross-compiles with NDK
  clang to a valid aarch64 Android ELF; module zip assembles.
- **App** (`tests/logs/app/`): debug APK builds with Gradle + SDK 35.
