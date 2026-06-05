# BrightnessFix Hardware Dimmer (Magisk module)

Real hardware dimming for Android 16 QPR2 (and others). The Xposed module lets
the brightness slider reach the software minimum (`0.0`); this companion daemon
pushes the **actual backlight** below the panel's software floor by writing to
sysfs, and re-asserts that value whenever the framework tries to revert it.

It writes directly to `/sys/class/leds/lcd-backlight/brightness` — the node you
confirmed works with `echo 1 > ...`.

## How it works

A root daemon (`service.sh`, started at `late_start`) polls the backlight node:

- When the framework drops the backlight to **TRIGGER or lower** (i.e. the slider
  is at minimum), the daemon rewrites it to **FLOOR**.
- When you raise the slider above TRIGGER, the daemon backs off and lets the
  framework control brightness normally.
- It only reacts to *changes*, so it never fights its own writes (no flicker
  loop), and it idles while the screen is off.

## Install

**Option A — flashable zip (Magisk / KernelSU / APatch):**
1. Zip the **contents** of this `magisk/` folder (so `module.prop` and `META-INF/`
   are at the zip root — not the `magisk` folder itself).
2. Install the zip in your root manager.
3. Reboot.

**Option B — manual (root shell):**
```sh
su
mkdir -p /data/adb/modules/brightnessfix_hwdim
cp -r module.prop service.sh customize.sh brightnessfix.conf \
      /data/adb/modules/brightnessfix_hwdim/
chmod 0755 /data/adb/modules/brightnessfix_hwdim/service.sh
reboot
```

## Tune it (important)

Edit `brightnessfix.conf` (after install it lives at
`/data/adb/modules/brightnessfix_hwdim/brightnessfix.conf`):

| Key        | Meaning                                                            |
|------------|-------------------------------------------------------------------|
| `BL_PATH`  | Backlight sysfs node. Leave default to auto-detect common paths, or set manually if your device differs. |
| `FLOOR`    | Dim hardware value to force. **Raw sysfs integer** (e.g. 1), not a 0.0-1.0 value. |
| `TRIGGER`  | Force FLOOR when the framework value is ≤ this (raw integer).     |
| `POLL_ON`  | Poll seconds while screen is on.                                  |
| `POLL_OFF` | Poll seconds while screen is off.                                 |

> `FLOOR` and `TRIGGER` are **whole numbers** in the panel's raw range
> (`0`…`max_brightness`). Decimals like `0.1` are invalid and reset to `1`.
> The daemon also strips Windows (CRLF) line endings, so editing the file on a
> PC is safe.

### Finding your TRIGGER value
1. Set screen brightness to **minimum**.
2. Open the daemon log: `/data/adb/modules/brightnessfix_hwdim/brightnessfix.log`.
3. Note the `range: max_brightness=<M> first_read=<N>` and any `observed: <N>` line
   — `<N>` at minimum is your target. Set `TRIGGER` to that (or slightly higher).
   Keep `TRIGGER > FLOOR`.

After editing, reboot (or kill the daemon process and re-run `service.sh`).

## Notes & troubleshooting

- For the most consistent result, turn **off adaptive/auto brightness** — otherwise
  the framework keeps recomputing the backlight at the low end.
- Check the daemon log: `/data/adb/modules/brightnessfix_hwdim/brightnessfix.log`.
  You should see lines like `enforced floor: 10 -> 1`.
- If nothing dims:
  - Verify `cat $BL_PATH` reacts to `echo <n> > $BL_PATH` as root.
  - Confirm `BL_PATH` is correct for your panel.
  - Make sure `TRIGGER` is at/above the value the framework writes at minimum.
- If the screen ever gets stuck too dim, raise the slider (daemon backs off) or
  disable the module and reboot.
