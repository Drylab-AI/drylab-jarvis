#!/system/bin/sh
# Magisk customize script. Runs at install time inside the recovery/Magisk
# installer environment. We just set permissions; the daemon is launched by
# service.sh on each boot.

ui_print "- Installing Jarvis Volume-Up Recorder"

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/volrecd" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755

mkdir -p /data/adb/volrec

ui_print "- Done. Reboot, then press Volume Up to toggle recording."
ui_print "- Kill switch: 'touch /data/adb/volrec/disable' to restore volume."
