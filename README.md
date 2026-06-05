# BrightnessFix

BrightnessFix is an LSPosed/Xposed module for Android brightness minimum control, with an optional Magisk hardware dimmer for panels that still look too bright at software minimum.

## What This Project Contains

1. APK module (LSPosed/Xposed side)
- Applies framework-level minimum brightness fixes.
- Includes Android 16 QPR2 compatibility (float minimum path).
- Lets the slider and framework reach very low software values.

2. Magisk module (hardware side, optional but recommended)
- Writes directly to the backlight sysfs node.
- Re-applies a low hardware floor when the framework tries to raise it back.
- Needed for true extra dimming when panel hardware floor is still too bright.

## Why You May Need Both

On many ROMs, software minimum can be 0.0 but the panel hardware floor is still bright.
Use both components for full effect:

1. APK handles framework and slider behavior.
2. Magisk daemon handles real hardware backlight floor.

## Requirements

1. Rooted device for full setup.
2. LSPosed/Xposed installed for APK module.
3. Magisk (or compatible root module manager) for hardware dimmer.

## Installation (Recommended Order)

1. Install the BrightnessFix APK from GitHub Releases.
2. Enable it in LSPosed scope:
- Android (system framework)
- SystemUI
3. Reboot.
4. Install the hardware dimmer Magisk zip from the same Release.
5. Reboot.

## Magisk Hardware Dimmer Setup

After install, config file is usually:

/data/adb/modules/brightnessfix_hwdim/brightnessfix.conf

Main keys:

1. FLOOR
- Raw integer backlight value to force at minimum.
- Use whole numbers only (example: 1).
- Do not use decimal values like 0.1.

2. TRIGGER
- Raw integer threshold. When framework value is <= TRIGGER, daemon forces FLOOR.
- Keep TRIGGER > FLOOR.

3. BL_PATH
- Backlight sysfs path.
- Auto-detection is built in; set manually only if your device is unusual.

4. POLL_ON / POLL_OFF
- Daemon polling intervals while screen on/off.

## How To Tune For Your Device

1. Set slider to minimum.
2. Read daemon log:

/data/adb/modules/brightnessfix_hwdim/brightnessfix.log

3. Find lines like:
- range: max_brightness=... first_read=...
- observed: N (no action; trigger=... floor=...)

4. Set:
- FLOOR to your preferred dim integer (usually 1 to 3)
- TRIGGER to the observed minimum value (or slightly higher)

5. Reboot after edits.

Users should install both from the same version tag.

## Troubleshooting

1. No dimming change
- Confirm LSPosed scope includes Android and SystemUI.
- Confirm Magisk module is enabled.
- Check daemon log for detected path and enforce events.

2. Log shows invalid FLOOR/TRIGGER
- Use whole integers only.
- If edited on Windows, keep file clean (module now sanitizes common line-ending issues).

3. Backlight node missing
- Check daemon log for auto-detected path.
- If none found, set BL_PATH manually to your device path.

4. Conflicts with other modules
- Disable other brightness/smartpixels modules and retest.

## Disclaimer

1. Behavior varies across devices and ROMs.
2. Hardware dimming writes to sysfs and should be tuned carefully.
3. You are responsible for your device configuration.

## License

MIT License. See LICENSE.
