#!/system/bin/sh
# Runs when the module is removed. Clean up our state and the virtual device
# goes away on reboot. We leave recordings and the app untouched.
rm -f /data/adb/volrec/disable
# Keep the logs for post-mortem; remove the dir only if empty.
rmdir /data/adb/volrec 2>/dev/null
