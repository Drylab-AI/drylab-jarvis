# Root staging (ready, waiting on the bootloader unlock)

All of these are prepared on this PC so the root step is fast once the phone is
unlocked. Files live under `firmware/CP1A.260305.018/` (gitignored, too large).

| Artifact | State |
|---|---|
| `husky-factory.zip` | Downloaded, **SHA-256 verified** `9031afa6…52c00` (3.79 GB) |
| `init_boot.img` | Extracted from the inner image zip (8 MB, `ANDROID!` magic) |
| `init_boot.stock.img` | Backup copy = the way back if anything goes wrong |
| `Magisk-v30.7.apk` | Downloaded (11.6 MB), latest stable |

`init_boot.img` sha256: `6203ce62f4b0f3573b933fbe7439337d0cad6136e3190cfbc133866522bdb90a`

## Why this must wait for you

The phone is **LOCKED** (`ro.boot.flash.locked=1`). Unlocking the bootloader
**wipes the phone**, so there is no point installing the app or module before
then. The sequence from here (plan Phase 0.3 -> Phase 1):

1. **You:** back up photos, chats, 2FA.
2. **You:** Settings > Developer options > enable **OEM unlocking** (needs
   internet). If that toggle is greyed out, the device is carrier/FRP-locked
   and we stop.
3. `adb reboot bootloader` -> `fastboot flashing unlock` -> **you** confirm on
   the phone (Volume keys to choose, Power to confirm). Phone wipes + reboots.
4. **You:** redo setup, re-enable Developer options + USB debugging.
5. **Me:** `adb install Magisk-v30.7.apk`, `adb push init_boot.img /sdcard/Download/`.
6. **You:** in Magisk, Install > Select and Patch a File -> init_boot.img.
7. **Me:** pull the patched img, `fastboot flash init_boot magisk_patched-*.img`,
   reboot, verify `adb shell su -c id` -> uid=0.
8. **Bootloop safety:** `fastboot flash init_boot init_boot.stock.img`.
