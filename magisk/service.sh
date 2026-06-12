#!/system/bin/sh
# BrightnessFix Hardware Dimmer daemon.
# Re-asserts a low backlight value on sysfs whenever the framework drops the
# brightness to (or below) TRIGGER, giving true hardware dimming below the
# panel's software minimum.

MODDIR=${0%/*}

# ---- Defaults (overridden by brightnessfix.conf) ----
BL_PATH="/sys/class/leds/lcd-backlight/brightness"
MAX_PATH="/sys/class/leds/lcd-backlight/max_brightness"
FLOOR=1
TRIGGER=12
POLL_ON=1.5
POLL_OFF=30

CONF="$MODDIR/brightnessfix.conf"
[ -f "$CONF" ] && . "$CONF"

LOG="$MODDIR/brightnessfix.log"
log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG" 2>/dev/null; }

detect_backlight_paths() {
  if [ -n "$BL_PATH" ] && [ -e "$BL_PATH" ]; then
    return 0
  fi

  for candidate in /sys/class/backlight/*/brightness /sys/class/leds/*backlight*/brightness /sys/class/leds/*lcd*brightness/brightness; do
    [ -e "$candidate" ] || continue
    BL_PATH="$candidate"
    MAX_PATH="${candidate%/brightness}/max_brightness"
    log "detected backlight node: $BL_PATH"
    return 0
  done

  return 1
}

read_number() {
  cat "$1" 2>/dev/null | tr -d '\r\n'
}

# Fresh log each boot.
: > "$LOG" 2>/dev/null
log "daemon starting (pid $$)"

# Strip CR/whitespace that sneaks in when brightnessfix.conf is edited with
# Windows (CRLF) line endings, then validate the numeric settings. Without this,
# values like FLOOR="1\r" break every integer comparison and nothing dims.
sanitize() { printf '%s' "$1" | tr -d ' \t\r\n'; }
is_int() { case "$1" in ''|*[!0-9]*) return 1 ;; *) return 0 ;; esac; }

BL_PATH=$(sanitize "$BL_PATH")
MAX_PATH=$(sanitize "$MAX_PATH")
FLOOR=$(sanitize "$FLOOR")
TRIGGER=$(sanitize "$TRIGGER")
POLL_ON=$(sanitize "$POLL_ON")
POLL_OFF=$(sanitize "$POLL_OFF")

if ! is_int "$FLOOR"; then
  log "FLOOR='$FLOOR' invalid (sysfs needs a whole number, not a 0.0-1.0 value); using 1"
  FLOOR=1
fi
if ! is_int "$TRIGGER"; then
  log "TRIGGER='$TRIGGER' invalid (must be a whole number); using 12"
  TRIGGER=12
fi

# Wait for the system to finish booting.
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 2
done

if ! detect_backlight_paths; then
  log "no backlight node found; set BL_PATH manually in brightnessfix.conf"
  exit 0
fi

log "boot completed; floor=$FLOOR trigger=$TRIGGER path=$BL_PATH max=$MAX_PATH"

# Best-effort: make the node writable for any caller (DAC). SELinux/MAC for the
# daemon's own domain is handled by the root manager.
chmod 0666 "$BL_PATH" 2>/dev/null
chmod 0666 "$MAX_PATH" 2>/dev/null

run() {
  last_seen=-1
  last_written=-1
  logged_max=0
  stable_loops=0
  current_sleep="$POLL_ON"
  while true; do
    if [ ! -e "$BL_PATH" ]; then
      if ! detect_backlight_paths; then
        log "backlight node missing: $BL_PATH"
        sleep "$POLL_OFF"
        continue
      fi
      log "switched to backlight node: $BL_PATH"
      chmod 0666 "$BL_PATH" 2>/dev/null
      chmod 0666 "$MAX_PATH" 2>/dev/null
    fi

    cur=$(read_number "$BL_PATH")

    # Skip non-numeric / empty reads.
    case "$cur" in
      ''|*[!0-9]*) sleep "$POLL_OFF"; continue ;;
    esac

    # Log the panel range and first reading once, so you can pick TRIGGER/FLOOR.
    if [ "$logged_max" -eq 0 ]; then
      log "range: max_brightness=$(read_number "$MAX_PATH") first_read=$cur"
      logged_max=1
    fi

    # Screen off -> nothing to enforce; reset state.
    if [ "$cur" -eq 0 ]; then
      last_seen=-1
      last_written=-1
      stable_loops=0
      current_sleep="$POLL_OFF"
      sleep "$POLL_OFF"
      continue
    fi

    #  if hardware value has not changed, skip all enforcement math
    if [ "$cur" -eq "$last_seen" ]; then
      stable_loops=$((stable_loops + 1))
      # Back off polling after stable periods to reduce wakeups
      if [ "$stable_loops" -ge 20 ]; then
        current_sleep=8
      elif [ "$stable_loops" -ge 8 ]; then
        current_sleep=4
      else
        current_sleep="$POLL_ON"
      fi
      sleep "$current_sleep"
      continue
    fi

    last_seen=$cur

    # Only act when the value actually changed since our last write, so we
    # never fight our own output.
    if [ "$cur" -ne "$last_written" ]; then
      stable_loops=0
      current_sleep="$POLL_ON"
      if [ "$cur" -le "$TRIGGER" ] && [ "$cur" -gt "$FLOOR" ]; then
        echo "$FLOOR" > "$BL_PATH" 2>/dev/null
        last_written=$FLOOR
        log "enforced floor: $cur -> $FLOOR"
      else
        # Outside trigger window; let framework own it.
        last_written=$cur
      fi
    fi

    sleep "$current_sleep"
  done
}

# Run detached so the boot service script can return.
run &
