package com.yhpgi.brightnessfix

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

class BrightnessFix : IXposedHookLoadPackage {

    companion object {
        private const val MIN_BRIGHTNESS = 1
        private const val BRIGHTNESS_SYSFS = "/sys/class/leds/lcd-backlight/brightness"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        if (lpparam?.packageName != "com.android.systemserver") {
            return
        }

        try {
            val displayControllerClass = XposedHelpers.findClass(
                "com.android.server.display.DisplayPowerController",
                lpparam.classLoader
            )

            // Hook setBrightness method
            XposedHelpers.findAndHookMethod(
                displayControllerClass,
                "setBrightness",
                Float::class.java,
                object : XposedBridge.MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        val brightness = param?.args?.get(0) as? Float ?: return
                        val normalizedBrightness = (brightness * 255).toInt()

                        // Clamp to minimum brightness
                        if (normalizedBrightness < MIN_BRIGHTNESS) {
                            param.args[0] = MIN_BRIGHTNESS / 255f
                            enforceBrightnessSysfs(MIN_BRIGHTNESS)
                        }
                    }
                }
            )

            XposedBridge.log("BrightnessFix: Hooked DisplayPowerController.setBrightness")
        } catch (e: Exception) {
            XposedBridge.log("BrightnessFix: Error hooking setBrightness: ${e.message}")
        }

        // Also try hooking Settings brightness changes
        try {
            val settingsProviderClass = XposedHelpers.findClass(
                "com.android.providers.settings.SettingsProvider",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                settingsProviderClass,
                "insertForUser",
                Int::class.java,
                String::class.java,
                object : XposedBridge.MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        val table = param?.args?.get(1) as? String
                        if (table == "system") {
                            val contentValues = param.args.getOrNull(2)
                            val key = XposedHelpers.getObjectField(contentValues, "name") as? String
                            val value = XposedHelpers.getObjectField(contentValues, "value")

                            if (key == "screen_brightness" && value is Int) {
                                if (value < MIN_BRIGHTNESS) {
                                    XposedHelpers.setObjectField(contentValues, "value", MIN_BRIGHTNESS)
                                    enforceBrightnessSysfs(MIN_BRIGHTNESS)
                                }
                            }
                        }
                    }
                }
            )
            XposedBridge.log("BrightnessFix: Hooked SettingsProvider")
        } catch (e: Exception) {
            XposedBridge.log("BrightnessFix: Could not hook SettingsProvider: ${e.message}")
        }
    }

    private fun enforceBrightnessSysfs(brightness: Int) {
        try {
            val brightnessFile = File(BRIGHTNESS_SYSFS)
            if (brightnessFile.exists() && brightnessFile.canWrite()) {
                brightnessFile.writeText(brightness.toString())
            }
        } catch (e: Exception) {
            XposedBridge.log("BrightnessFix: Error writing to sysfs: ${e.message}")
        }
    }
}