#!/system/bin/sh
# Magisk install script for BrightnessFix Hardware Dimmer.

ui_print "- BrightnessFix Hardware Dimmer"
ui_print "- Reads config from brightnessfix.conf"

BL_NODE="/sys/class/leds/lcd-backlight/brightness"
if [ -e "$BL_NODE" ]; then
  ui_print "- Found backlight node: $BL_NODE"
  ui_print "  current value: $(cat "$BL_NODE" 2>/dev/null)"
  ui_print "  max value:     $(cat /sys/class/leds/lcd-backlight/max_brightness 2>/dev/null)"
else
  ui_print "! Default backlight node not found."
  ui_print "! Edit BL_PATH in brightnessfix.conf after install."
fi

ui_print "- Setting permissions"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755

ui_print "- Tune FLOOR / TRIGGER / BL_PATH in:"
ui_print "    /data/adb/modules/brightnessfix_hwdim/brightnessfix.conf"
ui_print "- Reboot to activate"
