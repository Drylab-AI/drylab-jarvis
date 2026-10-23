#!/system/bin/sh
# Magisk late_start service for the Jarvis volume-key recorder.
# Runs as root after boot. Starts volrecd and keeps it alive.

MODDIR=${0%/*}
APP=com.jarvis.voicerec
STATE_DIR=/data/adb/volrec
LOG="$STATE_DIR/service.log"

mkdir -p "$STATE_DIR"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG"; }

# Wait for boot to finish so am / appops / the app are all available.
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
done
# A little extra settle time for the input stack.
sleep 5

log "boot complete, setting up"

# One-time app setup (idempotent):
#  - pre-grant RECORD_AUDIO so the app can record without a prompt
#  - whitelist from Doze so WorkManager uploads are not deferred for hours
appops set "$APP" RECORD_AUDIO allow 2>>"$LOG"
dumpsys deviceidle whitelist +"$APP" 2>>"$LOG"

# Keep volrecd running; restart with backoff if it dies.
BIN="$MODDIR/volrecd"
chmod 0755 "$BIN" 2>/dev/null

backoff=1
while true; do
    log "starting volrecd"
    "$BIN"
    rc=$?
    log "volrecd exited rc=$rc; restarting in ${backoff}s"
    sleep "$backoff"
    backoff=$((backoff * 2))
    [ "$backoff" -gt 30 ] && backoff=30
done &
