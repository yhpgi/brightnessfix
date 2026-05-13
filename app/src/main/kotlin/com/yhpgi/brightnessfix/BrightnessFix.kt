package com.yhpgi.brightnessfix

import android.content.res.XResources
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class BrightnessFix : IXposedHookZygoteInit, IXposedHookLoadPackage {

    private val minBrightnessInt = 1
    private val minBrightnessFloat = minBrightnessInt / 255f

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        XResources.setSystemWideReplacement(
            "android",
            "integer",
            "config_screenBrightnessSettingMinimum",
            minBrightnessInt
        )

        // Android 16 QPR2 uses the float config for clamping the slider minimum.
        XResources.setSystemWideReplacement(
            "android",
            "dimen",
            "config_screenBrightnessSettingMinimumFloat",
            minBrightnessFloat
        )
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            "android" -> hookSystemServer(lpparam.classLoader)
            "com.android.systemui" -> hookSystemUi(lpparam.classLoader)
        }
    }

    private fun hookSystemServer(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.display.brightness.BrightnessRangeController",
                classLoader,
                "getCurrentBrightnessMin",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val currentMin = param.result as? Float ?: return
                        if (currentMin > minBrightnessFloat) {
                            param.result = minBrightnessFloat
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("BrightnessFix: failed to hook BrightnessRangeController: ${t.message}")
        }

        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.display.brightness.DisplayBrightnessController",
                classLoader,
                "updateScreenBrightnessSetting",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val minArg = param.args[1] as? Float ?: return
                        if (minArg > minBrightnessFloat) {
                            param.args[1] = minBrightnessFloat
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("BrightnessFix: failed to hook DisplayBrightnessController: ${t.message}")
        }

        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.display.DisplayPowerController",
                classLoader,
                "clampScreenBrightness",
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val requested = param.args[0] as? Float ?: return
                        val clamped = param.result as? Float ?: return
                        if (requested < minBrightnessFloat) {
                            param.result = minBrightnessFloat
                            return
                        }
                        if (requested < clamped) {
                            param.result = requested
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("BrightnessFix: failed to hook DisplayPowerController: ${t.message}")
        }
    }

    private fun hookSystemUi(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.settings.brightness.BrightnessController",
                classLoader,
                "updateBrightnessInfo",
                "android.hardware.display.BrightnessInfo",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val info = param.args[0] ?: return
                        val minField = XposedHelpers.findField(info.javaClass, "brightnessMinimum")
                        val currentMin = minField.getFloat(info)
                        if (currentMin > minBrightnessFloat) {
                            minField.setFloat(info, minBrightnessFloat)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("BrightnessFix: failed to hook SystemUI BrightnessController: ${t.message}")
        }
    }

}