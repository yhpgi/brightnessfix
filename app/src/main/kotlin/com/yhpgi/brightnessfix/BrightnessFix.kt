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
        XposedBridge.log("BrightnessFix: initZygote starting, minBrightnessFloat=$minBrightnessFloat")
        
        XResources.setSystemWideReplacement(
            "android",
            "integer",
            "config_screenBrightnessSettingMinimum",
            minBrightnessInt
        )
        XposedBridge.log("BrightnessFix: replaced config_screenBrightnessSettingMinimum with $minBrightnessInt")

        // Android 16 QPR2 uses the float config for clamping the slider minimum.
        XResources.setSystemWideReplacement(
            "android",
            "dimen",
            "config_screenBrightnessSettingMinimumFloat",
            minBrightnessFloat
        )
        XposedBridge.log("BrightnessFix: replaced config_screenBrightnessSettingMinimumFloat with $minBrightnessFloat")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            "android" -> hookSystemServer(lpparam.classLoader)
            "com.android.systemui" -> hookSystemUi(lpparam.classLoader)
        }
    }

    private fun hookSystemServer(classLoader: ClassLoader) {
        hookBrightnessClamperController(classLoader)
        hookDisplayBrightnessController(classLoader)
        hookDisplayPowerController(classLoader)
    }

    private fun hookSystemUi(classLoader: ClassLoader) {
        val controllerClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.settings.brightness.BrightnessController",
            classLoader
        )
        if (controllerClass == null) {
            XposedBridge.log("BrightnessFix: SystemUI BrightnessController not found")
            return
        }
        
        XposedBridge.log("BrightnessFix: hooking SystemUI BrightnessController.updateBrightnessInfo")
        XposedBridge.hookAllMethods(
            controllerClass,
            "updateBrightnessInfo",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val currentMin = XposedHelpers.getFloatField(param.thisObject, "mBrightnessMin")
                        XposedBridge.log("BrightnessFix: updateBrightnessInfo, mBrightnessMin=$currentMin")
                        if (currentMin > minBrightnessFloat) {
                            XposedBridge.log("BrightnessFix: overriding mBrightnessMin from $currentMin to $minBrightnessFloat")
                            XposedHelpers.setFloatField(param.thisObject, "mBrightnessMin", minBrightnessFloat)
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log("BrightnessFix: error in updateBrightnessInfo hook: ${t.message}")
                    }
                }
            }
        )
    }

    private fun hookBrightnessClamperController(classLoader: ClassLoader) {
        val clamperClass = XposedHelpers.findClassIfExists(
            "com.android.server.display.brightness.clamper.BrightnessClamperController",
            classLoader
        )
        if (clamperClass == null) {
            XposedBridge.log("BrightnessFix: BrightnessClamperController not found")
            return
        }
        
        XposedBridge.log("BrightnessFix: hooking BrightnessClamperController.getMinBrightness")
        XposedBridge.hookAllMethods(
            clamperClass,
            "getMinBrightness",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val currentMin = param.result as? Float ?: return
                    XposedBridge.log("BrightnessFix: getMinBrightness called, result=$currentMin")
                    if (currentMin > minBrightnessFloat) {
                        XposedBridge.log("BrightnessFix: overriding min from $currentMin to $minBrightnessFloat")
                        param.result = minBrightnessFloat
                    }
                }
            }
        )
    }

    private fun hookDisplayBrightnessController(classLoader: ClassLoader) {
        val controllerClass = XposedHelpers.findClassIfExists(
            "com.android.server.display.brightness.DisplayBrightnessController",
            classLoader
        )
        if (controllerClass == null) {
            XposedBridge.log("BrightnessFix: DisplayBrightnessController not found")
            return
        }
        
        XposedBridge.log("BrightnessFix: hooking DisplayBrightnessController.updateScreenBrightnessSetting")
        XposedBridge.hookAllMethods(
            controllerClass,
            "updateScreenBrightnessSetting",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val minArg = param.args.getOrNull(1) as? Float
                    XposedBridge.log("BrightnessFix: updateScreenBrightnessSetting called with minArg=$minArg")
                    if (minArg != null && minArg > minBrightnessFloat) {
                        XposedBridge.log("BrightnessFix: overriding minBrightness arg from $minArg to $minBrightnessFloat")
                        param.args[1] = minBrightnessFloat
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    setFloatFieldIfHigher(
                        param.thisObject,
                        "mCurrentMinBrightness",
                        minBrightnessFloat
                    )
                }
            }
        )
    }

    private fun hookDisplayPowerController(classLoader: ClassLoader) {
        val controllerClass = XposedHelpers.findClassIfExists(
            "com.android.server.display.DisplayPowerController",
            classLoader
        ) ?: return

        XposedBridge.hookAllMethods(
            controllerClass,
            "clampScreenBrightness",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val requested = param.args.getOrNull(0) as? Float ?: return
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
    }

    private fun setFloatFieldIfHigher(target: Any?, fieldName: String, minValue: Float) {
        if (target == null) return
        try {
            val currentValue = XposedHelpers.getFloatField(target, fieldName)
            XposedBridge.log("BrightnessFix: setFloatFieldIfHigher($fieldName): current=$currentValue, min=$minValue")
            if (currentValue > minValue) {
                XposedBridge.log("BrightnessFix: setting $fieldName to $minValue")
                XposedHelpers.setFloatField(target, fieldName, minValue)
            }
        } catch (t: Throwable) {
            XposedBridge.log("BrightnessFix: error in setFloatFieldIfHigher($fieldName): ${t.message}")
        }
    }

}