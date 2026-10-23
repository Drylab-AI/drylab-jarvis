# Pixel 8 Pro device info (read 2026-10-23)

adb now has permission after the udev rule.

## Identity & state

| Property | Value |
|---|---|
| model / device | Pixel 8 Pro / husky |
| serial | 37061FDJG006EY |
| build fingerprint | google/husky/husky:16/CP1A.260305.018/14887507:user/release-keys |
| build id | **CP1A.260305.018** (factory image must match this) |
| Android release | 16 |
| security patch | 2026-03-05 |
| bootloader | ripcurrent-16.4-14540574 |
| ro.boot.flash.locked | **1 (LOCKED)** |
| ro.boot.verifiedbootstate | green |
| ro.boot.secure_boot | PRODUCTION |
| ro.oem.key1 / sku | G1MNW |
| battery | 100%, charging |
| carrier (gsm.operator.alpha) | empty -> no SIM / unlocked, not carrier-locked |

`sys.oem_unlock_allowed` reads empty from adb; the real state shows in
Settings > Developer options > OEM unlocking. If that toggle is greyed out the
device is carrier/FRP-locked and we must stop.

## Input devices (getevent -pl)

| Node | Name | Keys | Use |
|---|---|---|---|
| /dev/input/event0 | **gpio_keys** | KEY_VOLUMEDOWN, KEY_VOLUMEUP, KEY_POWER | **physical buttons (target)** |
| /dev/input/event1 | goodix_fingerprint | HOME/UP/LEFT/RIGHT/DOWN/VOLUMEDOWN/VOLUMEUP/POWER/MENU/BACK/CAMERA/CHAT/SEARCH | fingerprint gestures (virtual) |
| /dev/input/event2 | fts | BTN_TOUCH + ABS_MT_* | touchscreen |
| /dev/input/event3 | cs40l26_input | FF_* | haptics / vibrator |

**Important for volrecd:** two nodes expose KEY_VOLUMEUP. The daemon must grab
the *physical-button* node (gpio_keys: has VOLUMEUP+VOLUMEDOWN+POWER and no
touch/ABS axes), never the fingerprint node, or it would swallow fingerprint
gestures and miss the real button.

Live `getevent -lt` confirmation of the physical press is deferred to Phase 2
(after root) because non-root getevent cannot read events on Android 16.

## Post-root confirmation (2026-10-23)

Rooted with Magisk v30.7:
- Patched init_boot.img in the Magisk app (Stock boot image detected -> All done).
- `fastboot flash init_boot magisk_patched.img` to slot b.
- `adb shell su -c id` -> `uid=0(root) ... context=u:r:magisk:s0`.
- `magisk -V` -> 30700. Bootloader still unlocked (locked=0), build CP1A.260305.018.

Root `getevent -pl` re-confirms KEY_VOLUMEUP on event0 (gpio_keys: Vol±/Power,
the physical buttons) and event1 (goodix_fingerprint). The daemon's scoring
picks gpio_keys. Live key-event proof will come from the daemon toggling
recording on a real Volume Up press (Phase 7 case 1).
